package com.example.campus.services

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Real campus data only. Local demo records are never read by this Activity. */
class LiveCampusActivity : FragmentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PushSupport.setEnabled(this, granted)
    }
    private val executor=Executors.newSingleThreadExecutor()
    private lateinit var body:LinearLayout
    private var user=JSONObject()
    private var current="deliveries"
    private var generation=0
    private var offline=false
    private var selectedId=""
    private lateinit var scanner:HandoffScanner
    private var scannedCode:((String)->Unit)?=null
    private val green=Color.rgb(27,67,51)
    private val cream=Color.rgb(247,246,240)
    private val muted=Color.rgb(103,121,110)
    private val gold=Color.rgb(216,174,43)
    private val store by lazy{LiveSnapshotStore(applicationContext)}
    private val api by lazy{LiveApiClient(applicationContext)}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        scanner=HandoffScanner(this){value->scannedCode?.invoke(value)}
        if(SecureSession(this).read()?.isAuthorized!=true){finish();return}
        val pushPreferences=getSharedPreferences("campus_push",MODE_PRIVATE)
        if(PushSupport.initialize(this) && Build.VERSION.SDK_INT>=33 &&
            ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED &&
            pushPreferences.getBoolean("enabled",true) && !pushPreferences.getBoolean("permission_asked",false)) {
            pushPreferences.edit().putBoolean("permission_asked",true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        current=state?.getString("tab")?:"deliveries"
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(current=="deliveries")finish() else deliveries()}})
        shell("Your campus","Connecting to your university account…")
        fetch("/api/v1/profile",false){snapshot->user=snapshot.obj();when(current){"rides"->rides();"marketplace"->marketplace();"profile"->profile();"transactions"->transactions();else->deliveries()}}
    }
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);out.putString("tab",current)}
    override fun onDestroy(){executor.shutdown();super.onDestroy()}
    private fun background(task:()->Unit){executor.execute(task)}
    private fun ui(task:()->Unit){runOnUiThread{if(!isFinishing&&!isDestroyed)task()}}
    private fun shape(color:Int,radius:Int=20)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:String,size:Float=15f,bold:Boolean=false,color:Int=green)=TextView(this).apply{text=value;textSize=size;setTextColor(color);if(bold)setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(4),0,dp(4))}
    private fun label(value:String,size:Float=15f,bold:Boolean=false,color:Int=green){body.addView(text(value,size,bold,color))}
    private fun space(n:Int=12){body.addView(View(this),LinearLayout.LayoutParams(1,dp(n)))}
    private fun button(title:String,primary:Boolean=true,action:()->Unit):Button {
        val button=Button(this).apply{text=title;textSize=14f;isAllCaps=false;minHeight=dp(48);setPadding(dp(12),dp(6),dp(12),dp(6));setTextColor(if(primary)Color.WHITE else green);background=shape(if(primary)green else Color.rgb(234,239,228),14);setOnClickListener{try{action()}catch(e:Exception){notice(e.message?:"Check your details.")}}}
        body.addView(button,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(10)});return button
    }
    private fun shell(title:String,subtitle:String){
        generation++;offline=false
        val frame=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(cream)}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(4))}
        top.addView(text("CampusRelay",23f,true),LinearLayout.LayoutParams(0,-2,1f));top.addView(text("LIVE",12f,true).apply{setPadding(dp(12),dp(7),dp(12),dp(7));background=shape(Color.rgb(220,235,222),20)})
        frame.addView(top)
        val scroll=ScrollView(this).apply{isFillViewport=true}
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(28))}
        scroll.addView(body);frame.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this).apply{setPadding(dp(10),dp(8),dp(10),dp(8));setBackgroundColor(Color.WHITE)}
        listOf("Deliveries" to {deliveries()},"Rides" to {rides()},"Market" to {marketplace()},"Activity" to {transactions()},"Profile" to {profile()}).forEach{(title,action)->
            nav.addView(TextView(this).apply{text=title;textSize=11f;gravity=Gravity.CENTER;setTextColor(green);minHeight=dp(48);setOnClickListener{action()}},LinearLayout.LayoutParams(0,-2,1f))}
        frame.addView(nav);setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame){view,insets->val i=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());view.setPadding(i.left,i.top,i.right,i.bottom);insets}
        ViewCompat.requestApplyInsets(frame)
        label(title,26f,true);label(subtitle,14f,false,muted);space()
    }
    private fun notice(message:String){android.app.AlertDialog.Builder(this).setTitle("CampusRelay").setMessage(message).setPositiveButton("OK",null).show()}
    private fun fetch(path:String,array:Boolean,done:(LiveSnapshotStore.Snapshot)->Unit){
        val ticket=generation
        val progress=ProgressBar(this);body.addView(progress)
        background{
            try{val result=store.load(path,array);ui{if(ticket==generation){body.removeView(progress);offline=result.cached;if(result.cached)label("Offline snapshot · updated ${date(result.updatedAt)}. Changes require a connection, except queued handoffs.",13f,true,muted);done(result)}}}
            catch(e:Exception){ui{if(ticket==generation){body.removeView(progress);label(e.message?:"Campus service unavailable.",15f,false,muted);button("Try again",false){when(current){"rides"->rides();"marketplace"->marketplace();"profile"->profile();"transactions"->transactions();else->deliveries()}}}}}
        }
    }
    private fun action(method:String,path:String,payload:JSONObject?=null,after:(JSONObject)->Unit){
        if(offline){notice("This is an offline snapshot. Reconnect and refresh before making this change.");return}
        val dialog=android.app.ProgressDialog(this).apply{setMessage("Updating campus service…");setCancelable(false);show()}
        background{try{val response=api.request(method,path,payload);ui{dialog.dismiss();after(response)}}catch(e:Exception){ui{dialog.dismiss();notice(e.message?:"Unable to update campus service. Please try again.")}}}
    }
    private fun field(title:String,hint:String="",value:String="",numeric:Boolean=false):EditText{
        label(title,13f,true,muted)
        return EditText(this).apply{setText(value);this.hint=hint;textSize=15f;setSingleLine();setTextColor(green);setHintTextColor(muted);setPadding(dp(14),dp(8),dp(14),dp(8));background=shape(Color.WHITE,12);if(numeric)inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED;body.addView(this,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(12)})}
    }
    private fun pick(title:String,options:List<String>):Spinner{
        label(title,13f,true,muted)
        return Spinner(this).apply{adapter=ArrayAdapter(this@LiveCampusActivity,android.R.layout.simple_spinner_dropdown_item,options);background=shape(Color.WHITE,12);body.addView(this,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(12)})}
    }
    private fun card(title:String,detail:String,tag:String="",onClick:(()->Unit)?=null){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(15),dp(18),dp(15));background=shape(Color.WHITE,18)}
        if(tag.isNotBlank())box.addView(text(tag,11f,true,muted))
        box.addView(text(title,17f,true));box.addView(text(detail,14f,false,muted))
        body.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
        if(onClick!=null){box.isClickable=true;box.isFocusable=true;box.setOnClickListener{onClick()};box.contentDescription="$title. $detail. Open details"}
    }
    private fun each(array:JSONArray,render:(JSONObject)->Unit){if(array.length()==0)card("Nothing here yet","Be the first to connect your campus.") else for(i in 0 until array.length())render(array.getJSONObject(i))}
    private fun JSONObject.string(key:String)=if(isNull(key))"" else optString(key,"")
    private fun amount(cents:Long)=String.format(Locale.US,"R%.2f",cents/100.0)
    private fun date(value:Long)=SimpleDateFormat("d MMM, HH:mm",Locale.getDefault()).format(Date(value))
    private fun pretty(value:String)=value.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}
    private fun iso(value:Long)=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(value))
    private fun timestamp(label:String,default:Long=System.currentTimeMillis()+3600000):()->Long{
        var value=default
        val b=button("$label · ${date(value)}",false){ }
        b.setOnClickListener{val calendar=Calendar.getInstance().apply{timeInMillis=value};DatePickerDialog(this,{_,y,m,d->calendar.set(y,m,d);TimePickerDialog(this,{_,h,min->calendar.set(Calendar.HOUR_OF_DAY,h);calendar.set(Calendar.MINUTE,min);calendar.set(Calendar.SECOND,0);value=calendar.timeInMillis;b.text="$label · ${date(value)}"},calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE),true).show()},calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH)).show()}
        return {value}
    }
    private fun number(input:EditText,label:String,min:Double,max:Double):Double{val n=input.text.toString().toDoubleOrNull();require(n!=null&&n.isFinite()&&n in min..max){"$label must be between $min and $max."};return n}
    private fun required(input:EditText,label:String):String=input.text.toString().trim().also{require(it.isNotEmpty()){ "Enter $label." }}

    private fun deliveries(){current="deliveries";shell("A little help, on your way.","Real requests from your verified university community.")
        button("＋ New delivery request"){deliveryForm(false)};button("Offer your campus route",false){deliveryForm(true)};space()
        fetch("/api/v1/deliveries",true){snapshot->each(snapshot.array()){d->card(d.string("itemDescription"),"${d.string("pickupBuilding")} → ${d.string("dropoffBuilding")}\n${d.optDouble("weightKg")} kg · ${amount(d.optLong("rewardCents"))}",pretty(d.string("status"))){detail(d.string("id"))}}}
    }
    private fun deliveryForm(route:Boolean){
        current="delivery-form";shell(if(route)"Offer your route" else "New delivery request","Enter your actual campus locations. No demonstration buildings are used here.")
        val description=if(route)null else field("Item description","Book, notes, charger…")
        val start=field(if(route)"Start building" else "Pickup building")
        val end=field(if(route)"End building" else "Drop-off building")
        label("Coordinates help find routes within 1 km.",13f,false,muted)
        val startLat=field("Start / pickup latitude","Latitude in degrees",numeric=true);val startLng=field("Start / pickup longitude","Longitude in degrees",numeric=true)
        val endLat=field("End / drop-off latitude","Latitude in degrees",numeric=true);val endLng=field("End / drop-off longitude","Longitude in degrees",numeric=true)
        val weight=field(if(route)"Capacity (kg)" else "Weight (kg)","1.0",numeric=true)
        val reward=if(route)null else field("Reward (rand)","25.00",numeric=true)
        val from=timestamp("Available from");val until=timestamp("Available until",System.currentTimeMillis()+3*3600000)
        button(if(route)"Publish route" else "Request delivery"){
            val a=required(start,"pickup / start building");val b=required(end,"drop-off / end building");require(a!=b){"Choose different pickup and drop-off locations."};require(until()>from()){ "The time window must end after it starts." }
            val payload=JSONObject().put(if(route)"startBuilding" else "pickupBuilding",a).put(if(route)"endBuilding" else "dropoffBuilding",b)
                .put(if(route)"startLat" else "pickupLat",number(startLat,"Latitude",-90.0,90.0)).put(if(route)"startLng" else "pickupLng",number(startLng,"Longitude",-180.0,180.0))
                .put(if(route)"endLat" else "dropoffLat",number(endLat,"Latitude",-90.0,90.0)).put(if(route)"endLng" else "dropoffLng",number(endLng,"Longitude",-180.0,180.0))
                .put(if(route)"capacityKg" else "weightKg",number(weight,"Weight",0.01,50.0)).put("windowStart",iso(from())).put("windowEnd",iso(until()))
            if(!route)payload.put("itemDescription",required(description!!,"item description")).put("rewardCents",Math.round(number(reward!!,"Reward",0.0,1000.0)*100))
            action("POST",if(route)"/api/v1/delivery-offers" else "/api/v1/deliveries",payload){if(route){notice("Your route is available for matching requests.");deliveries()} else detail(it.string("id"))}
        }
    }
    private fun detail(id:String){current="detail";selectedId=id;shell("Delivery details","Track the handoff with your campus community.")
        fetch("/api/v1/deliveries/$id",false){snapshot->val d=snapshot.obj();card(d.string("itemDescription"),"${d.string("pickupBuilding")} → ${d.string("dropoffBuilding")}\n${d.optDouble("weightKg")} kg · ${amount(d.optLong("rewardCents"))}",pretty(d.string("status")))
            val requester=d.string("requesterId")==user.string("id");val courier=d.string("courierId")==user.string("id");val status=d.string("status")
            if(requester&&status=="ACTIVE")button("Find matching couriers"){matches(id)}
            if(requester&&status in listOf("ACTIVE","MATCHED"))button("Cancel request",false){confirm("Cancel this delivery request?"){action("POST","/api/v1/deliveries/$id/cancel"){deliveries()}}}
            if(requester&&status in listOf("MATCHED","IN_PROGRESS"))button("Show handoff code",false){showCode(id)}
            if(courier&&status=="MATCHED")button("Confirm pickup"){action("POST","/api/v1/deliveries/$id/pickup"){detail(id)}}
            if(courier&&status=="IN_PROGRESS"){
                val code=field("Recipient's QR verification code","Scan or enter the recipient's code")
                button("Scan recipient’s QR code",false){scannedCode={value->code.setText(value)};scanner.launch()}
                button("Verify handoff"){
                    val value=required(code,"handoff code")
                    val complete={action("POST","/api/v1/deliveries/$id/handoff",JSONObject().put("scannedQrHash",value)){detail(id)}}
                    BiometricGate.authenticate(this,"Confirm delivery handoff",complete){notice(it)}
                }
                button("Save handoff for offline sync",false){
                    val value=required(code,"handoff code")
                    BiometricGate.authenticate(this,"Save verified handoff",{
                        OfflineQueue.enqueue(this,id,value,user.string("id")){ui{notice("Handoff securely queued on this device. It remains pending until the campus API accepts it.")}}
                    }){notice(it)}
                }
            }
            val trade=d.string("tradeId");if(trade.isNotBlank())button("Open conversation",false){messages(trade)}
            button("Refresh",false){detail(id)}
        }
    }
    private fun matches(id:String){current="matches";shell("A route that fits.","Matched by route proximity, capacity and delivery time.")
        fetch("/api/v1/deliveries/$id/matches",true){snapshot->each(snapshot.array()){offer->card("${offer.string("startBuilding")} → ${offer.string("endBuilding")}","Capacity ${offer.optDouble("capacityKg")} kg\n${offer.string("windowStart")}","MATCHED ROUTE");button("Book this courier"){action("POST","/api/v1/deliveries/$id/book",JSONObject().put("offerId",offer.string("id"))){detail(id)}}}}
    }
    private fun showCode(id:String){action("GET","/api/v1/deliveries/$id/handoff-code"){response->
        val code=response.string("qrVerificationCode")
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(20))}
        panel.addView(text("Share only with your assigned courier at the final handoff.",14f,false,muted))
        panel.addView(ImageView(this).apply{setImageBitmap(HandoffQr.bitmap(code));contentDescription="Delivery verification QR code"},LinearLayout.LayoutParams(-1,dp(240)))
        panel.addView(text(code,12f).apply{setTextIsSelectable(true)})
        android.app.AlertDialog.Builder(this).setTitle("Recipient’s handoff code").setView(panel).setPositiveButton("Done",null).show()
    }}
    private fun confirm(message:String,yes:()->Unit){android.app.AlertDialog.Builder(this).setTitle("Confirm").setMessage(message).setPositiveButton("Confirm"){_,_->yes()}.setNegativeButton("Cancel",null).show()}

    private fun rides(query:String=""){current="rides";shell("Share the journey.","Split the trip and lighten your footprint.")
        val origin=field("From","Origin");val destination=field("To","Destination");button("Search rides",false){rides("?origin=${android.net.Uri.encode(origin.text.toString())}&destination=${android.net.Uri.encode(destination.text.toString())}")};button("＋ Offer a ride"){rideForm()};space()
        fetch("/api/v1/rides$query",true){snapshot->each(snapshot.array()){ride->card("${ride.string("origin")} → ${ride.string("destination")}","${ride.string("departureTime")}\n${ride.optInt("availableSeats")} seats · ${amount(ride.optLong("pricePerSeatCents"))}",ride.string("vehicleType"));if(ride.string("driverId")!=user.string("id"))button("Request a seat"){action("POST","/api/v1/rides/${ride.string("id")}/book"){notice("Seat booked. Your booking is in Activity.");transactions()}}}}
    }
    private fun rideForm(){current="ride-form";shell("Offer a ride","Share costs with a verified campus member.")
        val origin=field("Origin");val destination=field("Destination");val seats=field("Available seats","1–12",numeric=true);val price=field("Cost per seat (rand)",numeric=true);val distance=field("Trip distance (km)",numeric=true);val vehicle=pick("Vehicle",listOf("CAR","SUV","VAN"));val departure=timestamp("Departure")
        button("Publish ride"){
            val count=number(seats,"Seats",1.0,12.0);require(count%1==0.0){"Seats must be a whole number."}
            val payload=JSONObject().put("origin",required(origin,"origin")).put("destination",required(destination,"destination")).put("departureTime",iso(departure())).put("availableSeats",count.toInt()).put("vehicleType",vehicle.selectedItem.toString()).put("pricePerSeatCents",Math.round(number(price,"Price",0.0,1000.0)*100)).put("distanceKm",number(distance,"Distance",0.01,2000.0))
            action("POST","/api/v1/rides",payload){rides()}
        }
    }
    private fun marketplace(){current="marketplace";shell("Good things, passed on.","Buy and sell within your verified campus.")
        button("＋ List an item"){listingForm()};space()
        fetch("/api/v1/marketplace",true){snapshot->each(snapshot.array()){listing->card(listing.string("title"),"${listing.string("description")}\n${amount(listing.optLong("priceCents"))}",pretty(listing.string("condition"))+" · "+pretty(listing.string("category")));if(listing.string("sellerId")!=user.string("id"))button("Reserve item"){action("POST","/api/v1/marketplace/${listing.string("id")}/reserve"){notice("Item reserved. Arrange collection in Activity.");transactions()}}}}
    }
    private fun listingForm(){current="listing-form";shell("Make room for something new.","Give a campus item its next chapter.")
        val title=field("Item title");val description=field("Description");val price=field("Price (rand)",numeric=true);val category=pick("Category",listOf("BOOKS","ELECTRONICS","FURNITURE","CLOTHING","OTHER"));val condition=pick("Condition",listOf("GOOD","LIKE_NEW","FAIR","NEW"))
        button("Publish listing"){val payload=JSONObject().put("title",required(title,"title")).put("description",required(description,"description")).put("priceCents",Math.round(number(price,"Price",0.0,100000.0)*100)).put("category",category.selectedItem.toString()).put("condition",condition.selectedItem.toString());action("POST","/api/v1/marketplace",payload){marketplace()}}
    }
    private fun transactions(){current="transactions";shell("Your activity","Bookings, conversations and handoffs in one place.")
        fetch("/api/v1/transactions",true){snapshot->each(snapshot.array()){trade->card(pretty(trade.string("type")),"${amount(trade.optLong("agreedPriceCents"))}\nPayment handled between participants; no in-app escrow is funded.",pretty(trade.string("status")))
            button("Conversation",false){messages(trade.string("id"))}
            if(trade.string("type")=="DELIVERY")button("View delivery",false){detail(trade.string("serviceRefId"))}
            else if(trade.string("providerId")==user.string("id")&&trade.string("status")=="ACCEPTED")button("Confirm fulfilment"){confirm("Confirm that this ride or item exchange has been completed?"){BiometricGate.authenticate(this,"Confirm fulfilment",{action("POST","/api/v1/transactions/${trade.string("id")}/complete"){transactions()}}){notice(it)}}}
            if(trade.string("status")=="COMPLETED")button("Leave a review",false){review(trade.string("id"))}
        }}
    }
    private fun messages(tradeId:String){current="messages";shell("Conversation","Messages are private to the participants in this transaction.")
        fetch("/api/v1/transactions/$tradeId/messages",true){snapshot->each(snapshot.array()){m->card(if(m.string("senderId")==user.string("id"))"You" else "Campus member",m.string("content"),m.string("sentAt"))}
            val input=field("Message","Write a message…");button("Send"){action("POST","/api/v1/transactions/$tradeId/messages",JSONObject().put("content",required(input,"message"))){messages(tradeId)}}
        }
    }
    private fun review(tradeId:String){current="review";shell("How did it go?","Help your campus community build trust.")
        val stars=pick("Rating",listOf("5","4","3","2","1"));val comment=field("Comment (optional)")
        button("Submit review"){action("POST","/api/v1/transactions/$tradeId/review",JSONObject().put("rating",stars.selectedItem.toString().toInt()).put("comment",comment.text.toString())){notice("Thank you. Your review has been saved.");transactions()}}
    }
    private fun profile(){current="profile";shell("Your campus profile","Account information verified by the university API.")
        fetch("/api/v1/profile",false){snapshot->user=snapshot.obj();val p=user
            card(p.string("fullName"),p.string("email"),"UNIVERSITY ACCOUNT")
            val name=field("Full name",value=p.string("fullName"));val bio=field("Bio",value=p.string("bio"));val location=field("Campus location",value=p.string("campusLocation"));val lang=pick("Language",listOf("en","zu","af"));lang.setSelection(listOf("en","zu","af").indexOf(p.string("language")).coerceAtLeast(0))
            button("Save profile"){action("PUT","/api/v1/profile",JSONObject().put("fullName",required(name,"name")).put("bio",bio.text.toString()).put("campusLocation",required(location,"campus location")).put("language",lang.selectedItem.toString())){profile()}}
            button("View eco impact",false){eco()};button("Offline handoff queue",false){queue()};button("Return to app",false){finish()}
            button("Sign out",false){SecureSession(this).clear();store.unbind();androidx.core.app.NotificationManagerCompat.from(this).cancelAll();finish()}
        }
    }
    private fun eco(){current="eco";shell("Small journeys. Real impact.","Your estimated savings from completed campus exchanges.")
        fetch("/api/v1/eco",false){snapshot->val e=snapshot.obj();card(String.format(Locale.US,"%.3f kg CO₂",e.optDouble("ecoScoreKg")),"Estimated emissions saved");label(e.string("calculation"),13f,false,muted);space();each(e.optJSONArray("actions")?:JSONArray()){a->card(a.string("description"),"${a.optDouble("savedKg")} kg CO₂ · ${a.string("completedAt")}")}}
    }
    private fun queue(){current="queue";shell("Offline handoffs","Saved on this device. A handoff is complete remotely only after the server accepts it.")
        background{val pending=OfflineQueue.pendingCount(this);ui{label("$pending handoff(s) awaiting sync",20f,true);button("Retry synchronization"){OfflineQueue.schedule(this);notice("Synchronization scheduled. Android will retry when a network is available.")};button("Refresh status",false){queue()}}}
    }
}
