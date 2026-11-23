package com.listenlater

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun sequenceCoversFiveMinutesThenBacksOff() {
        assertEquals(60_000L, Backoff.nextDelayMs(1))
        assertEquals(2 * 60_000L, Backoff.nextDelayMs(2))
        assertEquals(2 * 60_000L, Backoff.nextDelayMs(3))
        assertEquals(30 * 60_000L, Backoff.nextDelayMs(4))
        assertEquals(60 * 60_000L, Backoff.nextDelayMs(5))
        assertEquals(60 * 60_000L, Backoff.nextDelayMs(25))
    }
}
