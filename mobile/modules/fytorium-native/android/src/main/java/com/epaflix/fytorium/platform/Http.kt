package com.epaflix.fytorium.platform

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HttpResult(val status: Int, val headers: Map<String, String>, val body: String)

class Http {
    // A transport retry or follow-up could replay a write or a single-use login code.
    val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        // Retire idle sockets before the server's five-second keep-alive timeout.
        .connectionPool(ConnectionPool(5, 1, TimeUnit.SECONDS))
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    // Token exchanges never reuse a pooled socket that may have expired during browser login.
    val tokenClient: OkHttpClient = client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()

    fun networkLost() = client.connectionPool.evictAll()

    private class OneShotBody(private val bytes: ByteArray, private val type: MediaType?) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = bytes.size.toLong()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
    }

    fun request(method: String, url: String, headers: Map<String, String>, body: String?, tokenEndpoint: Boolean): Call {
        val type = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.toMediaType()
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            method(method, body?.let { OneShotBody(it.toByteArray(), type) })
        }.build()
        return (if (tokenEndpoint) tokenClient else client).newCall(request)
    }

    fun enqueue(call: Call, binary: Boolean, done: (Result<HttpResult>) -> Unit) = call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = done(Result.failure(e))
        override fun onResponse(call: Call, response: Response) = done(runCatching {
            response.use {
                val bytes = it.body?.bytes() ?: ByteArray(0)
                HttpResult(it.code, it.headers.names().associate { name -> name.lowercase() to it.headers(name).joinToString(", ") },
                    if (binary) java.util.Base64.getEncoder().encodeToString(bytes) else bytes.decodeToString())
            }
        })
    })
}
