package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.entities.Trait
import com.flagsmith.entities.TraitWithIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun Flagsmith.getFeatureFlags(
    traits: List<Trait>? = null,
    transient: Boolean = false,
    forceRefresh: Boolean = false,
    result: (Result<List<Flag>>) -> Unit
) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getFeatureFlags(traits, transient, forceRefresh))
    }
}

fun Flagsmith.hasFeatureFlag(
    featureId: String,
    result: (Result<Boolean>) -> Unit
) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(hasFeatureFlag(featureId))
    }
}

fun Flagsmith.getValueForFeature(
    featureId: String,
    result: (Result<Any?>) -> Unit
) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getValueForFeature(featureId))
    }
}

fun Flagsmith.getTraits(result: (Result<List<Trait>>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getTraits())
    }
}

fun Flagsmith.getTrait(id: String, result: (Result<Trait?>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getTrait(id))
    }
}

fun Flagsmith.setTraits(traits: List<Trait>, result: (Result<List<TraitWithIdentity>>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(setTraits(traits))
    }
}

fun Flagsmith.setTrait(trait: Trait, result: (Result<TraitWithIdentity>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(setTrait(trait))
    }
}

fun Flagsmith.getIdentity(transient: Boolean = false, result: (Result<IdentityFlagsAndTraits>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getIdentity(transient))
    }
}
