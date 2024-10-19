package com.flagsmith.internal

import android.content.Context
import androidx.startup.Initializer

internal var appContext: Context? = null

internal class ContextInitializer : Initializer<Context> {
    override fun create(p0: Context): Context {
        return p0.applicationContext.also { appContext = it }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}