package com.flagsmith

import com.flagsmith.entities.IdentityFlagsAndTraits
import com.flagsmith.entities.Trait
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// No helper here may combine `refresh` and a read behind one call: that would recreate the exact
// fetch/read conflation the split removes, inside the test suite. Callers wanting fetched-then-read
// behaviour call refreshSync() and then a sync getter (or flagUpdateFlow) as two separate steps.

suspend fun Flagsmith.refreshSync(force: Boolean = false): Result<Unit>
    = suspendCoroutine { cont -> this.refresh(force) { cont.resume(it) } }

suspend fun Flagsmith.getTraitsSync(): Result<List<Trait>>
    = suspendCoroutine { cont -> this.getTraits { cont.resume(it) } }

suspend fun Flagsmith.getTraitSync(id: String): Result<Trait?>
    = suspendCoroutine { cont -> this.getTrait(id) { cont.resume(it)} }

suspend fun Flagsmith.getIdentitySync(): Result<IdentityFlagsAndTraits>
    = suspendCoroutine { cont -> this.getIdentity { cont.resume(it) } }
