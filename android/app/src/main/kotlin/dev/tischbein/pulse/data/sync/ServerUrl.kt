package dev.tischbein.pulse.data.sync

import java.net.URI
import java.net.URISyntaxException

/**
 * The server URL typed in Settings: checked and turned into endpoint URLs. The only place that
 * decides whether the app may talk to a URL.
 *
 * Rules: empty = sync off. Otherwise `https://host[:port][/path]`, no user info, query or
 * fragment. Plain `http://` only when [allowLocalHttp] (debug builds) and only to the
 * developer's own machine ([LOCAL_HOSTS]), matching src/debug/res/xml/network_security_config.xml.
 * Release builds never send anything unencrypted, so the API token cannot leak in cleartext.
 */
object ServerUrl {
    /** localhost / 127.0.0.1: a phone with `adb reverse`; 10.0.2.2: the host seen from the emulator. */
    val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    const val NOT_HTTPS = "Server URL must start with https://"

    /** Trims and drops trailing slashes, so "https://x/" and "https://x" are the same server. */
    fun normalize(url: String): String = url.trim().trimEnd('/')

    /** Why [url] cannot be used, or null if it is fine (or empty = sync off). */
    fun problem(
        url: String,
        allowLocalHttp: Boolean,
    ): String? {
        val u = normalize(url)
        if (u.isEmpty()) return null
        val uri =
            try {
                URI(u)
            } catch (_: URISyntaxException) {
                return "Not a valid URL"
            }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        return when {
            scheme != "https" && scheme != "http" -> NOT_HTTPS
            host.isNullOrEmpty() -> "Not a valid URL"
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null -> "Only the server address, like https://pulse.example.com"
            scheme == "http" && !(allowLocalHttp && host in LOCAL_HOSTS) -> NOT_HTTPS
            else -> null
        }
    }

    /** [path] (starting with "/") appended to the server URL. Only call with a URL that has no [problem]. */
    fun endpoint(
        url: String,
        path: String,
    ): String = normalize(url) + path
}
