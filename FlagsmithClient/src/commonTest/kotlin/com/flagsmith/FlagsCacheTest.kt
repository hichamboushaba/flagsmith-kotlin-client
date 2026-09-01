package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.internal.FlagsCache
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagsCacheTest {

    private val baseDir = "/cache".toPath()
    private val scope = FlagsCache.Scope(
        baseUrl = "https://edge.api.flagsmith.com/api/v1/",
        environmentKey = "env-key",
        identity = "person"
    )

    // A fixed "now" of 1_000_000 epoch millis (1000s) for deterministic TTL arithmetic.
    private val now = 1_000_000L

    private fun store(
        fileSystem: FileSystem,
        ttl: Duration = 3600.seconds,
        acceptStale: Boolean = false,
        scope: FlagsCache.Scope = this.scope,
        maxFileBytes: Long = 1L shl 20,
        nowMillis: () -> Long = { now }
    ) = FlagsCache(
        baseDirectory = baseDir,
        scope = scope,
        ttl = ttl,
        acceptStale = acceptStale,
        maxFileBytes = maxFileBytes,
        fileSystem = fileSystem,
        nowMillis = nowMillis
    )

    /** Defaults the fetch timestamp to the fixed test clock; a few tests pass their own. */
    private suspend fun FlagsCache.write(flags: List<Flag>, seq: Long) = write(flags, seq, now)

    private fun flag(name: String, value: Any?) = Flag(
        feature = Feature(id = 1L, name = name, type = "STANDARD"),
        enabled = true,
        featureStateValue = value
    )

    private val sampleFlags = listOf(
        flag("string-value", "hello"),
        flag("double-value", 756.0),
        flag("boolean-value", true),
        flag("null-value", null),
        flag("json-object-value", """{"nested":true}""")
    )

    @Test
    fun roundTripsAllValueTypes() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)

        s.write(sampleFlags, seq = 1)

        val loaded = s.readIfValid()
        assertNotNull(loaded)
        assertEquals(sampleFlags, loaded.flags)
        // The timestamp on disk is the one the caller recorded in memory, not one re-read from
        // the clock inside the write, so the two cannot drift apart.
        assertEquals(now, loaded.savedAtEpochMillis)
    }

    @Test
    fun missingFileReturnsNull() {
        assertNull(store(FakeFileSystem()).readIfValid())
    }

    @Test
    fun scopeHashMismatchReturnsNull() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)
        s.write(sampleFlags, seq = 1)

        // Tamper with the recorded scope hash so it no longer matches this store's scope.
        val raw = fs.source(s.file).buffer().use { it.readUtf8() }
        val tampered = raw.replace(s.scopeHash, "0".repeat(64))
        assertNotEquals(raw, tampered)
        fs.sink(s.file).buffer().use { it.writeUtf8(tampered) }

        assertNull(s.readIfValid())
    }

    @Test
    fun unknownFormatVersionReturnsNull() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)
        s.write(sampleFlags, seq = 1)

        val raw = fs.source(s.file).buffer().use { it.readUtf8() }
        val tampered = raw.replace("\"version\":1", "\"version\":99")
        assertNotEquals(raw, tampered)
        fs.sink(s.file).buffer().use { it.writeUtf8(tampered) }

        assertNull(s.readIfValid())
    }

    @Test
    fun expiredSnapshotReturnsNullUnlessStaleAccepted() = runTest {
        val fs = FakeFileSystem()
        val writtenAt = now
        store(fs, ttl = 3600.seconds).write(sampleFlags, seq = 1)

        // Read 4000s later with a strict TTL: expired.
        val strictStore = store(fs, ttl = 3600.seconds, acceptStale = false) { writtenAt + 4_000_000 }
        assertNull(strictStore.readIfValid())

        // Same instant with stale acceptance: served.
        val staleStore = store(fs, ttl = 3600.seconds, acceptStale = true) { writtenAt + 4_000_000 }
        assertEquals(sampleFlags, staleStore.readIfValid()?.flags)
    }

    @Test
    fun freshSnapshotWithinTtlIsServed() = runTest {
        val fs = FakeFileSystem()
        store(fs, ttl = 3600.seconds).write(sampleFlags, seq = 1)

        val laterStore = store(fs, ttl = 3600.seconds) { now + 3_600_000 }
        assertEquals(sampleFlags, laterStore.readIfValid()?.flags)
    }

    @Test
    fun backwardsClockJumpTreatedAsAgeZero() = runTest {
        val fs = FakeFileSystem()
        store(fs, ttl = 3600.seconds).write(sampleFlags, seq = 1)

        // "Now" before the write instant: age must clamp to 0 and stay eligible.
        val storeAfterClockJump = store(fs, ttl = 3600.seconds) { now - 10_000_000 }
        assertEquals(sampleFlags, storeAfterClockJump.readIfValid()?.flags)
    }

    @Test
    fun corruptFileReturnsNullAndNeverThrows() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)
        s.write(sampleFlags, seq = 1)

        fs.sink(s.file).buffer().use { it.writeUtf8("this is not json{") }

        assertNull(s.readIfValid())
    }

    @Test
    fun oversizedFileReturnsNull() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)
        s.write(sampleFlags, seq = 1)

        fs.sink(s.file).buffer().use { it.writeUtf8("x".repeat(2 * 1024 * 1024)) }

        assertNull(s.readIfValid())
    }

    @Test
    fun outOfOrderWriteIsSuppressed() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)

        val newer = listOf(flag("newer", 2.0))
        val older = listOf(flag("older", 1.0))
        s.write(newer, seq = 2)
        s.write(older, seq = 1)

        assertEquals(newer, s.readIfValid()?.flags)
    }

    @Test
    fun clearDeletesSnapshotAndBlocksOlderWrites() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)

        s.write(sampleFlags, seq = 1)
        assertNotNull(s.readIfValid())

        s.clear(barrierSeq = 5)

        assertNull(s.readIfValid())

        // A write started before the clear (seq <= barrier) must not repopulate the directory.
        s.write(sampleFlags, seq = 2)
        assertNull(s.readIfValid())

        // A write started after the clear (seq > barrier) is accepted.
        val afterClear = listOf(flag("after-clear", 9.0))
        s.write(afterClear, seq = 6)
        assertEquals(afterClear, s.readIfValid()?.flags)
    }

    @Test
    fun differentScopesMapToDifferentFiles() = runTest {
        val fs = FakeFileSystem()
        val personStore = store(fs)
        val envStore = store(fs, scope = scope.copy(identity = null))

        personStore.write(sampleFlags, seq = 1)

        assertTrue(fs.exists(personStore.file))
        assertNotEquals(personStore.file, envStore.file)
        assertNull(envStore.readIfValid())
        assertEquals(sampleFlags, personStore.readIfValid()?.flags)
    }

    @Test
    fun oversizedDocumentLeavesThePreviousSnapshotIntact() = runTest {
        val fs = FakeFileSystem()
        // Room for a one-flag document (a few hundred bytes) but not for two hundred of them.
        val s = store(fs, maxFileBytes = 1024)
        val small = listOf(flag("small", "value"))
        s.write(small, seq = 1)

        s.write(List(200) { flag("flag-$it", it.toDouble()) }, seq = 2)

        assertEquals(small, s.readIfValid()?.flags)
    }

    @Test
    fun clearLeavesOtherScopesUntouched() = runTest {
        val fs = FakeFileSystem()
        val personStore = store(fs)
        val envStore = store(fs, scope = scope.copy(identity = null))

        personStore.write(sampleFlags, seq = 1)
        envStore.write(sampleFlags, seq = 1)

        personStore.clear(barrierSeq = 1)

        assertNull(personStore.readIfValid())
        assertNotNull(envStore.readIfValid(), "Clearing one scope must not delete the others")
    }

    @Test
    fun writeStillLandsWhenTheTempFileDisappearsMidMove() = runTest {
        // A second FlagsCache for the same scope resolves to the same paths, so it can consume the
        // shared temp file before this one moves it. The write must still land a complete
        // document rather than truncating the snapshot to nothing.
        val fs = object : ForwardingFileSystem(FakeFileSystem()) {
            /** Steals the temp file just before the move, as a competing writer would. */
            override fun atomicMove(source: Path, target: Path) {
                delegate.delete(source, mustExist = false)
                super.atomicMove(source, target)
            }
        }
        val s = store(fs)

        s.write(sampleFlags, seq = 1)

        assertEquals(sampleFlags, s.readIfValid()?.flags)
    }

    @Test
    fun clearDoesNotDeleteASnapshotWrittenAfterItWasRequested() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)

        // `clearCache()` captures its barrier and releases the state lock before dispatching the
        // delete, so an operation starting after it can land its snapshot first. That write is
        // legitimately newer and must survive the pending clear.
        s.write(sampleFlags, seq = 2)
        s.clear(barrierSeq = 1)

        assertEquals(sampleFlags, s.readIfValid()?.flags)
    }

    @Test
    fun writeRecordsTheCallersFetchTimestamp() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs, nowMillis = { now + 5_000 })

        s.write(sampleFlags, seq = 1, fetchedAtMillis = now)

        assertEquals(now, s.readIfValid()?.savedAtEpochMillis)
    }

    @Test
    fun clearStillDeletesWhenANewerWriteNeverReachedDisk() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs, maxFileBytes = 1024)
        s.write(listOf(flag("small", "value")), seq = 1)

        // Claims sequence 2 for ordering, but the document is too large to persist, so the file
        // still holds the sequence-1 content. A clear barrier of 1 must therefore still delete:
        // gating on "what was requested" rather than "what landed" would leave it in place.
        s.write(List(200) { flag("flag-$it", it.toDouble()) }, seq = 2)

        s.clear(barrierSeq = 1)

        assertNull(s.readIfValid())
    }

    @Test
    fun writesLeaveNoTempFilesBehind() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)

        s.write(sampleFlags, seq = 1)
        s.write(sampleFlags, seq = 2)

        // Temp names are unique per write so two writers cannot truncate each other's file, which
        // makes cleaning them up the writer's job rather than the name's.
        val dir = baseDir / "flagsmith-flags-cache"
        assertTrue(fs.list(dir).none { it.name.endsWith(".tmp") }, "Temp files must not accumulate")
    }

    @Test
    fun pruningKeepsOnlyTheNewestFiles() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)
        val dir = s.file.parent!!
        fs.createDirectories(dir)

        repeat(6) { index ->
            fs.sink(dir / "scope-$index.json").buffer().use { it.writeUtf8("{}") }
        }

        s.write(sampleFlags, seq = 1)

        val remaining = fs.list(dir).filterNot { it.name.endsWith(".tmp") }
        assertEquals(
            4,
            remaining.size,
            "Expected pruning to cap the directory at maxFiles, including the one just written"
        )
        assertNotNull(
            s.readIfValid(),
            "The file just written must never be pruned"
        )
    }
}
