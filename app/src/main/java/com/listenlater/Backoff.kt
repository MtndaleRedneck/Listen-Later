package com.listenlater

/** Retry rhythm for failed shares: 1m, 5m, 30m, then hourly. */
object Backoff {
    /** After this many failures on one share, drop it and move to the next. */
    const val MAX_ATTEMPTS = 5

    fun shouldGiveUp(failures: Int): Boolean = failures >= MAX_ATTEMPTS

    fun nextDelayMs(failures: Int): Long = when {
        failures <= 1 -> 60_000L
        failures == 2 -> 5 * 60_000L
        failures == 3 -> 30 * 60_000L
        else -> 60 * 60_000L
    }
}
