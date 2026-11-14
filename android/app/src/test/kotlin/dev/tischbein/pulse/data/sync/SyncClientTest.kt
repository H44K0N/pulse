package dev.tischbein.pulse.data.sync

import dev.tischbein.pulse.AppConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncClientTest {
    private val http = FakeHttp()
    private val client = SyncClient(http, allowLocalHttp = false)
    private val url = "https://pulse.example.org/"

    private fun uploadAnswering(
        code: Int,
        body: String = "",
    ): UploadResult {
        http.onPost = { HttpResponse(code, body) }
        return client.upload(url, "tok", """{"id":"x"}""")
    }

    @Test
    fun `upload posts the document to api sessions with the token`() {
        uploadAnswering(201)
        val post = http.posts.single()
        assertEquals("https://pulse.example.org/api/sessions", post.url)
        assertEquals("tok", post.token)
        assertEquals("""{"id":"x"}""", post.body)
    }

    @Test
    fun `201 and 200 both mean stored`() {
        assertEquals(UploadResult.Stored, uploadAnswering(201))
        assertEquals(UploadResult.Stored, uploadAnswering(200))
    }

    @Test
    fun `413 and 422 are permanent rejections with the reason`() {
        assertIs<UploadResult.Rejected>(uploadAnswering(413, """{"detail":"Request body too large"}"""))
        val r = uploadAnswering(422, """{"detail":[{"type":"int_type","loc":["body","summary","avg_hr"],"msg":"Input should be a valid integer"}]}""")
        assertEquals(UploadResult.Rejected("Rejected by the server: summary.avg_hr: Input should be a valid integer"), r)
    }

    @Test
    fun `wrong token, server errors, redirects and the unexpected are retried`() {
        assertEquals(UploadResult.Retry(SyncClient.WRONG_TOKEN), uploadAnswering(401))
        assertEquals(UploadResult.Retry(SyncClient.WRONG_TOKEN), uploadAnswering(403))
        assertEquals(UploadResult.Retry("Server error (HTTP 503)"), uploadAnswering(503))
        assertIs<UploadResult.Retry>(uploadAnswering(500))
        assertIs<UploadResult.Retry>(uploadAnswering(301))
        assertIs<UploadResult.Retry>(uploadAnswering(404))
    }

    @Test
    fun `no connection is retried`() {
        http.onPost = FakeHttp.offline()
        assertEquals(UploadResult.Retry(SyncClient.UNREACHABLE), client.upload(url, "tok", "{}"))
    }

    @Test
    fun `long rejection reasons are shortened`() {
        val r = uploadAnswering(422, """{"detail":"${"x".repeat(1000)}"}""")
        assertTrue((r as UploadResult.Rejected).reason.length <= AppConstants.SYNC_MAX_ERROR_CHARS)
    }

    @Test
    fun `test connection checks health without the token, then the token`() {
        assertEquals(ConnectionCheck.OK, client.check(url, "tok"))
        assertEquals(listOf("https://pulse.example.org/healthz", "https://pulse.example.org/api/sessions?limit=1"), http.requests.map { it.url })
        assertEquals(listOf(null, "tok"), http.requests.map { it.token })
    }

    @Test
    fun `test connection reports each problem clearly`() {
        assertEquals(ConnectionCheck.NOT_CONFIGURED, client.check("", "tok"))
        assertEquals(ConnectionCheck.NOT_CONFIGURED, client.check(url, ""))
        assertEquals(ConnectionCheck.NOT_HTTPS, client.check("http://pulse.example.org", "tok"))
        assertEquals(ConnectionCheck.BAD_URL, client.check("https://pulse.example.org/?a=b", "tok"))
        assertTrue(http.requests.isEmpty(), "nothing is sent for an unusable URL")

        http.onGet = { if (it.token == null) HttpResponse(200, "{}") else HttpResponse(401, "") }
        assertEquals(ConnectionCheck.WRONG_TOKEN, client.check(url, "tok"))
        http.onGet = { HttpResponse(404, "") }
        assertEquals(ConnectionCheck.NOT_PULSE, client.check(url, "tok"))
        http.onGet = { HttpResponse(503, "") }
        assertEquals(ConnectionCheck.SERVER_ERROR, client.check(url, "tok"))
        http.onGet = FakeHttp.offline()
        assertEquals(ConnectionCheck.UNREACHABLE, client.check(url, "tok"))
    }

    @Test
    fun `debug builds may test against the developer's machine over http`() {
        val debugClient = SyncClient(http, allowLocalHttp = true)
        assertEquals(ConnectionCheck.OK, debugClient.check("http://localhost:8000", "tok"))
        assertEquals(ConnectionCheck.NOT_HTTPS, debugClient.check("http://pulse.example.org", "tok"))
    }
}
