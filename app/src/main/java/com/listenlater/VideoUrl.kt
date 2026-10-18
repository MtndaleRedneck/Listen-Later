package com.listenlater

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns whatever a share sheet hands us (share text, tracking params, youtu.be,
 * Shorts, music.youtube.com, bare IDs) into one clean watch URL. Playlist-only
 * links and non-video input come back null so the caller can say so.
 * JVM-only code (okhttp HttpUrl) so unit tests exercise the real thing.
 */
object VideoUrl {

    private val ID = Regex("[A-Za-z0-9_-]{11}")

    fun cleanSharedUrl(sharedText: String?): String? {
        val id = extractVideoId(sharedText) ?: return null
        return "https://www.youtube.com/watch?v=$id"
    }

    fun extractVideoId(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        for (raw in sharedText.trim().split(Regex("\\s+"))) {
            val token = raw.trim().trim('<', '>', '"', '\'', '(', ')', ',', '.', '!', ';')
            fromToken(token)?.let { return it }
        }
        return null
    }

    private fun fromToken(token: String): String? {
        if (token.isEmpty()) return null
        if (ID.matches(token)) return token
        val url = token.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()
        if (host == "youtu.be") {
            val seg = url.pathSegments.firstOrNull { it.isNotEmpty() } ?: return null
            return if (ID.matches(seg)) seg else null
        }
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            if (url.encodedPath.startsWith("/playlist")) return null
            url.queryParameter("v")?.let { if (ID.matches(it)) return it }
            val segs = url.pathSegments
            for (marker in listOf("shorts", "live", "embed")) {
                val i = segs.indexOf(marker)
                if (i >= 0 && i + 1 < segs.size && ID.matches(segs[i + 1])) return segs[i + 1]
            }
        }
        return null
    }
}
