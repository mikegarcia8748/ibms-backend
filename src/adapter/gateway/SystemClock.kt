package com.puregoldbe.ibms.adapter.gateway

import com.puregoldbe.ibms.domain.port.Clock
import kotlinx.datetime.Instant

class SystemClock : Clock {
    override fun now(): Instant = kotlinx.datetime.Clock.System.now()
}
