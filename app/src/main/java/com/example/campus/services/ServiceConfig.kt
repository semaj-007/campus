package com.example.campus.services

import android.content.Context
import android.net.Uri

/** The API address is the only deployment setting entered in-app. Firebase comes from google-services.json. */
class ServiceConfig(context: Context) {
    private val prefsContext=context.applicationContext
    private val prefs = context.getSharedPreferences("campus_services", Context.MODE_PRIVATE)
    val apiBaseUrl get() = prefs.getString("api", "").orEmpty()
    val configured get() = apiBaseUrl.isNotBlank()
    fun configure(apiBaseUrl: String) {
        val api = Uri.parse(apiBaseUrl.trim())
        require(api.scheme == "https" && !api.host.isNullOrBlank() && api.userInfo == null && api.query == null && api.fragment == null) { "The API URL must use HTTPS." }
        if (this.apiBaseUrl != apiBaseUrl.trim().trimEnd('/')) {
            GoogleSession(prefsContext).clear()
            LiveSnapshotStore(prefsContext).unbind()
        }
        prefs.edit().remove("tenant").remove("client").remove("domain")
            .putString("api", apiBaseUrl.trim().trimEnd('/')).apply()
    }
    fun configureFirebase(applicationId: String, apiKey: String, projectId: String, senderId: String) {
        require(listOf(applicationId, apiKey, projectId, senderId).all { it.isNotBlank() }) { "All Firebase public application fields are required." }
        prefs.edit().putString("firebase_app", applicationId).putString("firebase_key", apiKey).putString("firebase_project", projectId).putString("firebase_sender", senderId).apply()
    }
    internal fun firebaseValue(key: String) = prefs.getString("firebase_$key", "").orEmpty()
}
