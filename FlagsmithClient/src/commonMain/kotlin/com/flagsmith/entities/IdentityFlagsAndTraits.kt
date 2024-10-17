package com.flagsmith.entities

import kotlinx.serialization.Serializable

@Serializable
data class IdentityFlagsAndTraits(
    val flags: List<Flag>,
    val traits: List<Trait>
)