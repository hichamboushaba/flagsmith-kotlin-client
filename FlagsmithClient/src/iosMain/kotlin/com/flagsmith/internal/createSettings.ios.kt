package com.flagsmith.internal

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings

internal actual fun createSettings(name: String): Settings = NSUserDefaultsSettings.Factory().create(name)