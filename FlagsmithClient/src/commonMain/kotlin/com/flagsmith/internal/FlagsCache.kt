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
import kotlin.random.Random
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
    internal data class Snapshot(val flags: List<Flag>, val savedAtEpochMillis: Long)

    /** On-disk format. Private: nothing outside this class should depend on it. */
    @Serializable
    private data class CachedFlags(
        val version: Int = FORMAT_VERSION,
        val scopeHash: String,
        val savedAtEpochMillis: Long,
        val flags: List<Flag>
    )

    // The identity is tagged rather than defaulted to "", so an environment-scoped instance can
    // never share a file with one built from an empty identity string.
    internal val scopeHash: String = with(scope) {
        "$baseUrl|$environmentKey|${identity?.let { "identity:$it" } ?: "environment"}"
            .encodeUtf8().sha256().hex()
    }

    private val directory: Path = baseDirectory / DIR_NAME

    // Exposed for tests only.
    internal val file: Path = directory / "$scopeHash.json"

    private val ioMutex = Mutex()

    // Ordering guard: advanced even when the write below fails, so a superseded write can never
    // land after a newer one.
    private var lastWrittenSeq = 0L // guarded by ioMutex

    // What is actually on disk. Only advanced by a write that completed, so `clear` can tell a
    // real post-clear snapshot from one whose write was skipped or threw.
    private var lastPersistedSeq = 0L // guarded by ioMutex
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

        // A future-dated snapshot (the clock moved backwards) counts as fresh rather than being
        // discarded: priming only decides whether the flow starts populated, it never suppresses
        // a fetch, so the worst case is showing known-good flags for one request. Only the TTL
        // gate suppresses fetches, which is why that one must not clamp - see
        // Flagsmith.cachedFlagsWithinTtl.
        val ageMillis = (nowMillis() - cached.savedAtEpochMillis).coerceAtLeast(0)
        if (!acceptStale && ageMillis > ttl.inWholeMilliseconds) return null

        return Snapshot(cached.flags, cached.savedAtEpochMillis)
    }

    /**
     * Persists [flags] for the operation identified by [seq]. Out-of-order or superseded writes
     * (older than the newest one already handled) are dropped. Never throws.
     */
    suspend fun write(flags: List<Flag>, seq: Long, fetchedAtMillis: Long): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (seq > lastWrittenSeq) {
                lastWrittenSeq = seq
                val persisted = runCatching { writeSnapshot(flags, fetchedAtMillis) }.getOrDefault(false)
                if (persisted) lastPersistedSeq = seq
            }
        }
        deleteLegacyHttpCacheOnce()
    }

    /**
     * Deletes this scope's snapshot, leaving sibling scopes sharing the directory untouched.
     * [barrierSeq] is the sequence barrier captured by `Flagsmith.clearCache()`; any write
     * requested with a lower or equal sequence is dropped, so an operation started before the
     * clear can never repopulate the file afterwards.
     */
    suspend fun clear(barrierSeq: Long): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            // A snapshot from after this clear was requested is already on disk, so it supersedes
            // the clear: `clearCache()` releases its state lock before dispatching here, leaving
            // room for that write to land first. This deliberately tests what was *persisted*, not
            // what was requested — a write whose sequence was claimed but which then threw or was
            // skipped leaves the pre-clear file in place, and that must still be deleted.
            if (barrierSeq < lastPersistedSeq) return@withLock

            lastWrittenSeq = maxOf(lastWrittenSeq, barrierSeq)
            lastPersistedSeq = 0L // nothing left on disk
            runCatching { fileSystem.delete(file, mustExist = false) }
            deleteTempFiles()
        }
    }

    private fun readCachedFlags(): CachedFlags? {
        if (!fileSystem.exists(file)) return null
        if ((fileSystem.metadata(file).size ?: 0L) > maxFileBytes) return null
        return json.decodeFromString<CachedFlags>(fileSystem.source(file).buffer().use { it.readUtf8() })
    }

    /** Returns whether the snapshot reached disk. */
    private fun writeSnapshot(flags: List<Flag>, fetchedAtMillis: Long): Boolean {
        val encoded = json.encodeToString(
            CachedFlags(scopeHash = scopeHash, savedAtEpochMillis = fetchedAtMillis, flags = flags)
        ).encodeUtf8()
        // Checked before writing so an oversized document leaves the previous snapshot intact
        // instead of replacing it with a file that readIfValid would reject anyway.
        if (encoded.size > maxFileBytes) return false

        fileSystem.createDirectories(directory, mustCreate = false)
        // A fresh temp name per write: another FlagsCache for this scope (a second instance, or
        // another process) resolves to the same directory, and on a shared temp path it could
        // truncate ours mid-write and leave us moving a half-written file over a good snapshot.
        val tmpFile = directory / "$scopeHash.${Random.nextInt().toUInt().toString(16)}.tmp"
        fileSystem.sink(tmpFile).buffer().use { it.write(encoded) }

        replaceSnapshotWith(tmpFile, encoded)
        pruneToNewest()
        return true
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

    /**
     * Moves the temp file over the snapshot, falling back to an in-place write of [encoded].
     *
     * The fallback deliberately writes from memory rather than copying from the temp file: another
     * `FlagsCache` for the same scope (a second `Flagsmith` instance sharing the cache directory)
     * resolves to the same paths and may have already consumed it. Copying would then fail with
     * the target already truncated, leaving an empty file where a valid snapshot used to be.
     */
    private fun replaceSnapshotWith(tmpFile: Path, encoded: ByteString) {
        if (tryAtomicMove(tmpFile)) return

        // The target may already exist on filesystems where rename doesn't replace.
        runCatching { fileSystem.delete(file) }
        if (tryAtomicMove(tmpFile)) return

        // Non-atomic, so a concurrent reader may observe a torn file; readIfValid rejects that.
        fileSystem.sink(file).buffer().use { it.write(encoded) }
        runCatching { fileSystem.delete(tmpFile) }
    }

    private fun tryAtomicMove(tmpFile: Path): Boolean = try {
        fileSystem.atomicMove(tmpFile, file)
        true
    } catch (_: IOException) {
        false
    }

    private fun deleteTempFiles() {
        runCatching {
            fileSystem.list(directory)
                .filter { it.name.startsWith("$scopeHash.") && it.name.endsWith(".tmp") }
                .forEach { runCatching { fileSystem.delete(it) } }
        }
    }

    private fun pruneToNewest() {
        // Temp files left by a crashed write are not snapshots and would otherwise linger forever.
        deleteTempFiles()
        runCatching {
            fileSystem.list(directory)
                .filterNot { it.name.endsWith(".tmp") }
                // Never prune the file we just wrote: on filesystems with coarse mtime
                // granularity the sort order is arbitrary when several files share a tick.
                // It counts towards maxFiles, hence `maxFiles - 1` siblings are kept.
                .filterNot { it == file }
                .sortedByDescending { fileSystem.metadata(it).lastModifiedAtMillis ?: 0L }
                .drop(maxFiles - 1)
                .forEach { fileSystem.delete(it) }
        }
    }
}
