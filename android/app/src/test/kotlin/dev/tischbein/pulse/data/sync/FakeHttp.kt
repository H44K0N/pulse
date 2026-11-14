package dev.tischbein.pulse.data.sync

import java.io.IOException

/** A scripted [SyncHttp]: records every request and answers with [onPost] / [onGet]. */
class FakeHttp : SyncHttp {
    data class Request(val method: String, val url: String, val token: String?, val body: String?)

    val requests = mutableListOf<Request>()
    val posts get() = requests.filter { it.method == "POST" }

    var onPost: (Request) -> HttpResponse = { HttpResponse(201, """{"created":true}""") }
    var onGet: (Request) -> HttpResponse = { HttpResponse(200, "{}") }

    override fun postJson(
        url: String,
        token: String,
        body: ByteArray,
    ): HttpResponse = Request("POST", url, token, body.toString(Charsets.UTF_8)).also { requests += it }.let(onPost)

    override fun get(
        url: String,
        token: String?,
    ): HttpResponse = Request("GET", url, token, null).also { requests += it }.let(onGet)

    companion object {
        fun offline(): (Request) -> HttpResponse = { throw IOException("Unable to resolve host") }
    }
}
