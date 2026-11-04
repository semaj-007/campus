package com.example.campus.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/** Durable, device-local demo repository. It never claims a remote sync or university identity. */
class CampusRepository(context: Context) {
    private val helper = Store(context.applicationContext)
    private val db get() = helper.writableDatabase
    val profile: Profile get() = rows("profile").first().toProfile()
    val preferences: Preferences get() = rows("preferences").first().toPreferences()
    val isDemo: Boolean get() = true

    fun deliveries() = rows("delivery").map { it.toDelivery() }.sortedByDescending { it.createdAt }
    fun delivery(id: String) = deliveries().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Delivery not found.")
    fun routes() = rows("route").map { it.toRoute() }.sortedBy { it.windowStart }
    fun rides() = rows("ride").map { it.toRide() }.sortedBy { it.departure }
    fun listings() = rows("listing").map { it.toListing() }
    fun activities() = rows("activity").map { it.toActivity() }.sortedByDescending { it.createdAt }
    fun pendingTransactions() = rows("transaction").map { LocalTransaction(it.getString("id"),it.getString("deliveryId"),it.getString("scannedCode"),it.getLong("completedAt"),it.getString("syncStatus")) }
    fun messages(deliveryId: String) = rows("message").map { it.toMessage() }.filter { it.deliveryId == deliveryId }.sortedBy { it.sentAt }
    fun matches(deliveryId: String): List<RouteOffer> {
        val item = delivery(deliveryId)
        return routes().filter { CampusRules.routeMatches(item, it) }.sortedByDescending { it.rating }
    }

