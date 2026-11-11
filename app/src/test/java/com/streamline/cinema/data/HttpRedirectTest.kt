package com.streamline.cinema.data

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class HttpRedirectTest {

    private val servers = mutableListOf<HttpServer>()
    private val client = OkHttpClient()

    @After
    fun stopServers() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    @Test
    fun followsRelativeLocationsAndReportsFinalUrl() = runBlocking {
        val base = url(
            start(
                "/start" to redirect("/hop", 302),
                // Relative Location with no leading slash resolves against /hop.
                "/hop" to redirect("final", 307),
                "/final" to text("ok")
            )
        )
        val request = Request.Builder().url("$base/start").build()
        client.awaitRedirects(request).use { resolved ->
            assertEquals("$base/final", resolved.finalUrl)
            assertEquals(200, resolved.code)
            assertEquals(2, resolved.hops.size)
            assertEquals(302, resolved.hops[0].code)
            assertEquals(307, resolved.hops[1].code)
            assertEquals("ok", resolved.response.body?.string())
        }
    }

    @Test
    fun followsAbsoluteLocationToAnotherOrigin() = runBlocking {
        val targetPort = AtomicReference(0)
        val base = url(
            start("/a" to redirectTo({ "http://127.0.0.1:${targetPort.get()}/next" }, 301))
        )
        val other = start("/next" to text("next"))
        targetPort.set(other.address.port)
        val request = Request.Builder().url("$base/a").build()
        client.awaitRedirects(request).use { resolved ->
            assertTrue(resolved.finalUrl.startsWith("http://127.0.0.1:${other.address.port}/next"))
            assertEquals("next", resolved.response.body?.string())
        }
    }

    @Test
    fun keepsRequestHeadersAcrossHops() = runBlocking {
        val range = AtomicReference<String?>(null)
        val base = url(
            start(
                "/range" to redirect("/final", 302),
                "/final" to HttpHandler { exchange ->
                    range.set(exchange.requestHeaders.getFirst("Range"))
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
            )
        )
        val request = Request.Builder()
            .url("$base/range")
            .header("Range", "bytes=0-1023")
            .build()
        client.awaitRedirects(request).use { assertEquals(200, it.code) }
        assertEquals("bytes=0-1023", range.get())
    }

    @Test
    fun dropsCredentialsWhenTheOriginChanges() = runBlocking {
        val seen = AtomicReference<String?>("unset")
        val targetPort = AtomicReference(0)
        val base = url(
            start("/auth" to redirectTo({ "http://127.0.0.1:${targetPort.get()}/next" }, 302))
        )
        val other = start(
            "/next" to HttpHandler { exchange ->
                seen.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        )
        targetPort.set(other.address.port)
        val request = Request.Builder()
            .url("$base/auth")
            .header("Authorization", "Bearer secret")
            .build()
        client.awaitRedirects(request).use { assertEquals(200, it.code) }
        assertNull(seen.get())
    }

    @Test
    fun detectsSelfRedirectLoop() = runBlocking {
        val base = url(start("/loop" to redirect("/loop", 302)))
        val request = Request.Builder().url("$base/loop").build()
        val error = runCatching { client.awaitRedirects(request).use { it.code } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("Redirect loop"))
    }

    @Test
    fun stopsAfterMaxHops() = runBlocking {
        val base = url(
            start(
                "/r0" to redirect("/r1", 302),
                "/r1" to redirect("/r2", 302),
                "/r2" to redirect("/r3", 302),
                "/r3" to text("done")
            )
        )
        val request = Request.Builder().url("$base/r0").build()
        val error = runCatching { client.awaitRedirects(request, maxHops = 2).use { it.code } }
            .exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("Too many redirects"))
    }

    @Test
    fun reportsNonRedirectResponseWithoutHops() = runBlocking {
        val base = url(start("/direct" to text("body")))
        val request = Request.Builder().url("$base/direct").build()
        client.awaitRedirects(request).use { resolved ->
            assertEquals("$base/direct", resolved.finalUrl)
            assertEquals(0, resolved.hops.size)
            assertFalse(resolved.redirected)
        }
    }

    @Test
    fun doesNotFollowNotModifiedResponses() = runBlocking {
        // 304 can carry a Location header but is a cache response, not a hop.
        val base = url(
            start(
                "/cached" to HttpHandler { exchange ->
                    exchange.responseHeaders.add("Location", "/other")
                    exchange.sendResponseHeaders(304, -1)
                    exchange.close()
                }
            )
        )
        val request = Request.Builder().url("$base/cached").build()
        client.awaitRedirects(request).use { resolved ->
            assertEquals("$base/cached", resolved.finalUrl)
            assertEquals(0, resolved.hops.size)
        }
    }

    private fun start(vararg routes: Pair<String, HttpHandler>): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        routes.forEach { (path, handler) -> server.createContext(path, handler) }
        server.start()
        servers += server
        return server
    }

    private fun url(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    private fun redirect(location: String, code: Int) = redirectTo({ location }, code)

    private fun redirectTo(location: () -> String, code: Int) = HttpHandler { exchange ->
        exchange.responseHeaders.add("Location", location())
        exchange.sendResponseHeaders(code, -1)
        exchange.close()
    }

    private fun text(body: String) = HttpHandler { exchange ->
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
