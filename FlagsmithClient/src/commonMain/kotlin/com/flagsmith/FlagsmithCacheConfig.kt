package com.flagsmith

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

data class FlagsmithCacheConfig(
    val enableCache: Boolean = false,
    /** How long a fetched flags document stays valid before the next call hits the network. */
    val cacheTTL: Duration = 1.hours, // Default to 1 hour
    val acceptStaleCache: Boolean = false,
    val cacheDirectoryPath: String = "",
    /** Maximum size in bytes of a single cached flags snapshot; larger snapshots are skipped. */
    val maxSnapshotSizeBytes: Long = 1L * 1024L * 1024L, // 1 MB
)
