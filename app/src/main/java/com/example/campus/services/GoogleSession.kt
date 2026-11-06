package com.example.campus.services

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/** Firebase Authentication owns and refreshes the signed-in account session. */
class GoogleSession(context: Context) {
    private val appContext = context.applicationContext
    private val auth get() = try { FirebaseAuth.getInstance() } catch (_: IllegalStateException) { null }
    val isAuthorized get() = auth?.currentUser != null
    fun read(): GoogleSession? = if (isAuthorized) this else null
    fun idToken(): String {
        val user = auth?.currentUser ?: throw ApiException(401, "Sign in first.")
        return Tasks.await(user.getIdToken(false), 30, TimeUnit.SECONDS).token
            ?: throw ApiException(401, "Your session expired. Sign in again.")
    }
    fun clear() {
        auth?.signOut()
        appContext.getSharedPreferences("campus_live_account", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
