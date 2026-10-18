package com.listenlater

/** The feed-type segment PigeonPod expects in its /api/feed/{type}/ paths. */
object FeedTypes {
    fun pathFor(raw: String?): String =
        if (raw?.trim()?.lowercase() == "channel") "channel" else "playlist"
}
