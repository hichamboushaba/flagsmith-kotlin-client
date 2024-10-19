package com.flagsmith.internal

import android.content.Context
import androidx.startup.Initializer
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

private lateinit var appContext: Context

internal actual fun createSettings(name: String): Settings {
    if (!::appContext.isInitialized) {
        throw IllegalStateException("App context not initialized, is the ContextInitializer disabled?")
    }
    return SharedPreferencesSettings.Factory(appContext).create(name)
}

internal class ContextInitializer : Initializer<Context> {
    override fun create(p0: Context): Context {
        return p0.applicationContext.also { appContext = it }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
