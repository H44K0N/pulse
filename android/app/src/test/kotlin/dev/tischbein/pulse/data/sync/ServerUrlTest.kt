package dev.tischbein.pulse.data.sync

import dev.tischbein.pulse.data.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUrlTest {
    private fun ok(
        url: String,
        debug: Boolean = false,
    ) = assertNull(ServerUrl.problem(url, allowLocalHttp = debug), url)

    private fun bad(
        url: String,
        debug: Boolean = false,
    ) = assertNotNull(ServerUrl.problem(url, allowLocalHttp = debug), url)

    @Test
    fun `empty means sync off and is fine`() {
        ok("")
        ok("   ")
    }

    @Test
    fun `https URLs are accepted, with port, path and trailing slash`() {
        ok("https://pulse-api.tischbein.dev")
        ok("https://pulse-api.tischbein.dev/")
        ok("https://pulse.example.com:8443/pulse")
        ok("HTTPS://Pulse.Example.com")
    }

    @Test
    fun `plain http is refused in release builds, even to localhost`() {
        assertEquals(ServerUrl.NOT_HTTPS, ServerUrl.problem("http://pulse.example.com", allowLocalHttp = false))
        bad("http://localhost:8000")
        bad("http://10.0.2.2:8000")
        bad("http://127.0.0.1:8000")
    }

    @Test
    fun `debug builds allow http only to the developer's own machine`() {
        ok("http://localhost:8000", debug = true)
        ok("http://127.0.0.1:8000", debug = true)
        ok("http://10.0.2.2:8000", debug = true)
        bad("http://pulse.example.com", debug = true)
        bad("http://192.168.1.10:8000", debug = true)
        bad("http://localhost.example.com", debug = true)
    }

    @Test
    fun `other schemes, missing host, credentials, query and fragment are refused`() {
        bad("pulse.example.com")
        bad("ftp://pulse.example.com")
        bad("https://")
        bad("https://user:pass@pulse.example.com")
        bad("https://pulse.example.com/?token=x")
        bad("https://pulse.example.com/#x")
        bad("https://pulse example.com")
    }

    @Test
    fun `endpoints are joined without double slashes`() {
        assertEquals("https://p.example.com/api/sessions", ServerUrl.endpoint("https://p.example.com/", "/api/sessions"))
        assertEquals("https://p.example.com/x/healthz", ServerUrl.endpoint(" https://p.example.com/x ", "/healthz"))
    }

    @Test
    fun `sync is configured only with a usable URL and a token`() {
        assertFalse(AppSettings().syncConfigured(allowLocalHttp = false))
        assertFalse(AppSettings(serverUrl = "https://p.example.com").syncConfigured(allowLocalHttp = false))
        assertFalse(AppSettings(apiToken = "t").syncConfigured(allowLocalHttp = false))
        assertFalse(AppSettings(serverUrl = "http://p.example.com", apiToken = "t").syncConfigured(allowLocalHttp = false))
        assertTrue(AppSettings(serverUrl = "https://p.example.com", apiToken = "t").syncConfigured(allowLocalHttp = false))
        assertTrue(AppSettings(serverUrl = "http://localhost:8000", apiToken = "t").syncConfigured(allowLocalHttp = true))
    }

    @Test
    fun `an unusable URL blocks saving the settings`() {
        assertTrue(AppSettings(serverUrl = "http://pulse.example.com").validationErrors(allowLocalHttp = false).isNotEmpty())
        assertTrue(AppSettings(serverUrl = "https://pulse.example.com").validationErrors(allowLocalHttp = false).isEmpty())
    }
}
