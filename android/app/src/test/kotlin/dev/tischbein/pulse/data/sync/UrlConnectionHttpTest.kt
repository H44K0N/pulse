package dev.tischbein.pulse.data.sync

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The real HttpURLConnection client against a tiny local HTTP server (the JDK's own
 * com.sun.net.httpserver, test only): headers, body, error bodies, no redirects.
 */
class UrlConnectionHttpTest {
    private lateinit var server: HttpServer
    private val received = mutableListOf<Map<String, String?>>()
    private val http = UrlConnectionHttp(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/sessions") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            received +=
                mapOf(
                    "method" to ex.requestMethod,
                    "auth" to ex.requestHeaders.getFirst("Authorization"),
                    "type" to ex.requestHeaders.getFirst("Content-Type"),
                    "encoding" to ex.requestHeaders.getFirst("Content-Encoding"),
                    "body" to body,
                )
            val (code, answer) = if (body.contains("invalid")) 422 to """{"detail":[{"msg":"bad"}]}""" else 201 to """{"created":true}"""
            val bytes = answer.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/moved") { ex ->
            ex.responseHeaders.add("Location", "$base/api/sessions")
            ex.sendResponseHeaders(301, -1)
            ex.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.stop(0) }
    }

    @Test
    fun `posts UTF-8 JSON with the bearer token, uncompressed`() {
        val r = http.postJson("$base/api/sessions", "tok-123", """{"text":"Großartig – 4×4"}""".toByteArray())
        assertEquals(201, r.code)
        assertEquals("""{"created":true}""", r.body)
        val req = received.single()
        assertEquals("POST", req["method"])
        assertEquals("Bearer tok-123", req["auth"])
        assertEquals("application/json", req["type"])
        assertEquals(null, req["encoding"])
        assertEquals("""{"text":"Großartig – 4×4"}""", req["body"])
    }

    @Test
    fun `error answers come back with their body`() {
        val r = http.postJson("$base/api/sessions", "tok", """{"invalid":true}""".toByteArray())
        assertEquals(422, r.code)
        assertTrue("bad" in r.body)
    }

    @Test
    fun `GET without a token sends no Authorization header`() {
        assertEquals(201, http.get("$base/api/sessions", token = null).code)
        assertEquals(null, received.single()["auth"])
    }

    @Test
    fun `redirects are not followed, so the token never goes elsewhere`() {
        val r = http.postJson("$base/moved", "tok", "{}".toByteArray())
        assertEquals(301, r.code)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `an unreachable server is an IOException`() {
        val port = server.address.port
        server.stop(0)
        assertFailsWith<IOException> { http.get("http://127.0.0.1:$port/healthz", token = null) }
    }
}
