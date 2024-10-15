package com.flagsmith.internal

import com.flagsmith.entities.FlagEvent
import kotlinx.coroutines.flow.Flow

internal interface FlagsmithEventApi {
    fun observeEvents(): Flow<FlagEvent>
}

internal interface FlagsmithEventApiFactory {
    fun create(sseUrl: String, environmentKey: String): FlagsmithEventApi
}