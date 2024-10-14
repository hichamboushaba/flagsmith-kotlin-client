package com.flagsmith.entities

import kotlinx.serialization.Serializable

@Serializable
data class IdentityFlagsAndTraits(
    val flags: ArrayList<Flag>,
    val traits: ArrayList<Trait>
)