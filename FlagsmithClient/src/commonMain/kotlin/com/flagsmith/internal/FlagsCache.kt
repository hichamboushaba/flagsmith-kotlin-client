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
private const val FORMAT_VERSION = 2

/**
 * Persists the flags most recently emitted to `flagUpdateFlow` so they can prime it on the next
 * cold start, before any network call. One file per [Scope]. Writes go to a temp file followed by
 * an atomic rename, which is what makes [readIfValid] safe without a lock: a reader sees either
 * the complete old file or the complete new one.
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
    /**
     * Deliberately excludes the transient marker: a transient and a non-transient instance for one
     * identity share the snapshot for priming, and the request key keeps them apart at the gate.
     */
    internal data class Scope(val baseUrl: String, val environmentKey: String, val identity: String?)

    /** A valid cached document, with the encoded request key it was fetched under. */
    internal data class Snapshot(
        val flags: List<Flag>,
        val savedAtEpochMillis: Long,
        val requestKey: String?,
    )

    /** On-disk format. Private: nothing outside this class should depend on it. */
    @Serializable
    private data class CachedFlags(
        val version: Int = FORMAT_VERSION,
        val scopeHash: String,
        val savedAtEpochMillis: Long,
        val requestKey: String? = null,
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

    // Advanced even when the write fails, so a superseded write can never land after a newer one.
    private var lastWrittenSeq = 0L // guarded by ioMutex

    // What is actually on disk: only advanced by a write that completed, so `clear` can tell a
    // real post-clear snapshot from one whose write was skipped or threw.
    private var lastPersistedSeq = 0L // guarded by ioMutex
    private var legacyHttpCacheCleaned = false // guarded by ioMutex

    /**
     * The cached flags if a valid, in-policy snapshot exists, otherwise `null`. Lock-free and never
     * throws: a missing, oversized, corrupt, foreign or expired file is simply "no snapshot".
     */
    fun readIfValid(): Snapshot? {
        val cached = runCatching { readCachedFlags() }.getOrNull() ?: return null
        if (cached.version != FORMAT_VERSION || cached.scopeHash != scopeHash) return null

        // A future-dated snapshot (the clock moved backwards) counts as fresh: priming never
        // suppresses a fetch, so the worst case is showing known-good flags for one request. The
        // TTL gate does suppress fetches, which is why it must not clamp - see Flagsmith.withinTtlGate.
        val ageMillis = (nowMillis() - cached.savedAtEpochMillis).coerceAtLeast(0)
        if (!acceptStale && ageMillis > ttl.inWholeMilliseconds) return null

        return Snapshot(cached.flags, cached.savedAtEpochMillis, cached.requestKey)
    }

    /**
     * Persists [flags] for the operation [seq], tagged with the encoded [requestKey]. The key is
     * persisted, never the traits: they are user PII and the cache directory is unencrypted.
     * Superseded writes are dropped. Never throws.
     */
    suspend fun write(
        flags: List<Flag>,
        seq: Long,
        fetchedAtMillis: Long,
        requestKey: String?,
    ): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (seq > lastWrittenSeq) {
                lastWrittenSeq = seq
                val persisted = runCatching { writeSnapshot(flags, fetchedAtMillis, requestKey) }.getOrDefault(false)
                if (persisted) lastPersistedSeq = seq
            }
        }
        deleteLegacyHttpCacheOnce()
    }

    /**
     * Deletes this scope's snapshot, leaving sibling scopes untouched. Any write with a sequence at
     * or below [barrierSeq] (captured by `Flagsmith.clearCache()`) is dropped, so an operation
     * started before the clear can never repopulate the file.
     */
    suspend fun clear(barrierSeq: Long): Unit = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            // A snapshot persisted after this clear was requested supersedes it: clearCache()
            // releases its state lock before dispatching here. This tests what was *persisted*,
            // not what was requested: a write whose sequence was claimed but never landed leaves
            // the pre-clear file in place, and that must still be deleted.
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
    private fun writeSnapshot(flags: List<Flag>, fetchedAtMillis: Long, requestKey: String?): Boolean {
        val encoded = json.encodeToString(
            CachedFlags(
                scopeHash = scopeHash,
                savedAtEpochMillis = fetchedAtMillis,
                requestKey = requestKey,
                flags = flags
            )
        ).encodeUtf8()
        // Checked before writing so an oversized document leaves the previous snapshot intact.
        if (encoded.size > maxFileBytes) return false

        fileSystem.createDirectories(directory, mustCreate = false)
        // A fresh temp name per write: another FlagsCache for this scope (a second instance or
        // another process) sharing one temp path could truncate ours mid-write.
        val tmpFile = directory / "$scopeHash.${Random.nextInt().toUInt().toString(16)}.tmp"
        fileSystem.sink(tmpFile).buffer().use { it.write(encoded) }

        replaceSnapshotWith(tmpFile, encoded)
        pruneToNewest()
        return true
    }

    /**
     * Reclaims the Ktor HTTP cache 0.1.x left under `<cacheDirectoryPath>/flagsmith`, once. The
     * delete runs outside [ioMutex]: it can walk megabytes and must not block snapshot writes.
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
     * Moves the temp file over the snapshot, falling back to an in-place write of [encoded]. The
     * fallback deliberately writes from memory rather than copying the temp file, which may already
     * be gone: another `FlagsCache` for the same scope sweeps this directory's temp files on every
     * write. Copying would then fail with the target already truncated, leaving an empty file where
     * a valid snapshot used to be.
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
                // Never prune the file just written: with coarse mtime granularity the sort order
                // among files sharing a tick is arbitrary. It counts towards maxFiles, hence `maxFiles - 1`.
                .filterNot { it == file }
                .sortedByDescending { fileSystem.metadata(it).lastModifiedAtMillis ?: 0L }
                .drop(maxFiles - 1)
                .forEach { fileSystem.delete(it) }
        }
    }
}
