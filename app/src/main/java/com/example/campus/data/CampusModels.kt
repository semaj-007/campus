package com.example.campus.data

import kotlin.math.*

/** All records in the installed demo live on this device; none imply campus verification. */
data class Profile(
    val id: String = "me", val name: String = "Alex Morgan", val email: String = "Demo profile • on this device",
    val bio: String = "Taking the greener way across campus.", val buildingId: String = "library",
    val avatarUri: String = "", val ecoScore: Double = 0.0, val completedDeliveries: Int = 0,
    val rating: Double = 0.0, val ratingCount: Int = 0,
) { val isDemo: Boolean get() = true }
data class Preferences(val language: String = "en", val biometricLock: Boolean = false, val notifications: Boolean = true, val darkMode: Boolean = false)
data class Building(val id: String, val name: String, val shortName: String, val latitude: Double, val longitude: Double) {
    override fun toString() = name
}
object CampusBuildings {
    val all = listOf(
        Building("library", "Merensky Library", "Library", -25.7546, 28.2318),
        Building("engineering", "Engineering Block B", "Engineering", -25.7558, 28.2304),
        Building("student-centre", "Student Centre", "Student Centre", -25.7537, 28.2307),
        Building("humanities", "Humanities Building", "Humanities", -25.7551, 28.2336),
        Building("residence", "Student Residences", "Residences", -25.7511, 28.2352),
        Building("sports", "Sports Centre", "Sports Centre", -25.7499, 28.2320),
    )
    fun get(id: String): Building = all.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Choose a valid campus building.")
    fun name(id: String) = all.firstOrNull { it.id == id }?.name ?: id
}

data class Delivery(
    val id: String, val description: String, val pickupId: String, val dropoffId: String,
    val weightKg: Double, val reward: Double, val windowStart: Long, val windowEnd: Long,
    val requesterId: String = "me", val requesterName: String = "You", val courierId: String = "",
    val courierName: String = "", val status: String = "ACTIVE", val handoffCode: String,
    val distanceKm: Double, val createdAt: Long, val completedAt: Long = 0, val matchedRouteId: String = "",
) {
    val isMine get() = requesterId == "me"
    val isMyJob get() = courierId == "me"
    val statusLabel get() = when (status) { "ACTIVE" -> "Awaiting courier"; "MATCHED" -> "Courier assigned"; "IN_TRANSIT" -> "On the way"; "COMPLETED" -> "Delivered"; "CANCELLED" -> "Cancelled"; else -> status }
    val weightCategory get() = when { weightKg <= 1 -> "Small · Book"; weightKg <= 5 -> "Medium · Bag"; else -> "Large · Box" }
}
data class RouteOffer(
    val id: String, val startId: String, val endId: String, val capacityKg: Double,
    val windowStart: Long, val windowEnd: Long, val courierId: String, val courierName: String,
    val transport: String = "Walking", val rating: Double = 4.8,
)
data class RideOffer(
    val id: String, val origin: String, val destination: String, val departure: Long,
    val availableSeats: Int, val vehicle: String, val price: Double, val driverId: String,
    val driverName: String, val bookedByMe: Boolean = false, val distanceKm: Double = 10.0,
    val status: String = "ACTIVE",
)
data class Listing(
    val id: String, val title: String, val description: String, val price: Double,
    val category: String, val condition: String, val sellerId: String, val sellerName: String,
    val status: String = "ACTIVE", val purchasedByMe: Boolean = false,
)
data class Message(val id: String, val deliveryId: String, val senderId: String, val senderName: String, val text: String, val sentAt: Long)
data class EcoActivity(val id: String, val title: String, val detail: String, val co2Kg: Double, val type: String, val createdAt: Long, val referenceId: String)
data class LocalTransaction(val id: String, val deliveryId: String, val scannedCode: String, val completedAt: Long, val syncStatus: String = "LOCAL_ONLY")

object CampusRules {
    fun distanceKm(a: Building, b: Building): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lng / 2).pow(2)
        return 6371 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
    fun deliveryCo2(distanceKm: Double) = (distanceKm.coerceAtLeast(0.0) * 0.171 * 1000).roundToInt() / 1000.0
    fun rideCo2(distanceKm: Double) = (distanceKm.coerceAtLeast(0.0) * 0.0855 * 1000).roundToInt() / 1000.0
    fun marketplaceCo2(category: String): Double = when (category.uppercase()) { "BOOKS" -> 2.0; "ELECTRONICS" -> 10.0; "CLOTHING" -> 3.0; "FURNITURE" -> 8.0; else -> 1.0 }
    fun validateDelivery(description: String, pickupId: String, dropoffId: String, weightKg: Double, reward: Double, start: Long, end: Long) {
        require(description.trim().length in 3..200) { "Describe your item in 3–200 characters." }
        CampusBuildings.get(pickupId); CampusBuildings.get(dropoffId)
        require(pickupId != dropoffId) { "Choose different pickup and drop-off buildings." }
        require(weightKg.isFinite() && weightKg > 0 && weightKg <= 30) { "Weight must be between 0 and 30 kg." }
        require(reward.isFinite() && reward in 0.0..1000.0) { "The tip must be between R0 and R1,000." }
        require(start < end) { "The delivery window must end after it starts." }
    }
    fun routeMatches(delivery: Delivery, route: RouteOffer): Boolean {
        if (delivery.status != "ACTIVE" || route.courierId == delivery.requesterId || route.capacityKg < delivery.weightKg) return false
        if (max(delivery.windowStart, route.windowStart) >= min(delivery.windowEnd, route.windowEnd)) return false
        val start = CampusBuildings.get(route.startId); val end = CampusBuildings.get(route.endId)
        val pickup = CampusBuildings.get(delivery.pickupId); val drop = CampusBuildings.get(delivery.dropoffId)
        return distanceToSegmentKm(pickup, start, end) <= 1.0 && distanceToSegmentKm(drop, start, end) <= 1.0 && projection(pickup, start, end) <= projection(drop, start, end) + 0.05
    }
    private fun projection(p: Building, a: Building, b: Building): Double {
        val dx = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude)); val dy = b.latitude - a.latitude
        val px = (p.longitude - a.longitude) * cos(Math.toRadians(a.latitude)); val py = p.latitude - a.latitude
        return if (dx * dx + dy * dy == 0.0) 0.0 else (px * dx + py * dy) / (dx * dx + dy * dy)
    }
    private fun distanceToSegmentKm(p: Building, a: Building, b: Building): Double {
        val t = projection(p, a, b).coerceIn(0.0, 1.0)
        return distanceKm(p, Building("point", "", "", a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude)))
    }
}
