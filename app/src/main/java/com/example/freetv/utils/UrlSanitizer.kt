package com.example.freetv.utils

/**
 * Helpers for handling untrusted, user-supplied playlist and stream URLs.
 *
 * Playlist URLs frequently carry credentials (`http://user:pass@host/...`,
 * `?username=..&password=..`, `?token=..`). [redact] must be used before any
 * URL is written to a log, error message or crash report.
 */
object UrlSanitizer {

    private val SUPPORTED_SCHEMES = setOf("http", "https")

    private val SENSITIVE_QUERY_KEYS = setOf(
        "username", "user", "password", "pass", "pwd",
        "token", "auth", "key", "apikey", "api_key", "access_token", "signature", "sig",
    )

    private val USER_INFO_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s]+@")
    private val QUERY_PARAM_REGEX = Regex("([?&])([^=&#]+)=([^&#]*)")

    /** Returns true when [url] is an absolute http(s) URL with a host. */
    fun isValidRemoteUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = value.substring(0, schemeEnd).lowercase()
        if (scheme !in SUPPORTED_SCHEMES) return false
        val afterScheme = value.substring(schemeEnd + 3)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').removePrefix("[").removeSuffix("]")
        return host.isNotEmpty()
    }

    /** Replaces user-info and sensitive query values with `***`. */
    fun redact(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val noUserInfo = USER_INFO_REGEX.replace(url) { "${it.groupValues[1]}***@" }
        return QUERY_PARAM_REGEX.replace(noUserInfo) { match ->
            val (separator, key, _) = match.destructured
            if (key.lowercase() in SENSITIVE_QUERY_KEYS) "$separator$key=***" else match.value
        }
    }
}
