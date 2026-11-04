package com.example.campus.services

import android.content.Context
import android.os.Looper
import net.openid.appauth.AuthorizationService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** Blocking calls belong on a worker thread. OkHttp negotiates HTTP/2 over verified TLS. */
class LiveApiClient(context: Context) {
    private val context = context.applicationContext
    private val config = ServiceConfig(context)
    fun request(method: String, path: String, payload: JSONObject? = null): JSONObject = exchange(method, path, payload).let { if (it.isBlank()) JSONObject() else JSONObject(it) }
    fun requestArray(path: String): JSONArray = JSONArray(exchange("GET", path, null))
    private fun exchange(method: String, path: String, payload: JSONObject?): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "API requests must run on a background thread." }
        require(config.configured) { "Campus API is not configured." }
        require(path.startsWith("/api/v1/") && !path.contains("..") && !path.contains("://")) { "Invalid API path." }
        val session = SecureSession(context)
        val state = session.read() ?: throw ApiException(401, "Sign in through your university first.")
        val service = AuthorizationService(context)
        val latch = CountDownLatch(1)
        var accessToken: String? = null
        var failed = false
        try {
            state.performActionWithFreshTokens(service) { token, _, exception ->
                accessToken = token
                failed = exception != null
                if (!failed) session.save(state)
                latch.countDown()
            }
            if (!latch.await(30, TimeUnit.SECONDS) || failed || accessToken.isNullOrBlank()) throw ApiException(401, "Your university session expired. Sign in again.")
        } finally { service.dispose() }
        val body = payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(config.apiBaseUrl + path)
            .header("Authorization", "Bearer $accessToken").header("Accept", "application/json")
            .method(method.uppercase(), if (method.uppercase() in listOf("POST", "PUT", "PATCH")) body ?: "{}".toRequestBody("application/json".toMediaType()) else body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                400 -> "The campus API rejected these details. Check your request."
                401 -> "Your university session expired. Sign in again."
                403 -> "This account is not authorized by the university."
                404 -> "The requested campus item is unavailable."
                409 -> "This item changed. Refresh before trying again."
                else -> "The campus API is unavailable (HTTP ${response.code}). Try again later."
            })
            val text = response.body?.string().orEmpty()
            return text
        }
    }
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
