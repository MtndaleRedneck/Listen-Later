package com.listenlater

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PigeonPod's own web API, used exactly the way the website uses it.
 * The app never invents feed settings: it previews, then posts the server's
 * own feed object back unchanged, so website settings are preserved on
 * every share and new feeds come from the site's feed defaults.
 */
class Api(private val server: String, private val token: String?, testClient: OkHttpClient? = null) {

    class SessionExpired : Exception()
    class ApiError(message: String, val code: Int = 0) : Exception(message)

    /** A reply that isn't a proper answer at all (an HTML page, a proxy error). */
    class UnexpectedResponse(val httpCode: Int) : java.io.IOException("Unexpected response ($httpCode)")

    /**
     * The server answered with a redirect this client won't follow. [targetOrigin] is the
     * scheme and host only - never the path or query, which can carry secrets.
     */
    class RedirectRefused(val code: Int, val targetOrigin: String?) :
        java.io.IOException("Redirect refused ($code -> ${targetOrigin ?: "no usable Location"})")

    data class ShareResult(val duplicate: Boolean)

    /** Whether the server carries the optional full-delete add-on. UNKNOWN means we could not tell. */
    enum class RemovalSupport { YES, NO, UNKNOWN }

    /** What the server did for a full delete. */
    enum class RemoveResult { REMOVED, UNLINKED, ALREADY_GONE }

    /** The video exists but is not in the Individual Videos feed. */
    class NotInFeed : Exception()

    /** The video is downloading right now and can't be removed yet. */
    class StillDownloading : Exception()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // OkHttp never follows redirects on its own here: it forwards our custom pigeonpod
    // header to any host a redirect points at, and on 307/308 it re-sends the body too -
    // for login() that body is the password. Redirects go through execute(), which honors
    // only one exact same-origin hop (see redirectTargetAllowed).
    private val client = testClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
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

    /**
     * Classify by what the server actually said:
     * 401, or a JSON reply saying the sign-in is missing/expired, asks him to sign in;
     * 5xx, 408 and 429 are weather and get quiet retries; a body that isn't JSON is
     * something between us and the app answering; only a real {code,msg} refusal
     * is treated as the server saying no.
     */
    /**
     * Sends the request, following at most one redirect and only to the exact same origin
     * (scheme, host and port): a reverse proxy's trailing-slash or canonicalization redirect
     * keeps working. A redirect anywhere else - another host, another port, plain http, or a
     * second hop - is never followed, so the session header and request bodies (the login
     * password) can only ever go to the configured server itself.
     */
    private fun execute(req: Request): Response {
        var current = req
        var followed = false
        while (true) {
            val res = client.newCall(current).execute()
            if (res.code !in 300..399) return res
            val code = res.code
            val next = res.header("Location")?.let { current.url.resolve(it) }
            res.close()
            if (next == null) throw UnexpectedResponse(code)
            if (!redirectTargetAllowed(current.url, next, followed)) throw RedirectRefused(code, originOf(next))
            current = followRequest(current, code, next)
            followed = true
        }
    }

