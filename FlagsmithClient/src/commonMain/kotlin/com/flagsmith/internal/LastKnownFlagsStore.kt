package com.flagsmith.internal

import com.flagsmith.defaultJson
import com.flagsmith.entities.Flag
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.*
import okio.ByteString.Companion.encodeUtf8

private const val DIR_NAME = "flagsmith-last-known"
private const val FORMAT_VERSION = 1
private const val MAX_FILE_BYTES = 1L shl 20 // 1 MB
private const val MAX_FILES = 4

/**
 * Persists the flags most recently emitted to [com.flagsmith.Flagsmith.flagUpdateFlow] so they can
 * be primed back into the flow on the next cold start, before any network call.
 *
 * One file per [Scope] (base url + environment key + identity), stored in a sibling directory of
 * the Ktor HTTP cache so that HTTP-cache invalidation and trimming never touch it. Writes go to a
 * temp file followed by an atomic rename, which makes the (non-suspending, lock-free) priming read
 * safe against concurrent writes: a reader observes either the complete old file or the complete
 * new one.
 */
internal class LastKnownFlagsStore(
    baseDirectory: Path,
    scope: Scope,
    private val ttlSeconds: Long,
    private val acceptStale: Boolean,
    private val json: kotlinx.serialization.json.Json = defaultJson,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val nowMillis: () -> Long = ::getTimeMillis,
) {
    internal data class Scope(val baseUrl: String, val environmentKey: String, val identity: String?)

    @Serializable
    private data class LastKnownFlags(
        val version: Int = FORMAT_VERSION,
        val scopeHash: String,
        @SerialName("saved_at_epoch_seconds") val savedAtEpochSeconds: Long,
        val flags: List<Flag>
    )

    internal val scopeHash: String =
        "${scope.baseUrl}|${scope.environmentKey}|${scope.identity ?: ""}".encodeUtf8().sha256().hex()

    private val directory: Path = baseDirectory / DIR_NAME

    // Exposed for tests only.
    internal val file: Path = directory / "$scopeHash.json"

    private val ioMutex = Mutex()
    private var lastWrittenSeq = 0L // guarded by ioMutex

    internal data class Snapshot(val flags: List<Flag>, val savedAtEpochSeconds: Long)

    /**
     * Reads the last-known flags if a valid, in-policy snapshot exists.
     *
     * Non-suspending and lock-free: it is called on the caller's thread the first time
     * `flagUpdateFlow` is accessed. Safe against concurrent writes because writes replace the file
     * atomically. Any failure (missing file, oversized file, parse error, unknown format version,
     * scope mismatch, expired snapshot) returns `null` and never throws.
     */
    fun readIfValid(): Snapshot? = runCatching {
        if (!fileSystem.exists(file)) return null
        if ((fileSystem.metadata(file).size ?: 0L) > MAX_FILE_BYTES) return null

        val snapshot = json.decodeFromString<LastKnownFlags>(
            fileSystem.source(file).buffer().use { it.readUtf8() }
        )
        if (snapshot.version != FORMAT_VERSION || snapshot.scopeHash != scopeHash) return null

        val ageSeconds = ((nowMillis() / 1000) - snapshot.savedAtEpochSeconds).coerceAtLeast(0)
        if (!acceptStale && ageSeconds > ttlSeconds) return null

        Snapshot(snapshot.flags, snapshot.savedAtEpochSeconds)
    }.getOrNull()

    /**
     * Persists [flags] for the operation identified by [seq]. Out-of-order or superseded writes
     * (older than the newest one already handled) are dropped. Never throws.
     */
    suspend fun write(flags: List<Flag>, seq: Long): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (seq <= lastWrittenSeq) return@withLock
            lastWrittenSeq = seq
            runCatching {
                fileSystem.createDirectories(directory, mustCreate = false)
                val tmp = directory / "$scopeHash.json.tmp"
                fileSystem.sink(tmp).buffer().use { output ->
                    output.writeUtf8(
                        json.encodeToString(
                            LastKnownFlags(
                                scopeHash = scopeHash,
                                savedAtEpochSeconds = nowMillis() / 1000,
                                flags = flags
                            )
                        )
                    )
                }
                moveAtomicallyOrFallback(tmp, file)
                pruneToNewest(MAX_FILES)
            }
        }
    }

    /**
     * Deletes the snapshot directory. [barrierSeq] is the sequence barrier captured by
     * `Flagsmith.clearCache()`; any write requested with a lower or equal sequence is dropped, so
     * an operation started before the clear can never repopulate the directory afterwards.
     */
    suspend fun clear(barrierSeq: Long): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            lastWrittenSeq = maxOf(lastWrittenSeq, barrierSeq)
            runCatching { fileSystem.deleteRecursively(directory, mustExist = false) }
        }
    }

    private fun moveAtomicallyOrFallback(tmp: Path, target: Path) {
        try {
            fileSystem.atomicMove(tmp, target)
            return
        } catch (_: IOException) {
            // Fall through to the fallback chain.
        }
        runCatching { fileSystem.delete(target) }
        try {
            fileSystem.atomicMove(tmp, target)
        } catch (_: IOException) {
            // Last resort: a direct (non-atomic) write. A torn result from this path is rejected
            // by readIfValid's parse/version checks, never surfaced as a crash.
            fileSystem.sink(target).buffer().use { targetSink ->
                fileSystem.source(tmp).buffer().use { tmpSource ->
                    targetSink.writeAll(tmpSource)
                }
            }
            runCatching { fileSystem.delete(tmp) }
        }
    }

    private fun pruneToNewest(maxFiles: Int) {
        runCatching {
            fileSystem.list(directory)
                .filterNot { it.name.endsWith(".tmp") }
                // Never prune the file we just wrote: on filesystems with coarse mtime
                // granularity the sort order is arbitrary when several files share a tick.
                .filterNot { it == file }
                .sortedByDescending { fileSystem.metadata(it).lastModifiedAtMillis ?: 0L }
                .drop(maxFiles)
                .forEach { fileSystem.delete(it) }
        }
    }
}
