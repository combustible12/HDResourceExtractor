package com.combustible12.hdresourceextractor

import java.net.URI
import java.security.MessageDigest

object ResourceIdentity {
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    /** Keep every query parameter: signatures may also select genuinely different content. */
    fun of(url: String): String {
        val u = runCatching { URI(url).normalize() }.getOrNull()
        if (u == null || u.host.isNullOrBlank() || u.scheme.isNullOrBlank()) return hash(url.substringBefore('#'))
        val port = if (u.port == 80 && u.scheme.equals("http", true) || u.port == 443 && u.scheme.equals("https", true)) "" else if (u.port >= 0) ":${u.port}" else ""
        return hash("${u.scheme.lowercase()}://${u.host.lowercase()}$port${u.rawPath.ifEmpty { "/" }}${u.rawQuery?.let { "?$it" }.orEmpty()}")
    }
}
