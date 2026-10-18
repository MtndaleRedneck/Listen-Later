package com.listenlater

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PigeonPod's own web API, used exactly the way the website uses it.
 * The app never invents feed settings: it previews, then posts the server's
 * own feed object back unchanged, so website settings are preserved on
 * every share and new feeds come from the site's feed defaults.
 */
class Api(private val server: String, private val token: String?) {

    class SessionExpired : Exception()
    class ApiError(message: String) : Exception(message)

    data class ShareResult(val duplicate: Boolean)

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun base() = server.trimEnd('/')

    private fun get(path: String): Request =
        authed(Request.Builder().url(base() + path)).get().build()

    private fun post(path: String, payload: JSONObject): Request =
        authed(Request.Builder().url(base() + path))
            .post(payload.toString().toRequestBody(jsonType))
            .build()

    private fun authed(b: Request.Builder): Request.Builder {
        b.header("Accept-Language", "en")
        if (!token.isNullOrEmpty()) b.header("pigeonpod", token)
        return b
    }

    private fun call(req: Request): JSONObject {
        client.newCall(req).execute().use { res ->
            if (res.code == 401) throw SessionExpired()
            val body = res.body?.string().orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ApiError("Unexpected response (${res.code})")
            }
            if (json.optInt("code", 0) != 200) {
                throw ApiError(json.optString("msg", "Request failed"))
            }
            return json
        }
    }

    fun captchaEnabled(): Boolean =
        call(get("/api/auth/captcha-config")).optBoolean("data", false)

    fun captcha(): Pair<String, String> {
        val data = call(get("/api/auth/captcha")).optJSONObject("data")
            ?: throw ApiError("No captcha returned")
        return Pair(data.optString("captchaId", ""), data.optString("imageData", ""))
    }

    /** Returns the session token carried by the pigeonpod cookie. Never logged. */
    fun login(username: String, password: String, captchaId: String?, captchaCode: String?): String {
        val payload = JSONObject()
            .put("username", username)
            .put("password", password)
        if (!captchaId.isNullOrEmpty()) {
            payload.put("captchaId", captchaId).put("captchaCode", captchaCode ?: "")
        }
        val req = Request.Builder()
            .url(base() + "/api/auth/login")
            .header("Accept-Language", "en")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { res ->
            if (res.code == 401) throw SessionExpired()
            val body = res.body?.string().orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ApiError("Unexpected response (${res.code})")
            }
            if (json.optInt("code", 0) != 200) {
                throw ApiError(json.optString("msg", "Login failed"))
            }
            val cookie = res.headers("Set-Cookie").firstOrNull { it.startsWith("pigeonpod=") }
                ?: throw ApiError("No session returned")
            return cookie.substringAfter("pigeonpod=").substringBefore(";")
        }
    }

    /**
     * Same call the website makes when you paste a link: the server resolves
     * the video and returns its own feed object, which we post back unchanged.
     */
    fun previewFeed(videoUrl: String): JSONObject {
        val data = call(post("/api/feed/fetch", JSONObject().put("source", videoUrl)))
            .optJSONObject("data") ?: throw ApiError("No preview returned")
        return data.optJSONObject("feed") ?: throw ApiError("No feed in preview")
    }

    fun addFeed(feed: JSONObject): ShareResult {
        val type = FeedTypes.pathFor(feed.optString("type", ""))
        val data = call(post("/api/feed/$type/add", feed)).optJSONObject("data")
        val message = data?.optString("message", "") ?: ""
        return ShareResult(message.contains("already in", ignoreCase = true))
    }

    fun episodes(feedId: String): List<JSONObject> {
        val data = call(get("/api/episode/list/$feedId?size=50")).optJSONObject("data")
            ?: return emptyList()
        val records = data.optJSONArray("records") ?: return emptyList()
        return (0 until records.length()).map { records.getJSONObject(it) }
    }

    fun retryEpisode(id: String) {
        call(post("/api/episode/retry/$id", JSONObject()))
    }

    fun subscribeUrl(feedId: String): String {
        val url = call(get("/api/feed/playlist/subscribe/$feedId")).optString("data", "")
        if (url.isEmpty()) throw ApiError("No feed link returned")
        return url
    }
}
