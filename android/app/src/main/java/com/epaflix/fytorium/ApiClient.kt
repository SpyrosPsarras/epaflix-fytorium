package com.epaflix.fytorium

import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient(private val baseUrl: String, private val store: CredentialStore, private val onSessionLost: () -> Unit) {
    companion object {
        // Refresh tokens rotate once. All clients in this process must share this lock.
        private val refreshLock = Any()
    }
    // A transport retry could replay a refresh token already consumed by the server.
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    // Single-use grants cannot retry if a pooled socket expires during browser login.
    private val tokenHttp = http.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    fun networkLost() {
        // Airplane mode can abort idle sockets without making them look closed to OkHttp.
        http.connectionPool.evictAll()
    }
    fun loginUrl(): String {
        val attempt = Pkce.create()
        store.pending = attempt
        return (baseUrl + "/auth/login").toHttpUrl().newBuilder()
            .addQueryParameter("app", "android").addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("code_challenge", attempt.challenge).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", attempt.state).build().toString()
    }
    @Synchronized fun completeLogin(redirect: String): Boolean {
        val attempt = store.pending ?: return false
        val code = attempt.codeFrom(redirect, System.currentTimeMillis()) ?: return false
        store.pending = null // Only a validated redirect may consume the current attempt.
        val result = exchange(JSONObject().put("grant_type", "authorization_code").put("code", code)
            .put("code_verifier", attempt.verifier).put("device_name", "Fytorium Android")) ?: return false
        synchronized(refreshLock) { store.tokens = result }
        return true
    }
    private fun exchange(body: JSONObject, refreshing: Boolean = false): Tokens? {
        val request = Request.Builder().url(baseUrl + "/api/app/token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return tokenHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (!refreshing) return null
                val error = runCatching { JSONObject(response.body?.string().orEmpty()).optString("error") }.getOrNull()
                if (response.code in listOf(400, 401) && error in listOf("invalid_grant", "invalid_token")) return null
                throw IOException("Refresh unavailable. Please retry.")
            }
            val json = JSONObject(response.body?.string() ?: throw IOException("Token response unavailable"))
            if (json.optString("token_type") != "Bearer") throw IOException("Invalid token response")
            val access = json.getString("access_token")
            val refresh = json.getString("refresh_token")
            if (access.isBlank() || refresh.isBlank()) throw IOException("Invalid token response")
            Tokens(access, refresh)
        }
    }
    fun request(path: String, post: Boolean = false, method: String = if (post) "POST" else "GET",
                body: JSONObject? = null, query: Map<String, String> = emptyMap(), requestBody: okhttp3.RequestBody? = null): Response {
        require(path.startsWith("/api/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        require(method in listOf("GET", "POST", "PUT", "DELETE"))
        val original = store.tokens
        fun send(tokens: Tokens?): Response {
            val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val builder = Request.Builder().url(url).header("X-App-Version", BuildConfig.VERSION_NAME).header("Accept", "application/json")
            tokens?.let { builder.header("Authorization", "Bearer " + it.access) }
            if (method != "GET") builder.method(method, requestBody ?: body?.toString()?.toRequestBody("application/json".toMediaType())
                ?: ByteArray(0).toRequestBody(null))
            return http.newCall(builder.build()).execute()
        }
        val response = send(original)
        if (response.code != 401 || original == null) return response
        val rejected = response.newBuilder().body(ByteArray(0).toResponseBody(null)).build()
        response.close()
        val refreshed = try {
            synchronized(refreshLock) {
                val current = store.tokens
                when {
                    current == null -> null
                    current.refresh != original.refresh -> current
                    else -> exchange(JSONObject().put("grant_type", "refresh_token").put("refresh_token", current.refresh), refreshing = true)
                        .also { if (it == null) loseSession() else store.tokens = it }
                }
            }
        } catch (error: Exception) {
            rejected.close()
            throw if (error is IOException) error else IOException("Refresh unavailable. Please retry.", error)
        }
        // No unauthenticated replay and no recursive refresh.
        if (refreshed == null) return rejected
        rejected.close()
        return send(refreshed)
    }
    private fun loseSession() { store.clear(); onSessionLost() }
    fun logout() = synchronized(refreshLock) {
        try { if (store.tokens != null) request("/api/app/logout", post = true).close() }
        finally { loseSession() }
    }
}

fun requiresUpdate(status: Int, body: String, current: String): Boolean {
    if (status == 426) return true
    if (status == 404 || status !in 200..299) return false
    val minimum = JSONObject(body).optString("minAppVersion", "0.0.0")
    // Android release tags are stable versions. Compare their core against semver minima.
    fun version(value: String) = value.substringBefore('+').substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    val required = version(minimum)
    val own = version(current)
    for (i in 0 until maxOf(required.size, own.size)) {
        val comparison = required.getOrElse(i) { 0 }.compareTo(own.getOrElse(i) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return false
}
