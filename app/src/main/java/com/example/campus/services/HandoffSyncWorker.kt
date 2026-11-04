package com.example.campus.services

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HandoffSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!ServiceConfig(applicationContext).configured) return Result.failure()
        val dao = OfflineDatabase.get(applicationContext).handoffs()
        val pending = dao.pending()
        if (pending.isEmpty()) return Result.success()
        return try {
            // Single-item batches disambiguate legacy sync responses that expose only syncedCount.
            val currentId=LiveApiClient(applicationContext).request("GET", "/api/v1/profile").getString("id")
            for (handoff in pending.filter { it.courierId == currentId }) {
                val completed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(handoff.completedAt))
                val payload = JSONObject().put("offlineTransactions", JSONArray().put(JSONObject()
                    .put("localTransactionId", handoff.localTransactionId).put("deliveryId", handoff.deliveryId)
                    .put("scannedQrHash", handoff.scannedQrHash).put("courierId", handoff.courierId).put("completedAt", completed)))
                val response = LiveApiClient(applicationContext).request("POST", "/api/v1/deliveries/sync-offline", payload)
                val syncedIds = response.optJSONArray("syncedTransactionIds")
                val accepted = if (syncedIds != null) (0 until syncedIds.length()).any { syncedIds.optString(it) == handoff.localTransactionId }
                    else response.optInt("syncedCount", 0) == 1 && (response.optJSONArray("failedTransactions")?.length() ?: 0) == 0
                if (accepted) {
                    dao.update(handoff.localTransactionId, "SYNCED", "")
                } else {
                    dao.update(handoff.localTransactionId, "REJECTED", "Campus API rejected this handoff. Review the delivery before submitting again.")
                }
            }
            Result.success()
        } catch (error: ApiException) {
            // Keep authentication failures pending, so signing in can explicitly schedule the queue again.
            if (error.status in listOf(401, 403)) Result.failure() else if (error.status >= 500 || error.status == 429) Result.retry() else Result.failure()
        } catch (_: Exception) { Result.retry() }
    }
}
