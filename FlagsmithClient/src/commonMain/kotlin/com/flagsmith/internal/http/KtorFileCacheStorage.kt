package com.flagsmith.internal.http

import io.ktor.client.plugins.cache.storage.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.*
import okio.ByteString.Companion.encodeUtf8

private const val CACHE_DIRECTORY_NAME = "flagsmith"

/**
 * A file cache storage implementation for Ktor that uses Okio for reading and writing cache data.
 *
 * Based on the default JVM implementation of Ktor's "FileStorage": https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-core/jvm/src/io/ktor/client/plugins/cache/storage/FileCacheStorage.kt
 */
internal class KtorFileCacheStorage(
    baseDirectory: Path,
    private val maxSize: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger = KtorSimpleLogger("KtorFileCacheStorage")
) : CacheStorage, ClearableHttpCache {
    private val directory = baseDirectory / CACHE_DIRECTORY_NAME
    private val fileSystem = FileSystem.SYSTEM

    // Ktor default implementation uses a mutex per file, but we use a common mutex for all files
    // This is because we need to trim the cache to the specified size, and we can't do that with a mutex per file
    private val mutex = Mutex()

    override suspend fun store(url: Url, data: CachedResponseData): Unit = withContext(dispatcher) {
        ensureDirExists()
        val urlHex = key(url)
        val caches = readCache(urlHex).filterNot { it.varyKeys == data.varyKeys } + data
        writeCache(urlHex, caches)
    }

    override suspend fun findAll(url: Url): Set<CachedResponseData> {
        return readCache(key(url)).toSet()
    }

    override suspend fun find(url: Url, varyKeys: Map<String, String>): CachedResponseData? {
        val data = readCache(key(url))
        return data.find {
            varyKeys.all { (key, value) -> it.varyKeys[key] == value }
        }
    }

    private fun key(url: Url) = hex(url.toString().encodeUtf8().md5().toByteArray())

    private suspend fun writeCache(urlHex: String, caches: List<CachedResponseData>) = withContext(dispatcher) {
        mutex.withLock {
            try {
                fileSystem.sink(directory / urlHex).buffer().use { output ->
                    output.writeInt(caches.size)
                    for (cache in caches) {
                        output.writeCache(cache)
                    }
                }
            } catch (cause: Exception) {
                logger.trace("Exception during saving a cache to a file: ${cause.stackTraceToString()}")
            }

            trimToSize()
        }
    }

    private suspend fun readCache(urlHex: String): Set<CachedResponseData> = withContext(dispatcher) {
        mutex.withLock {
            val path = directory / urlHex
            if (!fileSystem.exists(path)) return@withContext emptySet()

            return@withLock try {
                fileSystem.source(path).buffer().use { input ->
                    val requestsCount = input.readInt()
                    val caches = mutableSetOf<CachedResponseData>()
                    for (i in 0 until requestsCount) {
                        caches.add(input.readCache())
                    }
                    caches
                }
            } catch (cause: Exception) {
                logger.trace("Exception during cache lookup in a file: ${cause.stackTraceToString()}")
                emptySet()
            }
        }
    }

    private fun BufferedSink.writeCache(cache: CachedResponseData) {
        writeUtf8(cache.url.toString() + "\n")
        writeInt(cache.statusCode.value)
        writeUtf8(cache.statusCode.description + "\n")
        writeUtf8(cache.version.toString() + "\n")
        val headers = cache.headers.flattenEntries()
        writeInt(headers.size)
        for ((key, value) in headers) {
            writeUtf8(key + "\n")
            writeUtf8(value + "\n")
        }
        writeLong(cache.requestTime.timestamp)
        writeLong(cache.responseTime.timestamp)
        writeLong(cache.expires.timestamp)
        writeInt(cache.varyKeys.size)
        for ((key, value) in cache.varyKeys) {
            writeUtf8(key + "\n")
            writeUtf8(value + "\n")
        }
        writeInt(cache.body.size)
        write(cache.body)
    }

    private fun BufferedSource.readCache(): CachedResponseData {
        val url = readUtf8Line()!!
        val status = HttpStatusCode(readInt(), readUtf8Line()!!)
        val version = HttpProtocolVersion.parse(readUtf8Line()!!)
        val headersCount = readInt()
        val headers = HeadersBuilder()
        for (j in 0 until headersCount) {
            val key = readUtf8Line()!!
            val value = readUtf8Line()!!
            headers.append(key, value)
        }
        val requestTime = GMTDate(readLong())
        val responseTime = GMTDate(readLong())
        val expirationTime = GMTDate(readLong())
        val varyKeysCount = readInt()
        val varyKeys = buildMap {
            for (j in 0 until varyKeysCount) {
                val key = readUtf8Line()!!
                val value = readUtf8Line()!!
                this.put(key, value)
            }
        }
        val bodyCount = readInt()
        val body = ByteArray(bodyCount)
        readFully(body)
        return CachedResponseData(
            url = Url(url),
            statusCode = status,
            requestTime = requestTime,
            responseTime = responseTime,
            version = version,
            expires = expirationTime,
            headers = headers.build(),
            varyKeys = varyKeys,
            body = body
        )
    }


    /**
     * Trims the cache to the specified [maxSize] by deleting the oldest files first.
     *
     * Uses Okio's [FileSystem] to get the file size.
     */
    private suspend fun trimToSize() = withContext(dispatcher) {
        val cacheFiles = fileSystem.list(directory).sortedByDescending { fileSystem.metadata(it).lastModifiedAtMillis }
        var totalSize = 0L
        for (file in cacheFiles) {
            totalSize += fileSystem.metadata(file).size!!
            if (totalSize > maxSize) {
                try {
                    fileSystem.delete(file)
                } catch (cause: Exception) {
                    logger.trace("Exception during cache trimming: ${cause.stackTraceToString()}")
                }
            }
        }
    }

    private fun ensureDirExists() {
        fileSystem.createDirectories(directory, mustCreate = false)
    }

    override suspend fun invalidate() {
        try {
            fileSystem.deleteRecursively(directory, false)
        } catch (e: IOException) {
            logger.error("Failed to clear cache", e)
        }
    }
}
