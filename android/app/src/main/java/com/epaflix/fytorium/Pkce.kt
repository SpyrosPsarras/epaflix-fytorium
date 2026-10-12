package com.epaflix.fytorium

import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

const val REDIRECT_URI = "com.epaflix.fytorium:/oauth2redirect"

class LoginAttempt(val verifier: String, val state: String, val createdAt: Long) {
    val challenge: String get() = Pkce.challenge(verifier)
    fun codeFrom(redirect: String, now: Long): String? {
        val uri = runCatching { URI(redirect) }.getOrNull() ?: return null
        if (uri.scheme != "com.epaflix.fytorium" || uri.rawAuthority != null ||
            uri.path != "/oauth2redirect" || uri.fragment != null || now - createdAt !in 0..600_000) return null
        val pairs = runCatching {
            uri.rawQuery.orEmpty().split('&').map { pair ->
                val parts = pair.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
        }.getOrNull() ?: return null
        if (pairs.map { it.first }.distinct().size != pairs.size) return null
        val params = pairs.toMap()
        if (!MessageDigest.isEqual(state.toByteArray(), params["state"].orEmpty().toByteArray()) || "error" in params) return null
        return params["code"]?.takeIf { it.isNotBlank() && it.length <= 1024 }
    }
}

object Pkce {
    private fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun create(now: Long = System.currentTimeMillis()) = LoginAttempt(random(), random(), now)
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
