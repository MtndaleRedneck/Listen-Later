package com.listenlater

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redirect safety: a server (or anything standing in front of it) that answers a 3xx must
 * never drag the pigeonpod session header or a request body to another server. Regression
 * tests for the finding that OkHttp forwards the custom header on every followed redirect
 * and re-sends the POST body (the login password) on 307/308.
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
    fun redirectNeverForwardsSessionHeaderOrBody() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val target = MockWebServer()
            target.start()
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", target.url("/landing")))
            server.start()
            val api = Api(server.url("/").toString().trimEnd('/'), "SECRET")
            val e = assertThrows(Api.UnexpectedResponse::class.java) {
                api.previewFeed("https://www.youtube.com/watch?v=abc")
            }
            assertEquals(status, e.httpCode)
            assertEquals("a $status must not be followed anywhere", 0, target.requestCount)
            target.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun loginNeverPostsPasswordToARedirectTarget() {
        val target = MockWebServer()
        target.start()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", target.url("/api/auth/login")))
        server.start()
        val api = Api(server.url("/").toString().trimEnd('/'), null)
        assertThrows(Api.UnexpectedResponse::class.java) {
            api.login("user", "PW-SECRET", null, null)
        }
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
}
