package com.flagsmith.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Flag(
    val feature: Feature,
    @SerialName(value = "feature_state_value")
    @Serializable(with = DynamicValueDeserializer::class)
    val featureStateValue: Any?,
    val enabled: Boolean
)

@Serializable
data class Feature(
    val id: Long,
    val name: String,
    @SerialName(value = "created_date") val createdDate: String? = null,
    val description: String? = null,
    @SerialName(value = "initial_value") val initialValue: String? = null,
    @SerialName(value = "default_enabled") val defaultEnabled: Boolean? = null,
    val type: String
)
