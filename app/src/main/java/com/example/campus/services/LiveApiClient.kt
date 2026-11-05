package com.example.campus.services

import android.content.Context
import android.net.Uri
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** Blocking calls belong on a worker thread. OkHttp negotiates HTTP/2 over verified TLS. */
class LiveApiClient(context: Context) {
    private val context = context.applicationContext
    private val config = ServiceConfig(context)
    fun request(method: String, path: String, payload: JSONObject? = null): JSONObject = exchange(method, path, payload).let { if (it.isBlank()) JSONObject() else JSONObject(it) }
    fun requestArray(path: String): JSONArray = JSONArray(exchange("GET", path, null))
    fun submitRegistration(fullName: String, studentNumber: String, proof: Uri): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "API requests must run on a background thread." }
        val token = GoogleSession(context).read()?.idToken() ?: throw ApiException(401, "Sign in with Google first.")
        val resolver = context.contentResolver
        val mime = resolver.getType(proof) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(proof)?.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 5 * 1024 * 1024) { "Proof must be 5 MB or smaller." }; output.write(buffer, 0, count) }
            output.toByteArray()
        } ?: throw IOException("The selected proof document could not be opened.")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("fullName", fullName).addFormDataPart("studentNumber", studentNumber)
            .addFormDataPart("proof", "registration-proof", bytes.toRequestBody(mime.toMediaType())).build()
        val request = Request.Builder().url(config.apiBaseUrl + "/api/v1/account/registration")
            .header("Authorization", "Bearer $token").header("Accept", "application/json").post(body).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, JSONObject(responseText.ifBlank { "{}" }).optString("error", "Registration could not be submitted."))
            return JSONObject(responseText)
        }
    }
    private fun exchange(method: String, path: String, payload: JSONObject?): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "API requests must run on a background thread." }
        require(config.configured) { "Campus API is not configured." }
        require(path.startsWith("/api/v1/") && !path.contains("..") && !path.contains("://")) { "Invalid API path." }
        val accessToken = GoogleSession(context).read()?.idToken()
            ?: throw ApiException(401, "Sign in with Google first.")
        val body = payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(config.apiBaseUrl + path)
            .header("Authorization", "Bearer $accessToken").header("Accept", "application/json")
            .method(method.uppercase(), if (method.uppercase() in listOf("POST", "PUT", "PATCH")) body ?: "{}".toRequestBody("application/json".toMediaType()) else body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                400 -> "The campus API rejected these details. Check your request."
                401 -> "Your Google session expired. Sign in again."
                403 -> runCatching { JSONObject(text).optString("error") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "This Google account is not authorized."
                404 -> "The requested campus item is unavailable."
                409 -> "This item changed. Refresh before trying again."
                else -> "The campus API is unavailable (HTTP ${response.code}). Try again later."
            })
            return text
        }
    }
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
