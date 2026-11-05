package com.example.campus.services

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceInfrastructureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun handoffQueuePreservesUnsentRowsAndDeduplicatesTransactionIds() {
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).allowMainThreadQueries().build()
        try {
            val first = OfflineHandoff().apply { localTransactionId = "test-retry-id"; deliveryId = "delivery-1"; scannedQrHash = "handoff-1"; courierId = "courier-1"; completedAt = 123456789L }
            database.handoffs().insert(first)
            database.handoffs().insert(first)
            assertEquals(1, database.handoffs().pendingCount())
            assertEquals("handoff-1", database.handoffs().pending().single().scannedQrHash)
            database.handoffs().update(first.localTransactionId, "SYNCED", "")
            assertEquals(0, database.handoffs().pendingCount())
        } finally { database.close() }
    }

    @Test fun snapshotCacheSeparatesDifferentUniversityAccounts() {
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).allowMainThreadQueries().build()
        try {
            database.snapshots().put(CachedSnapshot().apply { owner = "api|account-one"; path = "/api/v1/profile"; payload = "first profile"; updatedAt = 1L })
            database.snapshots().put(CachedSnapshot().apply { owner = "api|account-two"; path = "/api/v1/profile"; payload = "second profile"; updatedAt = 2L })
            assertEquals("first profile", database.snapshots().get("api|account-one", "/api/v1/profile").payload)
            assertEquals("second profile", database.snapshots().get("api|account-two", "/api/v1/profile").payload)
            assertNull(database.snapshots().get("api|other-account", "/api/v1/profile"))
        } finally { database.close() }
    }

    @Test fun serviceConfigurationRejectsUnencryptedEndpoints() {
        try {
            ServiceConfig(context).configure("http://localhost:5000")
            fail("An unencrypted API must not be accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTPS"))
        }
    }
}
