package com.example.campus.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.campus.MainActivity
import com.example.campus.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.Executors

/** Initialized only when a real Firebase project has been supplied; no placeholder project is used. */
object PushSupport {
    private val executor = Executors.newSingleThreadExecutor()
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("campus_push", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }
    fun initialize(context: Context): Boolean {
        val config = ServiceConfig(context)
        val appId = config.firebaseValue("app")
        if (appId.isBlank()) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApplicationId(appId).setApiKey(config.firebaseValue("key")).setProjectId(config.firebaseValue("project"))
                .setGcmSenderId(config.firebaseValue("sender")).build())
            FirebaseMessaging.getInstance().token.addOnSuccessListener { registerToken(context, it) }
            true
        } catch (_: Exception) { false }
    }
    fun registerToken(context: Context, token: String) {
        if (!ServiceConfig(context).configured || SecureSession(context).read()?.isAuthorized != true) return
        executor.execute {
            try { LiveApiClient(context).request("POST", "/api/v1/profile/device-token", JSONObject().put("token", token).put("platform", "android")) }
            catch (_: Exception) { /* Retry when sign-in/initialization is performed again. */ }
        }
    }
}

class CampusMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushSupport.registerToken(this, token) }
    override fun onMessageReceived(message: RemoteMessage) {
        if (SecureSession(this).read()?.isAuthorized != true) return
        val prefs = getSharedPreferences("campus_push", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("delivery_updates", "Delivery updates", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "delivery_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(message.notification?.title ?: message.data["title"] ?: "CampusRelay update")
            .setContentText(message.notification?.body ?: message.data["body"] ?: "A campus delivery has an update.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(this).notify(message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }
}
