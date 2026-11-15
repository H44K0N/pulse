package dev.tischbein.pulse.update

import com.sun.net.httpserver.HttpServer
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.sync.UrlConnectionHttp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real HTTP code of the update against a tiny local server (the JDK's com.sun.net.httpserver):
 * the check, the download, the bearer token only to the configured server, no redirects.
 */
class HttpUpdateServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Pair<String, String?>>()
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private var appBody = ""
    private var appCode = 200
    private var apkCode = 200
    private var apkBody = apk
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private var settings = AppSettings()

    private val updates get() = HttpUpdateServer({ settings }, UrlConnectionHttp(2_000, 2_000), HttpApkDownloader(2_000, 2_000), allowLocalHttp = true)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            requests += ex.requestURI.toString() to ex.requestHeaders.getFirst("Authorization")
            val (code, body) =
                when (ex.requestURI.path) {
                    "/api/app" -> appCode to appBody.toByteArray()
                    "/api/app/apk" -> apkCode to apkBody
                    else -> 404 to ByteArray(0)
                }
            if (code in 300..399) ex.responseHeaders.add("Location", "$base/elsewhere/pulse.apk")
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        }
        server.start()
        settings = AppSettings(serverUrl = base, apiToken = "tok-123")
        appBody = """{"published": true, "version_code": 7, "version_name": "0.5.2", "notes": "n", "size": ${apk.size}, "sha256": "${sha(apk)}", "apk": "/api/app/apk?v=7"}"""
    }

    @After
    fun tearDown() {
        runCatching { server.stop(0) }
    }

    private fun offer() = UpdateRules.offer(assertIs<ReleaseCheck.Published>(runBlocking { updates.check() }).release, 6)!!

    @Test
    fun `check asks GET api-app with the token`() {
        val r = assertIs<ReleaseCheck.Published>(runBlocking { updates.check() })
        assertEquals(7, r.release.versionCode)
        assertEquals(listOf<Pair<String, String?>>("/api/app" to "Bearer tok-123"), requests)
    }

    @Test
    fun `nothing published, and a server without the route, offer nothing`() {
        appBody = """{"published": false}"""
        assertEquals(ReleaseCheck.NothingPublished, runBlocking { updates.check() })
        appCode = 404
        assertEquals(ReleaseCheck.NothingPublished, runBlocking { updates.check() })
    }

    @Test
    fun `wrong token and server errors are quiet failures`() {
        appCode = 401
        assertEquals(ReleaseCheck.Failed(UpdateTexts.WRONG_TOKEN), runBlocking { updates.check() })
        appCode = 500
        assertIs<ReleaseCheck.Failed>(runBlocking { updates.check() })
        appCode = 302
        assertIs<ReleaseCheck.Failed>(runBlocking { updates.check() })
        assertEquals(3, requests.size, "the redirect was not followed")
    }

    @Test
    fun `offline is a quiet failure`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        settings = settings.copy(serverUrl = "http://127.0.0.1:$closedPort")
        assertEquals(ReleaseCheck.Failed(UpdateTexts.OFFLINE), runBlocking { updates.check() })
    }

    @Test
    fun `sync not set up - no request at all`() {
        settings = AppSettings()
        assertEquals(ReleaseCheck.NotConfigured, runBlocking { updates.check() })
        settings = AppSettings(serverUrl = base)
        assertEquals(ReleaseCheck.NotConfigured, runBlocking { updates.check() })
        // Release rules: plain http is refused, so not even the check goes out.
        settings = AppSettings(serverUrl = base, apiToken = "tok-123")
        val release = HttpUpdateServer({ settings }, UrlConnectionHttp(), HttpApkDownloader(), allowLocalHttp = false)
        assertEquals(ReleaseCheck.NotConfigured, runBlocking { release.check() })
        assertEquals(emptyList<Pair<String, String?>>(), requests)
    }

    @Test
    fun `download fetches the file from the configured server with the token, with progress`() {
        val o = offer()
        val dest = File(tmp.root, "pulse-7.part")
        var last = 0L
        val r = assertIs<DownloadResult.Done>(runBlocking { updates.download(o, dest) { last = it } })
        assertEquals(apk.size.toLong(), r.size)
        assertEquals(sha(apk), r.sha256)
        assertEquals(apk.toList(), dest.readBytes().toList())
        assertEquals(apk.size.toLong(), last)
        assertEquals("/api/app/apk?v=7" to "Bearer tok-123", requests.last())
    }

    @Test
    fun `a redirect is not followed, so the token goes nowhere else`() {
        val o = offer()
        apkCode = 302
        val r = runBlocking { updates.download(o, File(tmp.root, "x.part")) {} }
        assertEquals(DownloadResult.Failed(UpdateTexts.serverError(302)), r)
        assertTrue(requests.none { it.first.startsWith("/elsewhere") })
    }

    @Test
    fun `a body longer or shorter than stated fails`() {
        val o = offer()
        apkBody = apk + ByteArray(10)
        assertEquals(DownloadResult.Failed(UpdateTexts.DAMAGED), runBlocking { updates.download(o, File(tmp.root, "a.part")) {} })
        apkBody = apk.copyOf(1000)
        assertEquals(DownloadResult.Failed(UpdateTexts.DAMAGED), runBlocking { updates.download(o, File(tmp.root, "b.part")) {} })
    }

    @Test
    fun `wrong token on the download says so`() {
        val o = offer()
        apkCode = 401
        assertEquals(DownloadResult.Failed(UpdateTexts.WRONG_TOKEN), runBlocking { updates.download(o, File(tmp.root, "c.part")) {} })
    }

    @Test
    fun `only the api-app-apk path is ever downloaded`() {
        val o = offer().copy(apkPath = "/api/sessions")
        assertEquals(DownloadResult.Failed(UpdateTexts.DAMAGED), runBlocking { updates.download(o, File(tmp.root, "d.part")) {} })
        assertTrue(requests.none { it.first.startsWith("/api/sessions") })
    }

    @Test
    fun `sync switched off before the tap - nothing is downloaded`() {
        val o = offer()
        settings = AppSettings()
        assertEquals(DownloadResult.Failed(UpdateTexts.NO_SERVER), runBlocking { updates.download(o, File(tmp.root, "e.part")) {} })
        assertNull(requests.firstOrNull { it.first.startsWith("/api/app/apk") })
    }

    private fun sha(bytes: ByteArray) = HttpApkDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
