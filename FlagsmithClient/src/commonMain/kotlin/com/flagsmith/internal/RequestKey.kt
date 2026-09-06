package com.flagsmith.internal

import com.flagsmith.defaultJson
import com.flagsmith.entities.DynamicValueDeserializer
import com.flagsmith.entities.Trait
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import okio.ByteString.Companion.encodeUtf8

/**
 * The canonical identity of the request that produced (or would produce) a flags document. [encode]
 * renders the one stable string persisted with a snapshot; [decode] parses it back. Comparisons go
 * through the parsed type ([satisfies]), never the encoded string:
 * `"post:transient:x".startsWith("post:")` is `true`, which would let a transient document satisfy
 * a non-transient request.
 */
internal sealed class RequestKey {

    abstract fun encode(): String

    /** The document an environment-scoped instance fetches; no traits involved. */
    internal object Environment : RequestKey() {
        override fun encode() = ENVIRONMENT_ENCODED
    }

    /** An identity-scoped fetch, keyed on the canonical digest of the traits it was sent with. */
    internal data class Identity(val transient: Boolean, val digest: String) : RequestKey() {
        override fun encode() = if (transient) "$POST_TRANSIENT_PREFIX$digest" else "$POST_PREFIX$digest"
    }

    companion object {
        private const val ENVIRONMENT_ENCODED = "env"
        private const val POST_PREFIX = "post:"
        private const val POST_TRANSIENT_PREFIX = "post:transient:"

        /** The digest of an empty trait list. */
        val EMPTY_DIGEST: String = emptyList<Trait>().canonicalTraitDigest()!!

        /**
         * The key of the request a fetch would issue for [traits], or `null` when it has no reusable
         * identity: duplicate trait keys (collapsing them would let two different POST bodies share
         * a digest) or a value the wire format cannot encode. Such a request is neither matched
         * against the cache nor cached. The `require` is a last line of defense: the trait setters
         * already throw in environment mode.
         */
        fun forRequest(identity: String?, traits: Collection<Trait>, transient: Boolean): RequestKey? = when {
            identity == null -> {
                require(traits.isEmpty()) { "Traits require an identity" }
                Environment
            }
            else -> traits.toList().canonicalTraitDigest()?.let { digest -> Identity(transient, digest) }
        }

        /** Parses an [encode]d string back, or `null` for anything else. Never throws. */
        // A foreign or truncated key must not parse: any identity key dominates a trait-less
        // request, so `post:garbage` would suppress the first refresh of a session.
        private fun isDigest(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

        fun decode(s: String): RequestKey? = when {
            s == ENVIRONMENT_ENCODED -> Environment
            s.startsWith(POST_TRANSIENT_PREFIX) ->
                s.removePrefix(POST_TRANSIENT_PREFIX).takeIf(::isDigest)?.let { Identity(transient = true, digest = it) }
            s.startsWith(POST_PREFIX) ->
                s.removePrefix(POST_PREFIX).takeIf(::isDigest)?.let { Identity(transient = false, digest = it) }
            else -> null
        }
    }
}

/**
 * Whether a document keyed `this` may answer a request keyed [request]: an exact match, or dominance -
 * a trait-less request is satisfied by any document for the same identity and transience, so a
 * cold-start `refresh()` before `setTraits` reuses the last document instead of fetching (and
 * caching) a trait-less one. Transience must match on both sides even under dominance.
 */
internal fun RequestKey.satisfies(request: RequestKey, allowDominance: Boolean): Boolean = when {
    this is RequestKey.Environment && request is RequestKey.Environment -> true
    this is RequestKey.Identity && request is RequestKey.Identity ->
        transient == request.transient &&
            (digest == request.digest || (allowDominance && request.digest == RequestKey.EMPTY_DIGEST))
    else -> false
}

/**
 * A sha256 over the traits as a map keyed on [Trait.key] (ordering irrelevant), with
 * [Trait.identifier] dropped (`null` on app-built traits, populated on server-sourced ones),
 * per-trait [Trait.transient] kept, and values rendered by the wire serializer. `null` for
 * duplicate keys: two different POST bodies must never share a digest.
 */
internal fun List<Trait>.canonicalTraitDigest(): String? {
    val valuesByKey = LinkedHashMap<String, JsonElement>()
    for (trait in this) {
        if (valuesByKey.containsKey(trait.key)) return null
        val value = trait.traitValue.canonicalJson() ?: return null
        valuesByKey[trait.key] = buildJsonArray {
            add(JsonPrimitive(trait.transient))
            add(value)
        }
    }

    val canonical = buildJsonArray {
        for ((key, value) in valuesByKey.entries.sortedBy { it.key }) {
            add(buildJsonArray {
                add(JsonPrimitive(key))
                add(value)
            })
        }
    }
    return defaultJson.encodeToString(JsonElement.serializer(), canonical)
        .encodeUtf8().sha256().hex()
}

/**
 * Renders a trait value the way the wire format sees it, so a freshly built value and the same
 * value after a server round trip compare equal. `null` when it cannot be encoded at all
 * (`NaN`, `±Infinity`): this runs before Ktor's `runCatching`, so it must not throw.
 */
private fun Any.canonicalJson(): JsonElement? = try {
    // A real JSON round trip, not encodeToJsonElement: the tree encoder cannot encode a
    // root-level primitive through this serializer.
    val element = defaultJson.parseToJsonElement(defaultJson.encodeToString(DynamicValueDeserializer, this))
    if (element is JsonPrimitive && !element.isString) {
        // The serializer decodes every wire number to Double but encodes an app-built Int as an
        // Int, so all numbers compare through one Double rendering rather than hand-rolled casts;
        // booleans fail doubleOrNull and keep their own form.
        element.doubleOrNull?.let(::JsonPrimitive) ?: element
    } else {
        element
    }
} catch (_: kotlinx.serialization.SerializationException) {
    null
}
