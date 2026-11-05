package com.example.campus.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object OfflineQueue {
    private val executor = Executors.newSingleThreadExecutor()
    /** Queues only live API jobs. Demo deliveries must never be sent to the remote campus service. */
    fun enqueue(context: Context, deliveryId: String, qrCode: String, courierId: String, onSaved: (String) -> Unit = {}) {
        require(deliveryId.isNotBlank() && qrCode.isNotBlank() && courierId.isNotBlank()) { "A delivery, handoff code and courier are required." }
        require(ServiceConfig(context).configured && GoogleSession(context).read()?.isAuthorized == true) { "Sign in with Google before queuing a live handoff." }
        require(courierId == LiveSnapshotStore(context).accountId()) { "This handoff belongs to a different university account." }
        val id = UUID.randomUUID().toString()
        executor.execute {
            OfflineDatabase.get(context).handoffs().insert(OfflineHandoff().apply {
                localTransactionId = id; this.deliveryId = deliveryId; scannedQrHash = qrCode; this.courierId = courierId; completedAt = System.currentTimeMillis()
            })
            schedule(context)
            onSaved(id)
        }
    }
    /** Call off the main thread. */
    fun pendingCount(context: Context) = OfflineDatabase.get(context).handoffs().pendingCountForCourier(LiveSnapshotStore(context).accountId())
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<HandoffSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("campus-handoff-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