    private fun call(req: Request): JSONObject {
        execute(req).use { res ->
            if (res.code == 401) throw SessionExpired()
            if (res.code >= 500) throw java.io.IOException("Server error ${res.code}")
            if (res.code == 408 || res.code == 429) throw java.io.IOException("Temporary (${res.code})")
            val body = res.body?.string().orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw UnexpectedResponse(res.code)
            }
            if (json.optInt("code", 0) != 200) {
                val msg = json.optString("msg", "")
                if (looksLikeAuthFailure(msg)) throw SessionExpired()
                throw ApiError(msg.ifEmpty { "Request failed" }, json.optInt("code", 0))
            }
            return json
        }
    }

    /** The server words a missing or expired sign-in a few ways, e.g. "未能读取到有效 token". */
    private fun looksLikeAuthFailure(msg: String): Boolean {
        if (msg.isEmpty()) return false
        val m = msg.lowercase()
        return m.contains("token") || m.contains("登录") || m.contains("登陆") || m.contains("login")
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
        try {
            execute(req).use { res ->
            if (res.code == 401) throw SessionExpired()
            if (res.code >= 500) throw java.io.IOException("Server error ${res.code}")
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
        } catch (e: RedirectRefused) {
            throw ApiError("The server tried to redirect the sign-in to ${e.targetOrigin ?: "another address"}. Check the server address.")
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

    fun episodes(feedId: String, page: Int = 1, size: Int = 50): List<JSONObject> {
        val data = call(get("/api/episode/list/$feedId?page=$page&size=$size")).optJSONObject("data")
            ?: return emptyList()
        val records = data.optJSONArray("records") ?: return emptyList()
        return (0 until records.length()).map { records.getJSONObject(it) }
    }

    /** Exact lookup: the server matches episode IDs directly (YouTube video IDs for YouTube feeds). */
    fun episodeStatus(ids: List<String>): List<JSONObject> {
        val arr = org.json.JSONArray()
        ids.forEach { arr.put(it) }
        val req = authed(Request.Builder().url(base() + "/api/episode/status"))
            .post(arr.toString().toRequestBody(jsonType))
            .build()
        val data = call(req).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it) }
    }

    /** Re-kicks a cleaned-up (READY) episode. Server accepts READY only. */
    fun download(id: String) {
        call(post("/api/episode/download/$id", JSONObject()))
    }

    fun retryEpisode(id: String) {
        call(post("/api/episode/retry/$id", JSONObject()))
    }

    fun subscribeUrl(feedId: String): String {
        val url = call(get("/api/feed/playlist/subscribe/$feedId")).optString("data", "")
        if (url.isEmpty()) throw ApiError("No feed link returned")
        return url
    }

    /**
     * Asks the server whether the full-delete add-on is installed. Only a proper JSON answer that
     * says so counts as YES; a stock server (404, an HTML page, JSON without the flag) is NO;
     * network trouble, timeouts and 5xx are UNKNOWN so a hiccup is never reported as "not supported".
     */
    fun removalSupport(): RemovalSupport = try {
        val data = call(get("/api/episode/individual/capability")).optJSONObject("data")
        if (data != null && data.optInt("removeIndividual", 0) == 1) RemovalSupport.YES else RemovalSupport.NO
    } catch (e: SessionExpired) {
        throw e
    } catch (e: ApiError) {
        // A stock server answers an unknown or forbidden route with a JSON error of its own.
        if (e.code in listOf(0, 403, 404, 405)) RemovalSupport.NO else RemovalSupport.UNKNOWN
    } catch (e: java.io.IOException) {
        // A non-JSON reply below 500 (the site's HTML page for an unknown path) means the route
        // isn't there; timeouts, connection errors, redirects refused and 5xx mean we can't tell.
        if (e is UnexpectedResponse) RemovalSupport.NO else RemovalSupport.UNKNOWN
    }

    /**
     * Full delete of one Individual Videos entry (add-on route). Throws [NotInFeed] for
     * code 422 and [StillDownloading] for 409; anything else unexpected is an error, never a success.
     */
    fun removeIndividual(id: String): RemoveResult {
        if (!Regex("[A-Za-z0-9_-]{1,64}").matches(id)) throw ApiError("Invalid video id")
        val url = base().toHttpUrl().newBuilder()
            .addPathSegments("api/episode/individual").addPathSegment(id).build()
        val data = try {
            call(authed(Request.Builder().url(url)).delete().build()).optJSONObject("data")
        } catch (e: ApiError) {
            when (e.code) {
                422 -> throw NotInFeed()
                409 -> throw StillDownloading()
                else -> throw e
            }
        }
        return when (data?.optString("result", "")) {
            "removed" -> RemoveResult.REMOVED
            "unlinked" -> RemoveResult.UNLINKED
            "already_gone" -> RemoveResult.ALREADY_GONE
            else -> throw UnexpectedResponse(200)
        }
    }
}

/** The one redirect still honored: exact same origin (scheme, host and port), a single hop. */
internal fun redirectTargetAllowed(from: HttpUrl, to: HttpUrl?, alreadyFollowed: Boolean): Boolean =
    !alreadyFollowed && to != null && to.scheme == from.scheme && to.host == from.host && to.port == from.port && to.isHttps

/**
 * Builds the request for the one allowed same-origin hop with OkHttp's standard redirect
 * semantics: 307/308 keep the method and body; 301/302/303 turn anything but GET/HEAD into
 * a plain GET without a body.
 */
internal fun followRequest(req: Request, code: Int, url: HttpUrl): Request = when {
    code == 307 || code == 308 || req.method == "GET" || req.method == "HEAD" -> req.newBuilder().url(url).build()
    else -> req.newBuilder().url(url).get().build()
}

/** scheme://host(:port) only, so a redirect target's path or query never leaks into UI or logs. */
private fun originOf(url: HttpUrl): String {
    val default = if (url.isHttps) 443 else 80
    val port = if (url.port != default) ":${url.port}" else ""
    return "${url.scheme}://${url.host}$port"

}
