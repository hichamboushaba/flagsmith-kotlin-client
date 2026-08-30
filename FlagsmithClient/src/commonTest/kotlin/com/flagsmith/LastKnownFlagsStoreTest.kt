package com.flagsmith

import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.internal.LastKnownFlagsStore
import kotlinx.coroutines.test.runTest
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

class LastKnownFlagsStoreTest {

    private val baseDir = "/cache".toPath()
    private val scope = LastKnownFlagsStore.Scope(
        baseUrl = "https://edge.api.flagsmith.com/api/v1/",
        environmentKey = "env-key",
        identity = "person"
    )

    // A fixed "now" of 1_000_000 epoch millis (1000s) for deterministic TTL arithmetic.
    private val now = 1_000_000L

    private fun store(
        fileSystem: FakeFileSystem,
        ttlSeconds: Long = 3600,
        acceptStale: Boolean = false,
        scope: LastKnownFlagsStore.Scope = this.scope,
        nowMillis: () -> Long = { now }
    ) = LastKnownFlagsStore(
        baseDirectory = baseDir,
        scope = scope,
        ttlSeconds = ttlSeconds,
        acceptStale = acceptStale,
        fileSystem = fileSystem,
        nowMillis = nowMillis
    )

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
        assertEquals(sampleFlags, loaded)
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
        store(fs, ttlSeconds = 3600).write(sampleFlags, seq = 1)

        // Read 4000s later with a strict TTL: expired.
        val strictStore = store(fs, ttlSeconds = 3600, acceptStale = false) { writtenAt + 4_000_000 }
        assertNull(strictStore.readIfValid())

        // Same instant with stale acceptance: served.
        val staleStore = store(fs, ttlSeconds = 3600, acceptStale = true) { writtenAt + 4_000_000 }
        assertEquals(sampleFlags, staleStore.readIfValid())
    }

    @Test
    fun freshSnapshotWithinTtlIsServed() = runTest {
        val fs = FakeFileSystem()
        store(fs, ttlSeconds = 3600).write(sampleFlags, seq = 1)

        val laterStore = store(fs, ttlSeconds = 3600) { now + 3_600_000 }
        assertEquals(sampleFlags, laterStore.readIfValid())
    }

    @Test
    fun backwardsClockJumpTreatedAsAgeZero() = runTest {
        val fs = FakeFileSystem()
        store(fs, ttlSeconds = 3600).write(sampleFlags, seq = 1)

        // "Now" before the write instant: age must clamp to 0 and stay eligible.
        val storeAfterClockJump = store(fs, ttlSeconds = 3600) { now - 10_000_000 }
        assertEquals(sampleFlags, storeAfterClockJump.readIfValid())
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

        assertEquals(newer, s.readIfValid())
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
        assertEquals(afterClear, s.readIfValid())
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
        assertEquals(sampleFlags, personStore.readIfValid())
    }

    @Test
    fun pruningKeepsOnlyTheNewestFiles() = runTest {
        val fs = FakeFileSystem()
        val s = store(fs)
        val dir = baseDir / "flagsmith-last-known"
        fs.createDirectories(dir)

        repeat(6) { index ->
            fs.sink(dir / "scope-$index.json").buffer().use { it.writeUtf8("{}") }
        }

        s.write(sampleFlags, seq = 1)

        val remaining = fs.list(dir).filterNot { it.name.endsWith(".tmp") }
        assertEquals(
            5,
            remaining.size,
            "Expected pruning to cap the directory at 5 files (4 others + the one just written)"
        )
        assertNotNull(
            s.readIfValid(),
            "The file just written must never be pruned"
        )
    }
}
