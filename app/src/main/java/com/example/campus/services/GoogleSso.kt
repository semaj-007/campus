package com.example.campus.services

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import java.util.concurrent.Executors
import android.net.Uri

/** Google account selection followed by Firebase Authentication. */
class GoogleSso(private val activity: FragmentActivity) {
    private var listener: ((Boolean, String) -> Unit)? = null
    private var registration: Registration? = null
    private var client: com.google.android.gms.auth.api.signin.GoogleSignInClient? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = listener ?: return@registerForActivityResult
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(com.google.android.gms.common.api.ApiException::class.java)
            val token = account.idToken ?: throw IllegalStateException("Firebase did not return a Google ID token.")
            FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                .addOnSuccessListener { if (registration == null) verifyWithApi(callback) else submitRegistration(callback) }
                .addOnFailureListener { callback(false, "Google sign-in could not be completed. ${it.localizedMessage.orEmpty()}") }
        } catch (error: com.google.android.gms.common.api.ApiException) {
            val message = when (error.statusCode) {
                10 -> "Google Sign-In configuration does not match this app (error 10). Add this APK's SHA-1 and SHA-256 fingerprints to the Firebase Android app com.example.campus, then download google-services.json again."
                7 -> "Google Sign-In could not reach Google (error 7). Check the internet connection and try again."
                12501 -> "Google Sign-In was cancelled (error 12501)."
                12500 -> "Google Sign-In failed (error 12500). Confirm that the Google provider is enabled in Firebase Authentication."
                else -> "Google Sign-In could not be completed (error ${error.statusCode})."
            }
            callback(false, message)
        } catch (error: Exception) {
            callback(false, error.message ?: "Google Sign-In could not be completed.")
        }
    }

    fun start(onResult: (Boolean, String) -> Unit) {
        registration = null
        launch(onResult)
    }

    fun createAccount(fullName: String, studentNumber: String, proof: Uri, onResult: (Boolean, String) -> Unit) {
        registration = Registration(fullName, studentNumber, proof)
        launch(onResult)
    }

    private fun launch(onResult: (Boolean, String) -> Unit) {
        listener = onResult
        if (!ServiceConfig(activity).configured) {
            onResult(false, "Set the CampusRelay HTTPS API URL before signing in with Google.")
            return
        }
        if (!PushSupport.initialize(activity)) {
            onResult(false, "Firebase is not configured. Add the google-services.json file for com.example.campus and sync Gradle.")
            return
        }
        val webClientId = webClientId()
        if (webClientId == null) {
            onResult(false, "Google Sign-In is missing its Web client ID. Enable Google in Firebase Authentication, then download google-services.json again.")
            return
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId).requestEmail().build()
        client = GoogleSignIn.getClient(activity, options)
        launcher.launch(client!!.signInIntent)
    }

    private fun submitRegistration(callback: (Boolean, String) -> Unit) {
        val details = registration ?: return
        verifier.execute {
            try {
                LiveApiClient(activity).submitRegistration(details.fullName, details.studentNumber, details.proof)
                activity.runOnUiThread { callback(true, "Your proof was submitted. The institution must approve your account before you can sign in.") }
            } catch (error: Exception) {
                FirebaseAuth.getInstance().signOut()
                activity.runOnUiThread { callback(false, error.message ?: "Registration could not be submitted.") }
            }
        }
    }

    private fun verifyWithApi(callback: (Boolean, String) -> Unit) {
        verifier.execute {
            try {
                val profile = LiveApiClient(activity).request("GET", "/api/v1/profile")
                LiveSnapshotStore(activity.applicationContext).bind(profile)
                OfflineQueue.schedule(activity.applicationContext)
                PushSupport.initialize(activity.applicationContext)
                activity.runOnUiThread { callback(true, "Google account verified.") }
            } catch (error: Exception) {
                FirebaseAuth.getInstance().signOut()
                activity.runOnUiThread { callback(false, "Google sign-in succeeded, but the CampusRelay API could not verify it. ${error.message.orEmpty()}") }
            }
        }
    }

    fun signOut() {
        try { FirebaseAuth.getInstance().signOut() } catch (_: IllegalStateException) { }
        val webClientId = webClientId()
        if (client == null && webClientId != null) {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId).requestEmail().build()
            client = GoogleSignIn.getClient(activity, options)
        }
        client?.signOut()
        LiveSnapshotStore(activity.applicationContext).unbind()
        androidx.core.app.NotificationManagerCompat.from(activity).cancelAll()
    }
    private fun webClientId(): String? {
        val resourceId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        return if (resourceId == 0) null else activity.getString(resourceId).takeIf { it.isNotBlank() }
    }
    companion object { private val verifier = Executors.newSingleThreadExecutor() }
    private data class Registration(val fullName: String, val studentNumber: String, val proof: Uri)
}
