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
) = withCallback(result) { getFeatureFlags(traits, transient, forceRefresh) }

fun Flagsmith.hasFeatureFlag(
    featureId: String,
    result: (Result<Boolean>) -> Unit
) = withCallback(result) { hasFeatureFlag(featureId) }

fun Flagsmith.getValueForFeature(
    featureId: String,
    result: (Result<Any?>) -> Unit
) = withCallback(result) { getValueForFeature(featureId) }

fun Flagsmith.getTraits(result: (Result<List<Trait>>) -> Unit) =
    withCallback(result) { getTraits() }

fun Flagsmith.getTrait(id: String, result: (Result<Trait?>) -> Unit) =
    withCallback(result) { getTrait(id) }

fun Flagsmith.setTraits(traits: List<Trait>, result: (Result<List<TraitWithIdentity>>) -> Unit) =
    withCallback(result) { setTraits(traits) }

fun Flagsmith.setTrait(trait: Trait, result: (Result<TraitWithIdentity>) -> Unit) =
    withCallback(result) { setTrait(trait) }

fun Flagsmith.getIdentity(transient: Boolean = false, result: (Result<IdentityFlagsAndTraits>) -> Unit) =
    withCallback(result) { getIdentity(transient) }

/**
 * Runs [block] and hands its outcome to [result] exactly once.
 *
 * Some calls reject their arguments by throwing rather than by returning a failed `Result` — using
 * an identity-scoped method on an environment-scoped instance, for example. Those exceptions are
 * delivered as `Result.failure` instead of being left to kill the launched coroutine, which would
 * strand callers waiting on the callback (and reach the platform's uncaught exception handler).
 */
private fun <T> Flagsmith.withCallback(
    result: (Result<T>) -> Unit,
    block: suspend Flagsmith.() -> Result<T>
) {
    CoroutineScope(Dispatchers.Unconfined).launch {
        result(runCatching { block() }.getOrElse { Result.failure(it) })
    }
}
