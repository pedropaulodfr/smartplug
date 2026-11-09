package com.smartplug.app.core

import com.smartplug.app.domain.gateway.Clock

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
