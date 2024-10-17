package com.flagsmith.internal.http

import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.internal.FlagsmithEventTimeTracker
import kotlinx.serialization.json.Json

internal interface FlagsmithApi {
    suspend fun getIdentityFlagsAndTraits(
        identity: String,
        transient: Boolean = false
    ): Result<IdentityFlagsAndTraits>

    suspend fun getFlags() : Result<List<Flag>>

    suspend fun postTraits(identity: IdentityAndTraits) : Result<IdentityFlagsAndTraits>

    suspend fun postAnalytics(eventMap: Map<String, Int?>) : Result<Unit>

    interface Factory {
        fun create(
            baseUrl: String,
            environmentKey: String,
            cacheConfig: FlagsmithCacheConfig,
            requestTimeoutSeconds: Long,
            readTimeoutSeconds: Long,
            writeTimeoutSeconds: Long,
            timeTracker: FlagsmithEventTimeTracker,
            json: Json
        ): Pair<FlagsmithApi, ClearableHttpCache?>
    }
}
