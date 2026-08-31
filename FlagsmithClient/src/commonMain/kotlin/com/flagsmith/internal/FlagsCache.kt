package com.flagsmith.internal

import com.flagsmith.defaultJson
import com.flagsmith.entities.Flag
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.*
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration

private const val DIR_NAME = "flagsmith-flags-cache"
private const val LEGACY_HTTP_CACHE_DIR = "flagsmith"
private const val DEFAULT_MAX_FILES = 4
private const val DEFAULT_MAX_FILE_BYTES = 1L shl 20 // 1 MB
private const val FORMAT_VERSION = 1

/**
 * Persists the flags most recently emitted to [com.flagsmith.Flagsmith.flagUpdateFlow] so they can
 * be primed back into the flow on the next cold start, before any network call.
 *
 * One file per [Scope] (base url + environment key + identity). Writes go to a temp file followed
 * by an atomic rename, which is what makes [readIfValid] safe to call without a lock: a reader
 * observes either the complete old file or the complete new one, never a torn one.
 */
internal class FlagsCache(
    private val baseDirectory: Path,
    scope: Scope,
    private val ttl: Duration,
    private val acceptStale: Boolean,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val json: Json = defaultJson,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val nowMillis: () -> Long = ::getTimeMillis,
) {
    internal data class Scope(val baseUrl: String, val environmentKey: String, val identity: String?)

    /** A valid cached document: the flags plus the instant they were fetched at. */
    internal data class Snapshot(val flags: List<Flag>, val savedAtEpochSeconds: Long)

    /** On-disk format. Private: nothing outside this class should depend on it. */
    @Serializable
    private data class CachedFlags(
        val version: Int = FORMAT_VERSION,
        val scopeHash: String,
        val savedAtEpochSeconds: Long,
        val flags: List<Flag>
    )

    internal val scopeHash: String =
        "${scope.baseUrl}|${scope.environmentKey}|${scope.identity ?: ""}".encodeUtf8().sha256().hex()

    private val directory: Path = baseDirectory / DIR_NAME

    // Exposed for tests only.
    internal val file: Path = directory / "$scopeHash.json"

    private val ioMutex = Mutex()
    private var lastWrittenSeq = 0L // guarded by ioMutex
    private var legacyHttpCacheCleaned = false // guarded by ioMutex

    /**
     * Returns the cached flags if a valid, in-policy snapshot exists, otherwise `null`.
     *
     * Non-suspending and lock-free: it runs on the caller's thread the first time `flagUpdateFlow`
     * is accessed. Never throws — a missing, oversized, corrupt, foreign or expired file is simply
     * "no snapshot".
     */
    fun readIfValid(): Snapshot? {
        val cached = runCatching { readCachedFlags() }.getOrNull() ?: return null
        if (cached.version != FORMAT_VERSION || cached.scopeHash != scopeHash) return null

        val ageSeconds = ((nowMillis() / 1000) - cached.savedAtEpochSeconds).coerceAtLeast(0)
        if (!acceptStale && ageSeconds > ttl.inWholeSeconds) return null

        return Snapshot(cached.flags, cached.savedAtEpochSeconds)
    }

    /**
     * Persists [flags] for the operation identified by [seq]. Out-of-order or superseded writes
     * (older than the newest one already handled) are dropped. Never throws.
     */
    suspend fun write(flags: List<Flag>, seq: Long): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (seq > lastWrittenSeq) {
                lastWrittenSeq = seq
                runCatching { writeSnapshot(flags) }
            }
        }
        deleteLegacyHttpCacheOnce()
    }

    /**
     * Deletes the cache directory. [barrierSeq] is the sequence barrier captured by
     * `Flagsmith.clearCache()`; any write requested with a lower or equal sequence is dropped, so
     * an operation started before the clear can never repopulate the directory afterwards.
     */
    suspend fun clear(barrierSeq: Long): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            lastWrittenSeq = maxOf(lastWrittenSeq, barrierSeq)
            runCatching { fileSystem.deleteRecursively(directory, mustExist = false) }
        }
    }

    private fun readCachedFlags(): CachedFlags? {
        if (!fileSystem.exists(file)) return null
        if ((fileSystem.metadata(file).size ?: 0L) > maxFileBytes) return null
        return json.decodeFromString<CachedFlags>(fileSystem.source(file).buffer().use { it.readUtf8() })
    }

    private fun writeSnapshot(flags: List<Flag>) {
        fileSystem.createDirectories(directory, mustCreate = false)

        val tmp = directory / "$scopeHash.json.tmp"
        val cached = CachedFlags(
            scopeHash = scopeHash,
            savedAtEpochSeconds = nowMillis() / 1000,
            flags = flags
        )
        fileSystem.sink(tmp).buffer().use { it.writeUtf8(json.encodeToString(cached)) }

        moveAtomicallyOrFallback(tmp, file)
        pruneToNewest()
    }

    /**
     * 0.1.x installations left a Ktor HTTP cache under `<cacheDirectoryPath>/flagsmith` that
     * nothing reads anymore, so reclaim it once. The claim is latched under [ioMutex], but the
     * delete itself deliberately runs outside the lock: it can walk megabytes of files and must
     * not block concurrent snapshot writes. Best-effort — a failure is not retried.
     */
    private suspend fun deleteLegacyHttpCacheOnce() {
        val claimedCleanup = ioMutex.withLock {
            val isFirstWrite = !legacyHttpCacheCleaned
            legacyHttpCacheCleaned = true
            isFirstWrite
        }
        if (!claimedCleanup) return

        runCatching {
            fileSystem.deleteRecursively(baseDirectory / LEGACY_HTTP_CACHE_DIR, mustExist = false)
        }
    }

    private fun moveAtomicallyOrFallback(tmp: Path, target: Path) {
        if (tryAtomicMove(tmp, target)) return

        // The target may already exist on filesystems where rename doesn't replace.
        runCatching { fileSystem.delete(target) }
        if (tryAtomicMove(tmp, target)) return

        // Last resort: a direct, non-atomic copy. A torn result is rejected by readIfValid's
        // parse and version checks, never surfaced as a crash.
        fileSystem.sink(target).buffer().use { sink ->
            fileSystem.source(tmp).buffer().use { source -> sink.writeAll(source) }
        }
        runCatching { fileSystem.delete(tmp) }
    }

    private fun tryAtomicMove(tmp: Path, target: Path): Boolean = try {
        fileSystem.atomicMove(tmp, target)
        true
    } catch (_: IOException) {
        false
    }

    private fun pruneToNewest() {
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
