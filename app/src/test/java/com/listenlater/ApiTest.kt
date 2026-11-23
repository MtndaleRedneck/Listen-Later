package com.listenlater

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Redirect safety: OkHttp forwards the custom pigeonpod session header to any host a
 * redirect points at, and on 307/308 it re-sends the POST body (for login, the password).
 * The client therefore never auto-follows; execute() honors one exact same-origin
 * (scheme+host+port) https hop with standard method/body semantics and refuses the rest.
 */
class ApiTest {

    private fun ok(data: String = "false") = """{"code":200,"msg":"","data":$data}"""

    @Test
    fun normalJsonAnswerStillWorks() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(ok("true")))
        server.start()
        val api = Api(server.url("/").toString().trimEnd('/'), "SECRET")
        assertTrue(api.captchaEnabled())
        assertEquals("SECRET", server.takeRequest().getHeader("pigeonpod"))
        server.shutdown()
    }

    @Test
    fun crossHostRedirectNeverForwardsSessionHeaderOrBody() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val target = MockWebServer()
            // A queued answer keeps a regression (client follows anyway) a fast, clean failure
            // instead of a 60-second read timeout.
            target.enqueue(MockResponse().setBody(ok()))
            target.start()
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(status)
                .addHeader("Location", target.url("/landing")))
            server.start()
            val api = Api(server.url("/").toString().trimEnd('/'), "SECRET")
            assertThrows(Api.RedirectRefused::class.java) {
                api.previewFeed("https://www.youtube.com/watch?v=abc")
            }
            assertEquals("a $status must not be followed anywhere", 0, target.requestCount)
            target.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun getRequestsRefuseRedirectsToo() {
        val target = MockWebServer()
        target.enqueue(MockResponse().setBody(ok()))
        target.start()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", target.url("/api/auth/captcha-config")))
        server.start()
        val api = Api(server.url("/").toString().trimEnd('/'), "SECRET")
        assertThrows(Api.RedirectRefused::class.java) { api.captchaEnabled() }
        assertEquals(0, target.requestCount)
        target.shutdown()
        server.shutdown()
    }

    @Test
    fun refusedRedirectCarriesOnlyTheOriginNeverPathOrQuery() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(307)
            .addHeader("Location", "https://evil.example/collect?pigeonpod=stolen"))
        server.start()
        val api = Api(server.url("/").toString().trimEnd('/'), "SECRET")
        val e = assertThrows(Api.RedirectRefused::class.java) {
            api.previewFeed("https://www.youtube.com/watch?v=abc")
        }
        assertEquals("https://evil.example", e.targetOrigin)
        server.shutdown()
    }

    @Test
    fun sameOriginButPlainHttpIsRefused() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(308).addHeader("Location", server.url("/landing")))
        server.start()
        val api = Api(server.url("/").toString().trimEnd('/'), "SECRET")
        assertThrows(Api.RedirectRefused::class.java) { api.captchaEnabled() }
        assertEquals(1, server.requestCount) // only the first request, never the hop
        server.shutdown()
    }

    @Test
    fun loginNeverPostsPasswordToARedirectTarget() {
        val target = MockWebServer()
        target.enqueue(MockResponse().setBody(ok()))
        target.start()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", target.url("/api/auth/login")))
        server.start()
        val api = Api(server.url("/").toString().trimEnd('/'), null)
        val e = assertThrows(Api.ApiError::class.java) {
            api.login("user", "PW-SECRET", null, null)
        }
        assertTrue(e.message!!.contains("redirect", ignoreCase = true))
        assertEquals(0, target.requestCount)
        target.shutdown()
        server.shutdown()
    }

    @Test
    fun loginSuccessStillReadsTheSessionCookie() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(ok("null"))
                .addHeader("Set-Cookie", "pigeonpod=tok123; Path=/; HttpOnly")
        )
        server.start()
        val api = Api(server.url("/").toString().trimEnd('/'), null)
        assertEquals("tok123", api.login("user", "pw", null, null))
        server.shutdown()
    }

    @Test
    fun policyAllowsOneExactSameOriginHop() {
        val from = "https://server.example/api/feed/fetch".toHttpUrl()
        assertTrue(redirectTargetAllowed(from, "https://server.example/api/feed/fetch/".toHttpUrl(), false))
        assertTrue("explicit default port normalizes", redirectTargetAllowed(from, "https://server.example:443/api".toHttpUrl(), false))
        assertTrue("relative Location resolves same-origin", redirectTargetAllowed(from, from.resolve("/api/feed/fetch/"), false))
    }

    @Test
    fun policyDeniesHostPortSchemeAndChainEscapes() {
        val from = "https://server.example/api/feed/fetch".toHttpUrl()
        assertFalse("another host", redirectTargetAllowed(from, "https://other.example/api".toHttpUrl(), false))
        assertFalse("another port can be another service", redirectTargetAllowed(from, "https://server.example:4443/api".toHttpUrl(), false))
        assertFalse("downgrade to plain http", redirectTargetAllowed(from, "http://server.example/api".toHttpUrl(), false))
        assertFalse("a second hop", redirectTargetAllowed(from, "https://server.example/x".toHttpUrl(), true))
        assertFalse("no Location at all", redirectTargetAllowed(from, null, false))
    }

    @Test
    fun followRequestKeepsMethodAndBodyOn307And308() {
        val url = "https://server.example/api/feed/add".toHttpUrl()
        val post = okhttp3.Request.Builder().url("https://server.example/api/feed")
            .post("{\"password\":\"x\"}".toRequestBody("application/json".toMediaType())).build()
        for (code in listOf(307, 308)) {
            val r = followRequest(post, code, url)
            assertEquals("POST", r.method)
            assertTrue("body rides along on $code", r.body != null)
            assertEquals(url, r.url)
        }
    }

    @Test
    fun followRequestTurnsPostIntoGetOn301302And303() {
        val url = "https://server.example/api/feed".toHttpUrl()
        val post = okhttp3.Request.Builder().url("https://server.example/api/feed")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        for (code in listOf(301, 302, 303)) {
            val r = followRequest(post, code, url)
            assertEquals("GET", r.method)
            assertNull("no body forwarded on $code", r.body)
        }
        val get = okhttp3.Request.Builder().url("https://server.example/api").build()
        assertEquals("GET", followRequest(get, 302, url).method)
    }
}
