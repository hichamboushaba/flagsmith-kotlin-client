package com.flagsmith

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

data class FlagsmithCacheConfig(
    val enableCache: Boolean = false,
    /** How long a fetched flags document stays valid before [Flagsmith.refresh] hits the network again. */
    val cacheTTL: Duration = 1.hours, // Default to 1 hour
    /**
     * Whether an expired on-disk snapshot may still prime the flags reads on a cold start.
     * Never affects what [Flagsmith.refresh] returns or the TTL gate.
     */
    val acceptStaleCache: Boolean = true,
    val cacheDirectoryPath: String = "",
    /** Maximum size in bytes of a single cached flags snapshot; larger snapshots are skipped. */
    val maxSnapshotSizeBytes: Long = 1L * 1024L * 1024L, // 1 MB
)
