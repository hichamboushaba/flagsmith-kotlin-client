package com.flagsmith.internal.http

internal interface HttpCache {
    fun invalidate()
}