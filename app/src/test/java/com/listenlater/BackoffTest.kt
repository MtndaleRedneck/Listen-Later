package com.listenlater

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun sequenceIsOneFiveThirtyThenHourly() {
        assertEquals(60_000L, Backoff.nextDelayMs(1))
        assertEquals(5 * 60_000L, Backoff.nextDelayMs(2))
        assertEquals(30 * 60_000L, Backoff.nextDelayMs(3))
        assertEquals(60 * 60_000L, Backoff.nextDelayMs(4))
        assertEquals(60 * 60_000L, Backoff.nextDelayMs(25))
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        assertEquals(false, Backoff.shouldGiveUp(1))
        assertEquals(false, Backoff.shouldGiveUp(Backoff.MAX_ATTEMPTS - 1))
        assertEquals(true, Backoff.shouldGiveUp(Backoff.MAX_ATTEMPTS))
        assertEquals(true, Backoff.shouldGiveUp(Backoff.MAX_ATTEMPTS + 3))
    }
}
