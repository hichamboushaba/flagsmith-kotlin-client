package com.flagsmith.internal

import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.IdentityFlagsAndTraits

internal interface FlagsmithApi {
    suspend fun getIdentityFlagsAndTraits(
        identity: String,
        transient: Boolean = false
    ): Result<IdentityFlagsAndTraits>

    suspend fun getFlags() : Result<List<Flag>>

    suspend fun postTraits(identity: IdentityAndTraits) : Result<IdentityFlagsAndTraits>

    suspend fun postAnalytics(eventMap: Map<String, Int?>) : Result<Unit>
}