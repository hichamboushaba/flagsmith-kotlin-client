package com.flagsmith.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class FlagEvent (
    @SerialName(value = "updated_at") val updatedAt: Double
)
