package com.flagsmith.internal.http

internal interface ClearableHttpCache {
    fun invalidate()
}