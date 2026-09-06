@file:OptIn(ExperimentalAtomicApi::class)

package com.flagsmith.internal

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Atomically replaces the value with [transform] of the current one. The stdlib has this exact
 * extension (kotlin.concurrent.atomics.update) but 2.3 does not resolve it outside the stdlib
 * yet; revisit when it does.
 *
 * [transform] may be invoked more than once when a concurrent update wins the race, so it must
 * be pure - both current callers apply pure map math.
 */
internal inline fun <T> AtomicReference<T>.update(transform: (T) -> T): Unit {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return
    }
}
