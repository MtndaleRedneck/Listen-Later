package com.listenlater

/**
 * Every choice the share worker makes, as a plain testable function. The worker
 * turns a server outcome into one of these, and this decides what happens next:
 * done, retry (with how long), park under "Couldn't get it", or ask for sign-in.
 * Kept free of Android so each path is unit-tested (ShareLogicTest).
 */
object ShareLogic {

    enum class Outcome {
        SENT,                    // the server took the video
        DUPLICATE_READY,         // already there, cleaned up: re-kick the download
        DUPLICATE_FAILED,        // already there, download failed: retry it on the server
        DUPLICATE_WAITING,       // already there, still pending/downloading
        DUPLICATE_UNKNOWN,       // already there, state we don't recognize
        DUPLICATE_UNRESOLVED,    // server says it has it, but we couldn't locate it
        REFUSAL,                 // the server gave a reason (private, deleted, ...)
        SESSION_EXPIRED,         // sign-in missing or expired, however the server worded it
        UNEXPECTED_RESPONSE,     // a reply that isn't a proper answer at all
        NETWORK_FAILURE          // no signal, timeout, server down
    }

    enum class Action { DONE, RETRY, PARK, SIGN_IN }

    data class Decision(
        val action: Action,
        val retryDelayMs: Long = 0L,
        val toast: String? = null,
        val parkReason: String? = null,
        val markUnexpected: Boolean = false,
        val moveToBack: Boolean = false
    )

    /** One quick retry for a refusal, in case YouTube was briefly limited. */
    const val REFUSAL_MAX_TRIES = 2
    const val REFUSAL_RETRY_MS = 60_000L

    fun decide(outcome: Outcome, attempts: Int, title: String): Decision = when (outcome) {
        Outcome.SENT -> Decision(
            Action.DONE,
            toast = if (title.isNotEmpty()) "Sent: $title" else "Sent to Listen Later"
        )
        Outcome.DUPLICATE_READY -> Decision(Action.DONE, toast = "Getting it again")
        Outcome.DUPLICATE_FAILED -> Decision(Action.DONE, toast = "Trying that one again")
        Outcome.DUPLICATE_WAITING -> Decision(Action.DONE, toast = "Already downloading")
        Outcome.DUPLICATE_UNKNOWN -> Decision(Action.DONE, toast = "Already in your feed")
        Outcome.DUPLICATE_UNRESOLVED -> Decision(
            Action.PARK,
            parkReason = "The server says it already has this one, but I couldn't find it."
        )
        Outcome.REFUSAL ->
            if (attempts + 1 < REFUSAL_MAX_TRIES) {
                Decision(Action.RETRY, retryDelayMs = REFUSAL_RETRY_MS, moveToBack = true)
            } else {
                Decision(Action.PARK)
            }
        Outcome.SESSION_EXPIRED -> Decision(Action.SIGN_IN)
        Outcome.UNEXPECTED_RESPONSE -> Decision(
            Action.RETRY,
            retryDelayMs = Backoff.nextDelayMs(attempts + 1),
            markUnexpected = true,
            moveToBack = true
        )
        Outcome.NETWORK_FAILURE -> Decision(
            Action.RETRY,
            retryDelayMs = Backoff.nextDelayMs(attempts + 1),
            toast = if (attempts == 0) "Couldn't reach your server. It'll keep trying." else null,
            moveToBack = true
        )
    }
}
