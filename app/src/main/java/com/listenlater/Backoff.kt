package com.listenlater

/** Retry rhythm for failed shares: 1m, 2m, 2m (a few tries over about 5 minutes,
 *  so a server restart is covered), then 30m, then hourly. */
object Backoff {
    fun nextDelayMs(failures: Int): Long = when {
        failures <= 1 -> 60_000L
        failures == 2 -> 2 * 60_000L
        failures == 3 -> 2 * 60_000L
        failures == 4 -> 30 * 60_000L
        else -> 60 * 60_000L
    }
}
