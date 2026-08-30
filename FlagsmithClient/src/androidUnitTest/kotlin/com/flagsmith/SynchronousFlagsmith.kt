package com.flagsmith

import com.flagsmith.entities.Flag
import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.entities.Trait
import com.flagsmith.entities.TraitWithIdentity
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

suspend fun Flagsmith.hasFeatureFlagSync(forFeatureId: String): Result<Boolean>
    = suspendCoroutine { cont -> this.hasFeatureFlag(forFeatureId) { cont.resume(it) } }

suspend fun Flagsmith.getFeatureFlagsSync(traits: List<Trait>? = null, transient: Boolean = false) : Result<List<Flag>>
    = suspendCoroutine { cont -> this.getFeatureFlags(traits = traits, transient = transient) { cont.resume(it) } }

suspend fun Flagsmith.getValueForFeatureSync(forFeatureId: String): Result<Any?>
    = suspendCoroutine { cont -> this.getValueForFeature(forFeatureId) { cont.resume(it) } }

suspend fun Flagsmith.getTraitsSync(): Result<List<Trait>>
    = suspendCoroutine { cont -> this.getTraits { cont.resume(it) } }

suspend fun Flagsmith.getTraitSync(id: String): Result<Trait?>
    = suspendCoroutine { cont -> this.getTrait(id) { cont.resume(it)} }

suspend fun Flagsmith.setTraitSync(trait: Trait) : Result<TraitWithIdentity>
    = suspendCoroutine { cont -> this.setTrait(trait) { cont.resume(it) } }

suspend fun Flagsmith.setTraitsSync(traits: List<Trait>) : Result<List<TraitWithIdentity>>
        = suspendCoroutine { cont -> this.setTraits(traits) { cont.resume(it) } }

suspend fun Flagsmith.getIdentitySync(transient: Boolean = false): Result<IdentityFlagsAndTraits>
    = suspendCoroutine { cont -> this.getIdentity(transient) { cont.resume(it) } }
