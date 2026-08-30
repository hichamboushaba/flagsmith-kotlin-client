package com.flagsmith

data class FlagsmithCacheConfig(
    val enableCache: Boolean = false,
    val cacheTTLSeconds: Long = 3600, // Default to 1 hour
    val acceptStaleCache: Boolean = false,
    val cacheDirectoryPath: String = ""
)
