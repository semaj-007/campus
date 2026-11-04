package com.example.campus.services

import android.content.Context
import android.net.Uri

/** Public identifiers only. No client secret belongs in an installed mobile app. */
class ServiceConfig(context: Context) {
    private val prefsContext=context.applicationContext
    private val prefs = context.getSharedPreferences("campus_services", Context.MODE_PRIVATE)
    val tenantId get() = prefs.getString("tenant", "").orEmpty()
    val clientId get() = prefs.getString("client", "").orEmpty()
    val apiBaseUrl get() = prefs.getString("api", "").orEmpty()
    val allowedDomain get() = prefs.getString("domain", "").orEmpty()
    val configured get() = tenantId.isNotBlank() && clientId.isNotBlank() && apiBaseUrl.isNotBlank() && allowedDomain.isNotBlank()
    val redirectUri: Uri get() = Uri.parse("campusrelay://oauth/callback")
    fun configure(tenantId: String, clientId: String, apiBaseUrl: String, allowedDomain: String) {
        require(tenantId.matches(Regex("[a-zA-Z0-9.-]+")) && tenantId !in listOf("common", "organizations", "consumers")) { "Use the university's specific Microsoft tenant ID." }
        require(clientId.matches(Regex("[a-zA-Z0-9-]+"))) { "Enter the registered public client ID." }
        val api = Uri.parse(apiBaseUrl.trim())
        require(api.scheme == "https" && !api.host.isNullOrBlank() && api.userInfo == null && api.query == null && api.fragment == null) { "The API URL must use HTTPS." }
        require(allowedDomain.matches(Regex("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"))) { "Enter the university's verified email domain." }
        if (this.tenantId != tenantId || this.clientId != clientId || this.apiBaseUrl != apiBaseUrl.trim().trimEnd('/') || this.allowedDomain != allowedDomain.lowercase()) {
            SecureSession(prefsContext).clear()
            LiveSnapshotStore(prefsContext).unbind()
        }
        prefs.edit().putString("tenant", tenantId).putString("client", clientId).putString("api", apiBaseUrl.trim().trimEnd('/')).putString("domain", allowedDomain.lowercase()).apply()
    }
    fun configureFirebase(applicationId: String, apiKey: String, projectId: String, senderId: String) {
        require(listOf(applicationId, apiKey, projectId, senderId).all { it.isNotBlank() }) { "All Firebase public application fields are required." }
        prefs.edit().putString("firebase_app", applicationId).putString("firebase_key", apiKey).putString("firebase_project", projectId).putString("firebase_sender", senderId).apply()
    }
    internal fun firebaseValue(key: String) = prefs.getString("firebase_$key", "").orEmpty()
}