    @Synchronized
    fun createDelivery(description: String, pickupId: String, dropoffId: String, weightKg: Double, reward: Double,
                       windowStart: Long = System.currentTimeMillis(), windowEnd: Long = System.currentTimeMillis() + 7_200_000): Delivery {
        CampusRules.validateDelivery(description,pickupId,dropoffId,weightKg,reward,windowStart,windowEnd)
        require(windowEnd > System.currentTimeMillis()) { "Choose a delivery window in the future." }
        val item = Delivery(id(),description.trim(),pickupId,dropoffId,weightKg,reward,windowStart,windowEnd,
            requesterName=profile.name,handoffCode=randomCode(),distanceKm=CampusRules.distanceKm(CampusBuildings.get(pickupId),CampusBuildings.get(dropoffId)),createdAt=System.currentTimeMillis())
        save("delivery",item.id,item.json())
        return item
    }
    @Synchronized
    fun acceptDelivery(id: String): Delivery = atomic {
        val item = delivery(id)
        require(!item.isMine) { "Choose a courier for your own delivery using route matches." }
        require(item.status == "ACTIVE") { "This delivery is no longer available." }
        require(item.windowEnd > System.currentTimeMillis()) { "This delivery window has ended." }
        val updated = item.copy(courierId="me",courierName=profile.name,status="MATCHED")
        save("delivery",id,updated.json()); updated
    }
    @Synchronized
    fun markPickedUp(id: String): Delivery = atomic {
        val item = delivery(id)
        require(item.isMine || item.isMyJob) { "This delivery is not assigned to you." }
        require(item.status == "MATCHED") { "Assign a courier before marking this item as collected." }
        // In the demo, the local user can simulate their selected courier's progression.
        val updated=item.copy(status="IN_TRANSIT")
        save("delivery",id,updated.json()); updated
    }
    @Synchronized
    fun completeDelivery(id: String, code: String): Delivery = atomic {
        val item=delivery(id)
        require(item.isMine || item.isMyJob) { "This delivery is not assigned to you." }
        require(item.status == "IN_TRANSIT") { "Collect the package before confirming the handoff." }
        require(code.trim() == item.handoffCode) { "That handoff code does not match. Ask the recipient for their six-digit code." }
        val now=System.currentTimeMillis()
        val updated=item.copy(status="COMPLETED",completedAt=now)
        save("delivery",id,updated.json())
        val transaction=LocalTransaction(id(),id,code.trim(),now)
        save("transaction",transaction.id,obj("id" to transaction.id,"deliveryId" to id,"scannedCode" to code.trim(),"completedAt" to now,"syncStatus" to "LOCAL_ONLY"))
        addEco("Delivery completed",item.description,CampusRules.deliveryCo2(item.distanceKm),"DELIVERY",id)
        val p=profile
        save("profile","me",p.copy(completedDeliveries=p.completedDeliveries+1).json())
        updated
    }
    @Synchronized
    fun cancelDelivery(id: String): Delivery = atomic {
        val item=delivery(id)
        require(item.isMine) { "Only the requester can cancel a delivery." }
        require(item.status in listOf("ACTIVE","MATCHED")) { "This delivery cannot be cancelled now." }
        if (item.matchedRouteId.isNotBlank()) {
            routes().firstOrNull { it.id == item.matchedRouteId }?.let { route ->
                save("route",route.id,route.copy(capacityKg=route.capacityKg+item.weightKg).json())
            }
        }
        val updated=item.copy(status="CANCELLED")
        save("delivery",id,updated.json()); updated
    }
    @Synchronized
    fun createRoute(startId: String,endId: String,capacityKg: Double,windowStart: Long = System.currentTimeMillis(),windowEnd: Long = System.currentTimeMillis()+7_200_000,transport: String="Walking"): RouteOffer {
        CampusBuildings.get(startId); CampusBuildings.get(endId)
        require(startId != endId) { "Your route must end at a different building." }
        require(capacityKg.isFinite() && capacityKg > 0 && capacityKg <= 30) { "Capacity must be between 0 and 30 kg." }
        require(windowStart < windowEnd && windowEnd > System.currentTimeMillis()) { "Choose a valid future availability window." }
        require(transport in listOf("Walking","Cycling")) { "Choose walking or cycling for a campus route." }
        val item=RouteOffer(id(),startId,endId,capacityKg,windowStart,windowEnd,"me",profile.name,transport,0.0)
        save("route",item.id,item.json()); return item
    }
    @Synchronized
    fun bookCourier(deliveryId: String,routeId: String): Delivery = atomic {
        val item=delivery(deliveryId)
        require(item.isMine) { "Only the requester can choose a courier." }
        val route=routes().firstOrNull { it.id == routeId } ?: throw IllegalArgumentException("Route not found.")
        require(CampusRules.routeMatches(item,route) && route.windowEnd > System.currentTimeMillis()) { "This route is no longer a match." }
        val updated=item.copy(courierId=route.courierId,courierName=route.courierName,status="MATCHED",matchedRouteId=route.id)
        save("route",route.id,route.copy(capacityKg=route.capacityKg-item.weightKg).json())
        save("delivery",item.id,updated.json())
        val message=Message(id(),item.id,route.courierId,route.courierName,"Demo route selected. Use the handoff flow to try a campus delivery.",System.currentTimeMillis())
        save("message",message.id,message.json())
        updated
    }
    fun searchRides(origin: String="",destination: String="",dateEpochDay: Long?=null): List<RideOffer> = rides().filter {
        it.origin.contains(origin.trim(),ignoreCase=true) && it.destination.contains(destination.trim(),ignoreCase=true) &&
        (dateEpochDay == null || localDayKey(it.departure) == dateEpochDay) && it.status == "ACTIVE" && it.availableSeats > 0 && it.departure > System.currentTimeMillis()
    }
    @Synchronized
    fun createRide(origin: String,destination: String,departure: Long,seats: Int,vehicle: String,price: Double): RideOffer {
        require(origin.trim().length in 2..100 && destination.trim().length in 2..100) { "Enter an origin and destination with at least two characters." }
        require(!origin.trim().equals(destination.trim(),true)) { "Choose different origin and destination locations." }
        require(departure > System.currentTimeMillis()) { "Choose a future departure time." }
        require(seats in 1..8) { "Available seats must be between 1 and 8." }
        require(vehicle.uppercase() in listOf("CAR","SUV","VAN")) { "Choose a car, SUV or van." }
        require(price.isFinite() && price in 0.0..2000.0) { "Price per seat must be between R0 and R2,000." }
        val item=RideOffer(id(),origin.trim(),destination.trim(),departure,seats,vehicle.uppercase(),price,"me",profile.name)
        save("ride",item.id,item.json()); return item
    }
    @Synchronized
    fun bookSeat(id: String): RideOffer = atomic {
        val item=rides().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Ride not found.")
        require(item.driverId != "me") { "You are the driver of this ride." }
        require(!item.bookedByMe) { "You already have a seat on this ride." }
        require(item.availableSeats > 0 && item.status == "ACTIVE") { "This ride has no available seats." }
        require(item.departure > System.currentTimeMillis()) { "This ride has already departed." }
        val updated=item.copy(availableSeats=item.availableSeats-1,bookedByMe=true,status=if(item.availableSeats==1) "FULL" else "ACTIVE")
        save("ride",id,updated.json()); updated
    }
    @Synchronized
    fun cancelSeat(id: String): RideOffer = atomic {
        val item=rides().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Ride not found.")
        require(item.bookedByMe && item.status != "COMPLETED") { "No cancellable booking found." }
        require(item.departure > System.currentTimeMillis()) { "This ride has already departed." }
        val updated=item.copy(availableSeats=item.availableSeats+1,bookedByMe=false,status="ACTIVE")
        save("ride",id,updated.json()); updated
    }
    @Synchronized
    fun completeRide(id: String): RideOffer = atomic {
        val item=rides().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Ride not found.")
        require(item.bookedByMe && item.status != "COMPLETED") { "Book a seat before completing this demo ride." }
        val updated=item.copy(status="COMPLETED")
        save("ride",id,updated.json())
        addEco("Shared a ride","${item.origin} → ${item.destination}",CampusRules.rideCo2(item.distanceKm),"CARPOOL",id)
        updated
    }
    @Synchronized
    fun createListing(title: String,description: String,price: Double,category: String,condition: String): Listing {
        require(title.trim().length in 3..100) { "Enter a title in 3–100 characters." }
        require(description.trim().length in 3..1000) { "Describe the item in 3–1,000 characters." }
        require(price.isFinite() && price in 0.0..50000.0) { "Price must be between R0 and R50,000." }
        require(category.uppercase() in listOf("BOOKS","FURNITURE","ELECTRONICS","CLOTHING","OTHER")) { "Choose a valid category." }
        require(condition.uppercase() in listOf("NEW","LIKE_NEW","GOOD","FAIR")) { "Choose a valid condition." }
        val item=Listing(id(),title.trim(),description.trim(),price,category.uppercase(),condition.uppercase(),"me",profile.name)
        save("listing",item.id,item.json()); return item
    }
    @Synchronized
    fun purchaseListing(id: String): Listing = atomic {
        val item=listings().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Listing not found.")
        require(item.sellerId != "me") { "You cannot buy your own listing." }
        require(item.status == "ACTIVE") { "This listing is no longer available." }
        val updated=item.copy(status="PENDING",purchasedByMe=true)
        save("listing",id,updated.json())
        updated
    }
    @Synchronized
    fun completePurchase(id: String): Listing = atomic {
        val item=listings().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Listing not found.")
        require(item.purchasedByMe && item.status == "PENDING") { "Reserve this item before confirming collection." }
        val updated=item.copy(status="SOLD")
        save("listing",id,updated.json())
        addEco("Bought pre-loved",item.title,CampusRules.marketplaceCo2(item.category),"MARKETPLACE",id)
        updated
    }
    @Synchronized
    fun sendMessage(deliveryId: String,text: String): Message {
        val item=delivery(deliveryId)
        require(item.isMine || item.isMyJob) { "Join this delivery before starting a conversation." }
        require(item.courierId.isNotBlank()) { "Choose a courier before starting a conversation." }
        require(text.trim().length in 1..1000) { "Messages must contain 1–1,000 characters." }
        val message=Message(id(),deliveryId,"me",profile.name,text.trim(),System.currentTimeMillis())
        save("message",message.id,message.json()); return message
    }
    @Synchronized
    fun updateProfile(name: String,bio: String,buildingId: String,avatarUri: String=profile.avatarUri): Profile {
        require(name.trim().length in 2..80) { "Enter your name in 2–80 characters." }
        require(bio.length <= 300) { "Keep your bio under 300 characters." }
        CampusBuildings.get(buildingId)
        val updated=profile.copy(name=name.trim(),bio=bio.trim(),buildingId=buildingId,avatarUri=avatarUri)
        save("profile","me",updated.json()); return updated
    }
    @Synchronized
    fun updatePreferences(preferences: Preferences) {
        require(preferences.language in listOf("en","zu","af")) { "Choose English, isiZulu or Afrikaans." }
        save("preferences","me",preferences.json())
    }
    private fun addEco(title: String,detail: String,co2: Double,type: String,reference: String) {
        // A reference can be credited once, even if a UI action is retried.
        if (activities().any { it.referenceId==reference }) return
        val activity=EcoActivity(id(),title,detail,co2,type,System.currentTimeMillis(),reference)
        save("activity",activity.id,activity.json())
        val p=profile
        save("profile","me",p.copy(ecoScore=p.ecoScore+co2).json())
    }
    private fun rows(type: String): List<JSONObject> = db.query("records",arrayOf("payload"),"kind=?",arrayOf(type),null,null,"rowid ASC").use { cursor ->
        buildList { while(cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
    }
    private fun save(type: String,id: String,json: JSONObject) {
        db.insertWithOnConflict("records",null,ContentValues().apply { put("kind",type); put("id",id); put("payload",json.toString()) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun <T> atomic(block: () -> T): T {
        val database=db
        database.beginTransaction()
        try { val value=block(); database.setTransactionSuccessful(); return value } finally { database.endTransaction() }
    }
    fun close() = helper.close()

    private class Store(context: Context): SQLiteOpenHelper(context,"campusrelay-demo.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind,id))")
            fun put(kind: String,id: String,json: JSONObject) { db.insertOrThrow("records",null,ContentValues().apply { put("kind",kind);put("id",id);put("payload",json.toString()) }) }
            put("profile","me",Profile().json()); put("preferences","me",Preferences().json())
            val now=System.currentTimeMillis()
            val deliveries=listOf(
                Delivery("demo-textbook","Calculus textbook & notes","engineering","library",0.8,25.0,now,now+7_200_000,"demo-thabo","Thabo M.",handoffCode="482619",distanceKm=0.19,createdAt=now-600_000),
                Delivery("demo-posters","Society event posters","student-centre","humanities",1.5,18.0,now,now+10_800_000,"demo-lerato","Lerato K.",handoffCode="735204",distanceKm=0.33,createdAt=now-1_200_000),
                Delivery("demo-charger","Laptop charger","residence","library",0.4,30.0,now,now+7_200_000,"me","You",handoffCode="194827",distanceKm=0.52,createdAt=now-300_000),
            )
            deliveries.forEach { put("delivery",it.id,it.json()) }
            listOf(
                RouteOffer("demo-route-1","residence","engineering",3.0,now,now+7_200_000,"demo-sipho","Sipho D.","Walking",4.9),
                RouteOffer("demo-route-2","sports","library",6.0,now,now+10_800_000,"demo-amy","Amy V.","Cycling",4.8),
                RouteOffer("demo-route-3","engineering","humanities",2.0,now,now+7_200_000,"demo-neo","Neo S.","Walking",4.7),
            ).forEach { put("route",it.id,it.json()) }
            listOf(
                RideOffer("demo-ride-1","Hatfield Campus","Menlyn",now+3_600_000,3,"CAR",25.0,"demo-naledi","Naledi P.",distanceKm=8.0),
                RideOffer("demo-ride-2","Hatfield Campus","Centurion",now+7_200_000,2,"SUV",40.0,"demo-david","David M.",distanceKm=18.0),
                RideOffer("demo-ride-3","Brooklyn","Hatfield Campus",now+90_000_000,1,"CAR",15.0,"demo-zola","Zola N.",distanceKm=4.0),
            ).forEach { put("ride",it.id,it.json()) }
            listOf(
                Listing("demo-book","Engineering Mathematics","A well-loved first-year essential. All pages intact, a few helpful pencil notes.",120.0,"BOOKS","GOOD","demo-lerato","Lerato K."),
                Listing("demo-lamp","Study desk lamp","Adjustable LED lamp. Warm and cool light, USB powered.",85.0,"ELECTRONICS","LIKE_NEW","demo-naledi","Naledi P."),
                Listing("demo-hoodie","Campus hoodie","Size medium. Freshly washed and ready for a new home.",160.0,"CLOTHING","GOOD","demo-thabo","Thabo M."),
                Listing("demo-chair","Desk chair","Comfortable study chair. Collect near the residences.",250.0,"FURNITURE","FAIR","demo-sipho","Sipho D."),
            ).forEach { put("listing",it.id,it.json()) }
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) = Unit
    }
}

/** Mirrors the date picker's local-midnight day key, including its timezone and DST offset. */
private fun localDayKey(time: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis=time
    set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0)
    set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0)
}.timeInMillis / 86_400_000
private fun id()=UUID.randomUUID().toString()
private fun randomCode()=(java.security.SecureRandom().nextInt(900000)+100000).toString()
private fun obj(vararg entries: Pair<String,Any>): JSONObject = JSONObject().apply { entries.forEach { put(it.first,it.second) } }
private fun Profile.json()=obj("id" to id,"name" to name,"email" to email,"bio" to bio,"buildingId" to buildingId,"avatarUri" to avatarUri,"ecoScore" to ecoScore,"completedDeliveries" to completedDeliveries,"rating" to rating,"ratingCount" to ratingCount)
private fun JSONObject.toProfile()=Profile(getString("id"),getString("name"),getString("email"),getString("bio"),getString("buildingId"),optString("avatarUri"),getDouble("ecoScore"),getInt("completedDeliveries"),optDouble("rating",0.0),optInt("ratingCount"))
private fun Preferences.json()=obj("language" to language,"biometricLock" to biometricLock,"notifications" to notifications,"darkMode" to darkMode)
private fun JSONObject.toPreferences()=Preferences(getString("language"),getBoolean("biometricLock"),getBoolean("notifications"),getBoolean("darkMode"))
private fun Delivery.json()=obj("id" to id,"description" to description,"pickupId" to pickupId,"dropoffId" to dropoffId,"weightKg" to weightKg,"reward" to reward,"windowStart" to windowStart,"windowEnd" to windowEnd,"requesterId" to requesterId,"requesterName" to requesterName,"courierId" to courierId,"courierName" to courierName,"status" to status,"handoffCode" to handoffCode,"distanceKm" to distanceKm,"createdAt" to createdAt,"completedAt" to completedAt,"matchedRouteId" to matchedRouteId)
private fun JSONObject.toDelivery()=Delivery(getString("id"),getString("description"),getString("pickupId"),getString("dropoffId"),getDouble("weightKg"),getDouble("reward"),getLong("windowStart"),getLong("windowEnd"),getString("requesterId"),getString("requesterName"),getString("courierId"),getString("courierName"),getString("status"),getString("handoffCode"),getDouble("distanceKm"),getLong("createdAt"),optLong("completedAt"),optString("matchedRouteId"))
private fun RouteOffer.json()=obj("id" to id,"startId" to startId,"endId" to endId,"capacityKg" to capacityKg,"windowStart" to windowStart,"windowEnd" to windowEnd,"courierId" to courierId,"courierName" to courierName,"transport" to transport,"rating" to rating)
private fun JSONObject.toRoute()=RouteOffer(getString("id"),getString("startId"),getString("endId"),getDouble("capacityKg"),getLong("windowStart"),getLong("windowEnd"),getString("courierId"),getString("courierName"),getString("transport"),getDouble("rating"))
private fun RideOffer.json()=obj("id" to id,"origin" to origin,"destination" to destination,"departure" to departure,"availableSeats" to availableSeats,"vehicle" to vehicle,"price" to price,"driverId" to driverId,"driverName" to driverName,"bookedByMe" to bookedByMe,"distanceKm" to distanceKm,"status" to status)
private fun JSONObject.toRide()=RideOffer(getString("id"),getString("origin"),getString("destination"),getLong("departure"),getInt("availableSeats"),getString("vehicle"),getDouble("price"),getString("driverId"),getString("driverName"),getBoolean("bookedByMe"),getDouble("distanceKm"),getString("status"))
private fun Listing.json()=obj("id" to id,"title" to title,"description" to description,"price" to price,"category" to category,"condition" to condition,"sellerId" to sellerId,"sellerName" to sellerName,"status" to status,"purchasedByMe" to purchasedByMe)
private fun JSONObject.toListing()=Listing(getString("id"),getString("title"),getString("description"),getDouble("price"),getString("category"),getString("condition"),getString("sellerId"),getString("sellerName"),getString("status"),getBoolean("purchasedByMe"))
private fun Message.json()=obj("id" to id,"deliveryId" to deliveryId,"senderId" to senderId,"senderName" to senderName,"text" to text,"sentAt" to sentAt)
private fun JSONObject.toMessage()=Message(getString("id"),getString("deliveryId"),getString("senderId"),getString("senderName"),getString("text"),getLong("sentAt"))
private fun EcoActivity.json()=obj("id" to id,"title" to title,"detail" to detail,"co2Kg" to co2Kg,"type" to type,"createdAt" to createdAt,"referenceId" to referenceId)
private fun JSONObject.toActivity()=EcoActivity(getString("id"),getString("title"),getString("detail"),getDouble("co2Kg"),getString("type"),getLong("createdAt"),getString("referenceId"))
