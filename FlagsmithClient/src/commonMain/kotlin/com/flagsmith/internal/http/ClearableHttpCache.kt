package com.flagsmith.internal.http

internal interface ClearableHttpCache {
    suspend fun invalidate()
}