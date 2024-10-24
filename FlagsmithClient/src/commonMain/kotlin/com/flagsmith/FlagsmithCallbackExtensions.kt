package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.entities.Trait
import com.flagsmith.entities.TraitWithIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun Flagsmith.getFeatureFlags(
    identity: String? = null,
    traits: List<Trait>? = null,
    transient: Boolean = false,
    result: (Result<List<Flag>>) -> Unit
) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getFeatureFlags(identity, traits, transient))
    }
}

fun Flagsmith.getTrait(id: String, identity: String, result: (Result<Trait?>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getTrait(id, identity))
    }
}

fun Flagsmith.getValueForFeature(
    featureId: String,
    identity: String? = null,
    result: (Result<Any?>) -> Unit
) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getValueForFeature(featureId, identity))
    }
}

fun Flagsmith.getTraits(identity: String, result: (Result<List<Trait>>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getTraits(identity))
    }
}

fun Flagsmith.setTraits(traits: List<Trait>, identity: String, result: (Result<List<TraitWithIdentity>>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(setTraits(traits, identity))
    }
}


fun Flagsmith.setTrait(trait: Trait, identity: String, result: (Result<TraitWithIdentity>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(setTrait(trait, identity))
    }
}

fun Flagsmith.getIdentity(identity: String, transient: Boolean = false, result: (Result<IdentityFlagsAndTraits>) -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(getIdentity(identity, transient))
    }
}
