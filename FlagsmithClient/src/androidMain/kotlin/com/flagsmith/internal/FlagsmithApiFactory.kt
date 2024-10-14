package com.flagsmith.internal

import com.flagsmith.FlagsmithCacheConfig
import kotlinx.serialization.json.Json

internal interface FlagsmithApiFactory {
    fun create(
        baseUrl: String,
        environmentKey: String,
        cacheConfig: FlagsmithCacheConfig,
        requestTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long,
        timeTracker: FlagsmithEventTimeTracker,
        json: Json
    ): Pair<FlagsmithApi, HttpCache?>
}
