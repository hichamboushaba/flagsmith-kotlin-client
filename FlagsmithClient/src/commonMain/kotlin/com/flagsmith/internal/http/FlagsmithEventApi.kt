package com.flagsmith.internal.http

import com.flagsmith.entities.FlagEvent
import kotlinx.coroutines.flow.Flow

internal interface FlagsmithEventApi {
    fun observeEvents(): Flow<FlagEvent>

    interface Factory {
        fun create(sseUrl: String, environmentKey: String): FlagsmithEventApi
    }
}
