package com.flagsmith.entities

import kotlinx.serialization.Serializable

@Serializable
data class FeatureStatePutBody (
    val enabled: Boolean,
    @Serializable(with = DynamicValueDeserializer::class)
    val feature_state_value: Any?
)
