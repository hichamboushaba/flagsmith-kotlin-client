package com.flagsmith

import kotlinx.serialization.json.Json

internal val defaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
