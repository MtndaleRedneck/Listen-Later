package com.listenlater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification id ranges must be disjoint by construction, not by luck. */
class NoticeIdsTest {

    @Test
    fun rangesAreDisjointByMath() {
        // Skipped runs 1000..66535; redirect runs 131072..196607; sign-in is a single id.
        assertTrue(NoticeIds.SKIPPED_BASE + 0xFFFF < NoticeIds.REDIRECT_BASE)
        assertTrue(NoticeIds.SIGNIN < NoticeIds.SKIPPED_BASE)
    }

    @Test
    fun sampledUrlsLandInTheirOwnRanges() {
        val urls = listOf(
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=abc123XYZ_-",
            "https://m.youtube.com/shorts/zz99",
            "", "x", "https://example.com/" + "a".repeat(500))
        for (u in urls) {
            val sk = NoticeIds.skipped(u)
            val rd = NoticeIds.redirect(u)
            assertTrue(sk in NoticeIds.SKIPPED_BASE..NoticeIds.SKIPPED_BASE + 0xFFFF)
            assertTrue(rd in NoticeIds.REDIRECT_BASE..NoticeIds.REDIRECT_BASE + 0xFFFF)
            assertTrue(sk != rd && sk != NoticeIds.SIGNIN && rd != NoticeIds.SIGNIN)
        }
        // Same url, same id in each category (stable, so cancel targets the posted notice).
        assertEquals(NoticeIds.skipped("https://youtu.be/dQw4w9WgXcQ"), NoticeIds.skipped("https://youtu.be/dQw4w9WgXcQ"))
    }
}
