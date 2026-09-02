package com.flagsmith.internal.http

import com.flagsmith.entities.FlagEvent
import kotlinx.coroutines.flow.Flow

internal interface FlagsmithEventApi {
    fun observeEvents(): Flow<FlagEvent>

    /** Releases the underlying HTTP client. The instance is unusable afterwards. */
    fun close()

    interface Factory {
        fun create(
            sseUrl: String,
            environmentKey: String,
            userAgentOverride: String?
        ): FlagsmithEventApi
    }
}
