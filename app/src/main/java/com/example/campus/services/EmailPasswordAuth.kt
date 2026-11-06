package com.example.campus.services

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Firebase email/password accounts require institutional proof before API access. */
class EmailPasswordAuth(private val activity: FragmentActivity) {
    fun signIn(email: String, password: String, callback: (Boolean, String) -> Unit) {
        if (!ready(callback)) return
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { verify(callback) }
            .addOnFailureListener { callback(false, it.localizedMessage ?: "Email sign-in failed.") }
    }

    fun createAccount(fullName: String, studentNumber: String, email: String, password: String, proof: Uri, callback: (Boolean, String) -> Unit) {
        if (!ready(callback)) return
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { result ->
                executor.execute {
                    try {
                        LiveApiClient(activity).submitRegistration(fullName, studentNumber, proof)
                        FirebaseAuth.getInstance().signOut()
                        activity.runOnUiThread { callback(true, "Account created. Your proof was submitted for institutional review.") }
                    } catch (error: Exception) {
                        runCatching { result.user?.let { Tasks.await(it.delete(), 30, TimeUnit.SECONDS) } }
                        FirebaseAuth.getInstance().signOut()
                        activity.runOnUiThread { callback(false, error.message ?: "Registration could not be submitted.") }
                    }
                }
            }
            .addOnFailureListener { callback(false, it.localizedMessage ?: "The account could not be created.") }
    }

    private fun ready(callback: (Boolean, String) -> Unit): Boolean {
        if (!ServiceConfig(activity).configured) { callback(false, "Set the CampusRelay HTTPS API URL first."); return false }
        if (!PushSupport.initialize(activity)) { callback(false, "Firebase is not configured. Add google-services.json and rebuild the app."); return false }
        return true
    }

    private fun verify(callback: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                val profile = LiveApiClient(activity).request("GET", "/api/v1/profile")
                LiveSnapshotStore(activity.applicationContext).bind(profile)
                OfflineQueue.schedule(activity.applicationContext)
                PushSupport.initialize(activity.applicationContext)
                activity.runOnUiThread { callback(true, "Account verified.") }
            } catch (error: Exception) {
                FirebaseAuth.getInstance().signOut()
                activity.runOnUiThread { callback(false, error.message ?: "This account cannot sign in yet.") }
            }
        }
    }

    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
