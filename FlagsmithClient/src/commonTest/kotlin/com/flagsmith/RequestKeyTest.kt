package com.flagsmith

import com.flagsmith.entities.Trait
import com.flagsmith.internal.RequestKey
import com.flagsmith.internal.satisfies
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the canonicalisation behind the trait digest, and the [RequestKey] encode/decode round
 * trip. It fails silently if wrong — the app loses its change-detection and the SDK just re-POSTs
 * on every call — so each normalisation rule is pinned here.
 */
class RequestKeyTest {

    private fun keyFor(
        identity: String? = "person",
        traits: Collection<Trait> = emptyList(),
        transient: Boolean = false
    ): RequestKey? = RequestKey.forRequest(identity, traits, transient)

    @Test
    fun reorderedTraitsHashToTheSameKey() = runTest {
        // Apps commonly build the list from a HashMap whose iteration order is not stable across
        // processes, so ordering must be irrelevant.
        assertEquals(
            keyFor(traits = listOf(Trait("a", 1.0), Trait("b", "x"))),
            keyFor(traits = listOf(Trait("b", "x"), Trait("a", 1.0)))
        )
    }

    @Test
    fun intAndDoubleValuesHashToTheSameKey() = runTest {
        // DynamicValueDeserializer decodes every wire number to Double but encodes an app-built
        // Int as an Int: the canonicalisation goes through the serializer so a freshly built Int
        // and a round-tripped Double agree.
        assertEquals(
            keyFor(traits = listOf(Trait("age", 30))),
            keyFor(traits = listOf(Trait("age", 30.0)))
        )
    }

    @Test
    fun identifierIsExcludedFromTheDigest() = runTest {
        // App-built traits carry no identifier; server-sourced ones do. Same request either way.
        assertEquals(
            keyFor(traits = listOf(Trait(key = "a", value = "x"))),
            keyFor(traits = listOf(Trait(identifier = "person", key = "a", traitValue = "x")))
        )
    }

    @Test
    fun perTransientTraitIsPartOfTheIdentity() = runTest {
        // A transient trait is only skipped for the server-side write, not for evaluation, so it
        // must hash differently from the same trait persisted.
        assertNotEquals(
            keyFor(traits = listOf(Trait("a", "v"))),
            keyFor(traits = listOf(Trait("a", "v", transient = true)))
        )
        // ...but ordering stays irrelevant even when transient flags differ per trait.
        assertEquals(
            keyFor(traits = listOf(Trait("a", "1", transient = true), Trait("b", "2"))),
            keyFor(traits = listOf(Trait("b", "2"), Trait("a", "1", transient = true)))
        )
    }

    @Test
    fun duplicateKeysLeaveNoReusableIdentity() = runTest {
        // Collapsing duplicates "last wins" would let two different POST bodies share a digest.
        assertNull(keyFor(traits = listOf(Trait("a", "1"), Trait("a", "2"))))
    }

    @Test
    fun anUnencodableTraitValueLeavesNoReusableIdentity() = runTest {
        // defaultJson rejects special floating point values, and forRequest runs outside the
        // Ktor runCatching that used to turn encode failures into Result.failure: it must yield
        // no identity instead of throwing.
        assertNull(keyFor(traits = listOf(Trait("a", Double.NaN))))
        assertNull(keyFor(traits = listOf(Trait("a", Double.POSITIVE_INFINITY))))
        assertNull(keyFor(traits = listOf(Trait("a", Double.NEGATIVE_INFINITY))))
    }

    @Test
    fun anEnvironmentRequestWithTraitsIsAProgrammingError() = runTest {
        // Unreachable through the public surface - setTraits/setTrait/removeTrait already throw
        // in environment mode - but forRequest still asserts it, as the last line of defense.
        assertFailsWith<IllegalArgumentException> {
            RequestKey.forRequest(identity = null, traits = listOf(Trait("a", "1")), transient = false)
        }
    }

    @Test
    fun numericStringsAndNumbersStayDistinct() = runTest {
        // The serializer decides what is a number: a string "30" must not canonicalise into one.
        assertNotEquals(
            keyFor(traits = listOf(Trait("a", "30"))),
            keyFor(traits = listOf(Trait("a", 30)))
        )
    }

    @Test
    fun theEncodedKeyIsAStableCanonicalString() = runTest {
        assertEquals("env", RequestKey.forRequest(identity = null, traits = emptyList(), transient = false)?.encode())

        val noTraitsKey = RequestKey.forRequest(identity = "person", traits = emptyList(), transient = false)
        assertIs<RequestKey.Identity>(noTraitsKey)
        assertTrue(!noTraitsKey.transient)

        val noTraitsTransientKey = RequestKey.forRequest(identity = "person", traits = emptyList(), transient = true)
        assertIs<RequestKey.Identity>(noTraitsTransientKey)
        assertTrue(noTraitsTransientKey.transient)

        val key = RequestKey.forRequest(identity = "person", traits = listOf(Trait("a", "v")), transient = false)
        assertIs<RequestKey.Identity>(key)
        assertTrue(!key.transient)
        assertTrue(key.encode().startsWith("post:") && !key.encode().startsWith("post:transient:"))

        val transientKey = RequestKey.forRequest(
            identity = "person", traits = listOf(Trait("a", "v")), transient = true
        )
        assertIs<RequestKey.Identity>(transientKey)
        assertTrue(transientKey.transient)
        assertTrue(transientKey.encode().startsWith("post:transient:"))

        // Both carry the same digest hex, differing only in the transient marker.
        assertEquals(key.digest, transientKey.digest)
    }

