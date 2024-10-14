package com.flagsmith.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IdentityAndTraits(
    @SerialName(value = "identifier") val identifier: String,
    @SerialName(value = "traits") val traits: List<Trait>,
    @SerialName(value = "transient") val transient: Boolean? = null
)