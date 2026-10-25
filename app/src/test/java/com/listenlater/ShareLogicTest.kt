package com.listenlater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLogicTest {

    @Test
    fun sentIsDoneWithTitleToast() {
        val d = ShareLogic.decide(ShareLogic.Outcome.SENT, attempts = 0, title = "Great video")
        assertEquals(ShareLogic.Action.DONE, d.action)
        assertEquals("Sent: Great video", d.toast)
    }

    @Test
    fun sentWithoutTitleFallsBackToGenericToast() {
        val d = ShareLogic.decide(ShareLogic.Outcome.SENT, attempts = 0, title = "")
        assertEquals(ShareLogic.Action.DONE, d.action)
        assertEquals("Sent to Listen Later", d.toast)
    }

    @Test
    fun duplicateReadyIsDoneWithRekickToast() {
        val d = ShareLogic.decide(ShareLogic.Outcome.DUPLICATE_READY, 0, "t")
        assertEquals(ShareLogic.Action.DONE, d.action)
        assertEquals("Getting it again", d.toast)
    }

    @Test
    fun duplicateFailedIsDoneWithRetryToast() {
        val d = ShareLogic.decide(ShareLogic.Outcome.DUPLICATE_FAILED, 0, "t")
        assertEquals(ShareLogic.Action.DONE, d.action)
        assertEquals("Trying that one again", d.toast)
    }

    @Test
    fun duplicateWaitingIsDone() {
        val d = ShareLogic.decide(ShareLogic.Outcome.DUPLICATE_WAITING, 0, "t")
        assertEquals(ShareLogic.Action.DONE, d.action)
        assertEquals("Already downloading", d.toast)
    }

    @Test
    fun duplicateUnknownIsDone() {
        val d = ShareLogic.decide(ShareLogic.Outcome.DUPLICATE_UNKNOWN, 0, "t")
        assertEquals(ShareLogic.Action.DONE, d.action)
        assertEquals("Already in your feed", d.toast)
    }

    @Test
    fun duplicateUnresolvedParksWithExplanation() {
        val d = ShareLogic.decide(ShareLogic.Outcome.DUPLICATE_UNRESOLVED, 0, "t")
        assertEquals(ShareLogic.Action.PARK, d.action)
        assertEquals(
            "The server says it already has this one, but I couldn't find it.",
            d.parkReason
        )
    }

    @Test
    fun refusalRetriesOnceAfterSixtySeconds() {
        val d = ShareLogic.decide(ShareLogic.Outcome.REFUSAL, attempts = 0, title = "t")
        assertEquals(ShareLogic.Action.RETRY, d.action)
        assertEquals(ShareLogic.REFUSAL_RETRY_MS, d.retryDelayMs)
        assertTrue(d.moveToBack)
        assertNull(d.toast)
    }

    @Test
    fun refusalParksAfterSecondTry() {
        val d = ShareLogic.decide(ShareLogic.Outcome.REFUSAL, attempts = 1, title = "t")
        assertEquals(ShareLogic.Action.PARK, d.action)
        assertNull(d.retryDelayMs.takeIf { it > 0 })
    }

    @Test
    fun sessionExpiredAsksForSignIn() {
        val d = ShareLogic.decide(ShareLogic.Outcome.SESSION_EXPIRED, 0, "t")
        assertEquals(ShareLogic.Action.SIGN_IN, d.action)
    }

    @Test
    fun unexpectedResponseRetriesWithBackoffAndMarksIt() {
        val d0 = ShareLogic.decide(ShareLogic.Outcome.UNEXPECTED_RESPONSE, attempts = 0, title = "t")
        assertEquals(ShareLogic.Action.RETRY, d0.action)
        assertTrue(d0.markUnexpected)
        assertTrue(d0.moveToBack)
        val d1 = ShareLogic.decide(ShareLogic.Outcome.UNEXPECTED_RESPONSE, attempts = 1, title = "t")
        assertTrue(d1.retryDelayMs > d0.retryDelayMs)
    }

    @Test
    fun networkFailureRetriesAndToastsOnce() {
        val first = ShareLogic.decide(ShareLogic.Outcome.NETWORK_FAILURE, attempts = 0, title = "t")
        assertEquals(ShareLogic.Action.RETRY, first.action)
        assertEquals("Couldn't reach your server. It'll keep trying.", first.toast)
        assertTrue(first.moveToBack)
        val again = ShareLogic.decide(ShareLogic.Outcome.NETWORK_FAILURE, attempts = 3, title = "t")
        assertEquals(ShareLogic.Action.RETRY, again.action)
        assertNull(again.toast)
        assertFalse(again.markUnexpected)
    }
}
