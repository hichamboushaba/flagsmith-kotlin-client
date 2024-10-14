package com.flagsmith.entities

import kotlinx.serialization.Serializable

@Serializable
data class Identity(
    val identifier: String
)