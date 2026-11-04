package com.example.campus.services

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.util.concurrent.Executors

/** Register before the Activity reaches STARTED. AppAuth handles state, nonce, PKCE and token exchange. */
class UniversitySignIn(private val activity: FragmentActivity) {
    private val config = ServiceConfig(activity)
    private val service = AuthorizationService(activity)
    private val pending = activity.getSharedPreferences("campus_pending_auth", android.content.Context.MODE_PRIVATE)
    private var listener: ((Boolean, String) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = result.data
        if (intent == null) listener?.invoke(false, "Sign-in was cancelled.") else complete(intent)
    }
    fun start(onResult: (Boolean, String) -> Unit) {
        listener = onResult
        if (!config.configured) {
            onResult(false, "Emeris sign-in needs the university's Microsoft tenant, public client ID, verified email domain and HTTPS API configuration.")
            return
        }
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse("https://login.microsoftonline.com/${config.tenantId}/v2.0")) { discovered, error ->
            activity.runOnUiThread {
                if (discovered == null || error != null) onResult(false, "Unable to load the university sign-in service. Check the connection.")
                else {
                    val request = AuthorizationRequest.Builder(discovered, config.clientId, ResponseTypeValues.CODE, config.redirectUri)
                        .setScope("openid profile email offline_access api://${config.clientId}/access_as_user")
                        .build()
                    pending.edit().putString("state", request.state).putString("tokenEndpoint", discovered.tokenEndpoint.toString()).apply()
                    try { launcher.launch(service.getAuthorizationRequestIntent(request)) } catch (_: Exception) { onResult(false, "Install a browser to use university sign-in.") }
                }
            }
        }
    }
    fun complete(intent: Intent, onResult: (Boolean, String) -> Unit) {
        listener = onResult
        complete(intent)
    }
    fun complete(intent: Intent) {
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        if (response == null || error != null) { listener?.invoke(false, error?.errorDescription ?: "University sign-in was cancelled."); return }
        val expectedState = pending.getString("state", null)
        val expectedEndpoint = pending.getString("tokenEndpoint", null)
        pending.edit().clear().apply()
        if (expectedState == null || response.request.state != expectedState || response.state != expectedState ||
            response.request.clientId != config.clientId || response.request.redirectUri != config.redirectUri ||
            response.request.configuration.tokenEndpoint.toString() != expectedEndpoint) {
            listener?.invoke(false, "This sign-in response does not match the university sign-in request. Please try again.")
            return
        }
        val state = AuthState(response, error)
        service.performTokenRequest(response.createTokenExchangeRequest()) { token, tokenError ->
            if (token == null || tokenError != null) activity.runOnUiThread { listener?.invoke(false, "The university could not complete sign-in.") }
            else {
                state.update(token, tokenError)
                SecureSession(activity).save(state)
                // Server, not unsigned client-side token claims, decides whether this is a trusted campus member.
                verifier.execute {
                    try {
                        val profile = LiveApiClient(activity).request("GET", "/api/v1/profile")
                        LiveSnapshotStore(activity.applicationContext).bind(profile)
                        OfflineQueue.schedule(activity.applicationContext)
                        PushSupport.initialize(activity.applicationContext)
                        activity.runOnUiThread { listener?.invoke(true, "University account verified.") }
                    } catch (_: Exception) {
                        SecureSession(activity).clear()
                        activity.runOnUiThread { listener?.invoke(false, "Sign-in succeeded, but the campus API could not verify this account. Check your university and API configuration.") }
                    }
                }
            }
        }
    }
    fun signOut() { SecureSession(activity).clear(); LiveSnapshotStore(activity.applicationContext).unbind() }
    fun dispose() = service.dispose()
    companion object { private val verifier = Executors.newSingleThreadExecutor() }
}
