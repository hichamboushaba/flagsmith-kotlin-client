package com.flagsmith.internal

import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

internal actual fun createSettings(name: String): Settings {
    if (appContext == null) {
        throw IllegalStateException("App context not initialized, is the ContextInitializer disabled?")
    }
    return SharedPreferencesSettings.Factory(appContext!!).create(name)
}