    @Test
    fun nonStringTraitValuesProduceAKeyAtAll() = runTest {
        // Every other numeric assertion here compares two keys for equality, which passes
        // vacuously when both are null. Canonicalisation returning null for all numbers — the
        // exact failure the SerializationException catch guards against — would leave those
        // green while silently making every numeric-trait request uncacheable, so non-nullity
        // has to be pinned on its own.
        assertNotNull(keyFor(traits = listOf(Trait("age", 30))))
        assertNotNull(keyFor(traits = listOf(Trait("ratio", 1.5))))
        assertNotNull(keyFor(traits = listOf(Trait("beta", true))))
    }

    @Test
    fun encodeDecodeRoundTrips() = runTest {
        // A real-shaped digest: decode rejects anything that is not 64 lowercase hex chars.
        val digest = "a".repeat(64)
        val keys = listOf(
            RequestKey.Environment,
            RequestKey.Identity(transient = false, digest = digest),
            RequestKey.Identity(transient = true, digest = digest),
        )
        for (key in keys) {
            assertEquals(key, RequestKey.decode(key.encode()))
        }
    }

    @Test
    fun decodeRejectsUnrecognisedStrings() = runTest {
        // killed by: decode accepts get
        // An unrecognised key must decode to null rather than be mistaken for a POST-derived one,
        // or a foreign snapshot could satisfy a gated call it was never evaluated for.
        assertNull(RequestKey.decode("get"))
        assertNull(RequestKey.decode("get:transient"))
        assertNull(RequestKey.decode(""))
        assertNull(RequestKey.decode("post"))
        assertNull(RequestKey.decode("environment"))
        // A prefix alone, or a suffix that is not the digest encode emits, must not parse: any
        // identity key can dominate a trait-less request, so a foreign snapshot would otherwise
        // suppress the first refresh of a session.
        assertNull(RequestKey.decode("post:"))
        assertNull(RequestKey.decode("post:garbage"))
        assertNull(RequestKey.decode("post:transient:garbage"))
        assertNull(RequestKey.decode("post:" + "A".repeat(64)))
    }

    @Test
    fun `satisfies - a traited document dominates a trait-less request`() = runTest {
        // killed by: dominance inverted
        val traited = RequestKey.Identity(transient = false, digest = "abc123")
        val traitLessRequest = RequestKey.Identity(transient = false, digest = RequestKey.EMPTY_DIGEST)
        assertTrue(traited.satisfies(traitLessRequest, allowDominance = true))
        // ...and only when the document came off disk; a fetched one must not dominate.
        assertFalse(traited.satisfies(traitLessRequest, allowDominance = false))
    }

    @Test
    fun `satisfies - dominance is one-directional - a trait-less document does not satisfy a traited request`() = runTest {
        // killed by: dominance inverted
        val traitLessDocument = RequestKey.Identity(transient = false, digest = RequestKey.EMPTY_DIGEST)
        val traitedRequest = RequestKey.Identity(transient = false, digest = "abc123")
        assertFalse(traitLessDocument.satisfies(traitedRequest, allowDominance = true))
    }

    @Test
    fun `satisfies - dominance still requires the transient marker to match`() = runTest {
        // killed by: marker ignored
        val transientDocument = RequestKey.Identity(transient = true, digest = "abc123")
        val nonTransientTraitLessRequest = RequestKey.Identity(transient = false, digest = RequestKey.EMPTY_DIGEST)
        assertFalse(transientDocument.satisfies(nonTransientTraitLessRequest, allowDominance = true))

        val nonTransientDocument = RequestKey.Identity(transient = false, digest = "abc123")
        val transientTraitLessRequest = RequestKey.Identity(transient = true, digest = RequestKey.EMPTY_DIGEST)
        assertFalse(nonTransientDocument.satisfies(transientTraitLessRequest, allowDominance = true))
    }

    @Test
    fun `satisfies - Environment only ever satisfies Environment`() = runTest {
        assertTrue(RequestKey.Environment.satisfies(RequestKey.Environment, allowDominance = true))
        val identityKey = RequestKey.Identity(transient = false, digest = RequestKey.EMPTY_DIGEST)
        assertFalse(RequestKey.Environment.satisfies(identityKey, allowDominance = true))
        assertFalse(identityKey.satisfies(RequestKey.Environment, allowDominance = true))
    }
}
