package com.example.campus.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** Exercises SQLite and transaction boundaries using a unique test-only database directory. */
@RunWith(AndroidJUnit4::class)
class CampusRepositoryTest {
    private lateinit var storage: IsolatedDatabaseContext
    private lateinit var repo: CampusRepository

    @Before fun setUp() {
        storage=IsolatedDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext)
        repo=CampusRepository(storage)
    }
    @After fun tearDown() {
        repo.close()
        storage.root.deleteRecursively()
    }

    @Test fun invalidBuildingsAreRejectedAndCreatedDeliverySurvivesReopening() {
        val count=repo.deliveries().size
        rejects { repo.createDelivery("Textbook","library","library",1.0,20.0) }
        assertEquals(count,repo.deliveries().size)
        val created=repo.createDelivery("Biology textbook","engineering","library",0.8,22.0)
        reopen()
        val saved=repo.delivery(created.id)
        assertEquals("Biology textbook",saved.description)
        assertEquals("ACTIVE",saved.status)
        assertEquals(created.handoffCode,saved.handoffCode)
        assertEquals(0.8,saved.weightKg,0.0)
    }

    @Test fun handoffRejectsWrongCodeAndCreditsOnlyOneCompletedDelivery() {
        val available=repo.deliveries().first { !it.isMine && it.status=="ACTIVE" }
        repo.acceptDelivery(available.id)
        assertEquals("me",repo.delivery(available.id).courierId)
        repo.markPickedUp(available.id)
        rejects { repo.completeDelivery(available.id,"000000") }
        assertEquals("IN_TRANSIT",repo.delivery(available.id).status)
        assertEquals(0.0,repo.profile.ecoScore,0.0)
        assertTrue(repo.pendingTransactions().isEmpty())
        val completed=repo.completeDelivery(available.id,available.handoffCode)
        val expected=CampusRules.deliveryCo2(available.distanceKm)
        assertEquals("COMPLETED",completed.status)
        assertEquals(expected,repo.profile.ecoScore,0.0001)
        assertEquals(1,repo.profile.completedDeliveries)
        assertEquals(1,repo.activities().size)
        assertEquals("LOCAL_ONLY",repo.pendingTransactions().single().syncStatus)
        rejects { repo.completeDelivery(available.id,available.handoffCode) }
        reopen()
        assertEquals(expected,repo.profile.ecoScore,0.0001)
        assertEquals(1,repo.profile.completedDeliveries)
        assertEquals(1,repo.pendingTransactions().size)
    }

    @Test fun routeBookingReservesCapacityAndCancellationRestoresIt() {
        val item=repo.createDelivery("Lab notebook","residence","library",0.5,15.0)
        val route=repo.matches(item.id).first()
        val booked=repo.bookCourier(item.id,route.id)
        assertEquals("MATCHED",booked.status)
        assertEquals(route.id,booked.matchedRouteId)
        assertEquals(route.capacityKg-0.5,repo.routes().first { it.id==route.id }.capacityKg,0.0001)
        rejects { repo.bookCourier(item.id,route.id) }
        repo.cancelDelivery(item.id)
        assertEquals(route.capacityKg,repo.routes().first { it.id==route.id }.capacityKg,0.0001)
        assertEquals(0.0,repo.profile.ecoScore,0.0)
    }

    @Test fun seatBookingNeverOverbooksAndEcoWaitsForRideCompletion() {
        val ride=repo.rides().first { it.availableSeats==1 }
        val booked=repo.bookSeat(ride.id)
        assertEquals(0,booked.availableSeats)
        assertEquals("FULL",booked.status)
        assertTrue(booked.bookedByMe)
        assertEquals(0.0,repo.profile.ecoScore,0.0)
        rejects { repo.bookSeat(ride.id) }
        assertEquals(0,repo.rides().first { it.id==ride.id }.availableSeats)
        repo.cancelSeat(ride.id)
        assertEquals(1,repo.rides().first { it.id==ride.id }.availableSeats)
        repo.bookSeat(ride.id)
        repo.completeRide(ride.id)
        val expected=CampusRules.rideCo2(ride.distanceKm)
        assertEquals(expected,repo.profile.ecoScore,0.0001)
        rejects { repo.completeRide(ride.id) }
        assertEquals(expected,repo.profile.ecoScore,0.0001)
    }

    @Test fun marketplaceReservationDoesNotCreditEcoUntilCollection() {
        val listing=repo.listings().first { it.category=="BOOKS" }
        val reserved=repo.purchaseListing(listing.id)
        assertEquals("PENDING",reserved.status)
        assertTrue(reserved.purchasedByMe)
        assertEquals(0.0,repo.profile.ecoScore,0.0)
        assertTrue(repo.activities().isEmpty())
        rejects { repo.purchaseListing(listing.id) }
        val collected=repo.completePurchase(listing.id)
        assertEquals("SOLD",collected.status)
        val expected=CampusRules.marketplaceCo2(listing.category)
        assertEquals(expected,repo.profile.ecoScore,0.0)
        rejects { repo.completePurchase(listing.id) }
        reopen()
        assertEquals(expected,repo.profile.ecoScore,0.0)
        assertEquals(1,repo.activities().size)
    }

    @Test fun messagesProfileAndPreferencesPersistAcrossReopening() {
        val item=repo.createDelivery("Lecture notes","residence","library",0.2,12.0)
        rejects { repo.sendMessage(item.id,"Hello") }
        repo.bookCourier(item.id,repo.matches(item.id).first().id)
        repo.sendMessage(item.id,"Meet by the library entrance.")
        repo.updateProfile("Nandi Mokoena","Books, bikes and good company.","humanities")
        repo.updatePreferences(Preferences(language="zu",biometricLock=true,notifications=false,darkMode=true))
        reopen()
        assertTrue(repo.messages(item.id).any { it.senderId=="me" && it.text=="Meet by the library entrance." })
        assertEquals("Nandi Mokoena",repo.profile.name)
        assertEquals("humanities",repo.profile.buildingId)
        assertEquals(Preferences("zu",true,false,true),repo.preferences)
        assertTrue(repo.profile.isDemo)
    }

    @Test fun dateSearchMatchesCalendarDayInPositiveTimezone() {
        val previous=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Africa/Johannesburg"))
            val departure=Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH,2)
                set(Calendar.HOUR_OF_DAY,13);set(Calendar.MINUTE,30);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
            }
            val selectedDay=(departure.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0)
            }.timeInMillis/86_400_000
            val ride=repo.createRide("Hatfield","Brooklyn",departure.timeInMillis,2,"CAR",20.0)
            assertTrue(repo.searchRides("Hatfield","Brooklyn",selectedDay).any { it.id==ride.id })
            assertFalse(repo.searchRides("Hatfield","Brooklyn",selectedDay+1).any { it.id==ride.id })
        } finally { TimeZone.setDefault(previous) }
    }

    private fun reopen() { repo.close();repo=CampusRepository(storage) }
    private fun rejects(block: () -> Unit) {
        try { block();fail("Expected invalid operation to be rejected") }
        catch(expected: IllegalArgumentException) { assertFalse(expected.message.isNullOrBlank()) }
    }

    /** applicationContext stays wrapped so SQLiteOpenHelper cannot escape into the user's DB. */
    private class IsolatedDatabaseContext(base: Context): ContextWrapper(base) {
        val root=File(base.cacheDir,"repository-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root,name)
        override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
        override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }
}
