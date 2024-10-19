package com.flagsmith.internal

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings

internal actual fun createSettings(name: String): Settings = PreferencesSettings.Factory().create(name)