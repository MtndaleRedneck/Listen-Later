package com.listenlater

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedTypesTest {

    @Test
    fun playlistIsDefault() {
        assertEquals("playlist", FeedTypes.pathFor(null))
        assertEquals("playlist", FeedTypes.pathFor(""))
        assertEquals("playlist", FeedTypes.pathFor("PLAYLIST"))
        assertEquals("playlist", FeedTypes.pathFor("something-else"))
    }

    @Test
    fun channelRecognized() {
        assertEquals("channel", FeedTypes.pathFor("CHANNEL"))
        assertEquals("channel", FeedTypes.pathFor("channel"))
        assertEquals("channel", FeedTypes.pathFor(" Channel "))
    }
}
