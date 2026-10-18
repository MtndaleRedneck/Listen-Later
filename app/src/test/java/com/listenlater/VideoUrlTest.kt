package com.listenlater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoUrlTest {

    @Test
    fun plainWatchUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun shareTextWithTrackingParams() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("Check this out https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc123&t=42 so good")
        )
    }

    @Test
    fun youtuBeShortLink() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("https://youtu.be/dQw4w9WgXcQ?si=xyz")
        )
    }

    @Test
    fun shortsUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
    }

    @Test
    fun musicYoutube() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=zzz")
        )
    }

    @Test
    fun bareVideoId() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("dQw4w9WgXcQ")
        )
    }

    @Test
    fun videoInsidePlaylistKeepsVideo() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc123&index=4")
        )
    }

    @Test
    fun playlistOnlyRejected() {
        assertNull(VideoUrl.cleanSharedUrl("https://www.youtube.com/playlist?list=PLabc123def"))
    }

    @Test
    fun nonYoutubeRejected() {
        assertNull(VideoUrl.cleanSharedUrl("https://vimeo.com/123456789"))
    }

    @Test
    fun blankRejected() {
        assertNull(VideoUrl.cleanSharedUrl(null))
        assertNull(VideoUrl.cleanSharedUrl(""))
        assertNull(VideoUrl.cleanSharedUrl("   "))
    }

    @Test
    fun trailingPunctuation() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("wow: https://youtu.be/dQw4w9WgXcQ.")
        )
    }

    @Test
    fun titleWordLookalikeDoesNotBeatUrl() {
        // "Performance" is 11 ID-legal chars; the URL must still win.
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("Performance review https://youtu.be/dQw4w9WgXcQ")
        )
    }

    @Test
    fun idMixedWithOtherTextRejected() {
        // No URL anywhere: an 11-char token among other words is not trusted.
        assertNull(VideoUrl.cleanSharedUrl("loved this dQw4w9WgXcQ wow"))
    }

    @Test
    fun titleOnlyShareRejected() {
        assertNull(VideoUrl.cleanSharedUrl("Performance review of the year"))
    }

    @Test
    fun wholeTextBareIdAccepted() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoUrl.cleanSharedUrl("  dQw4w9WgXcQ  ")
        )
    }
}
