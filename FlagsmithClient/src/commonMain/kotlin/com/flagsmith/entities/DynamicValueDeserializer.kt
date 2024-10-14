package com.flagsmith.entities

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

internal object DynamicValueDeserializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Any {
        val jsonElement = decoder.decodeSerializableValue(JsonElement.serializer())

        return when {
            jsonElement is JsonPrimitive && jsonElement.isString -> jsonElement.content
            jsonElement is JsonPrimitive && jsonElement.booleanOrNull != null -> jsonElement.boolean
            // To stay compatible with how Gson deserialization worked, we return all numbers as doubles
            jsonElement is JsonPrimitive && jsonElement.doubleOrNull != null -> jsonElement.double
            else -> jsonElement.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: Any) {
        when (value) {
            is String -> encoder.encodeString(value)
            is Int -> encoder.encodeInt(value)
            is Double -> encoder.encodeDouble(value)
            is Boolean -> encoder.encodeBoolean(value)
            else -> encoder.encodeString(value.toString())
        }
    }
}
