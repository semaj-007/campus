package com.example.campus

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.campus.data.*
import com.example.campus.services.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var repo: CampusRepository
    private lateinit var googleSso: GoogleSso
    private lateinit var handoffScanner: HandoffScanner
    private lateinit var shell: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var frame: FrameLayout
    private val photoPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
        if(uri!=null)attempt{
            contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            repo.updateProfile(repo.profile.name,repo.profile.bio,repo.profile.buildingId,uri.toString())
            render()
        }
    }
    private var registrationProof:Uri?=null
    private var registrationName=""
    private var registrationStudentNumber=""
    private val proofPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
        if(uri!=null){
            contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            registrationProof=uri; render()
        }
    }
    private var screen="home"
    private var filter=0
    private var selectedDelivery=""
    private var wizardStep=0
    private var draftPickup="engineering"
    private var draftDropoff="library"
    private var draftDescription=""
    private var draftWeight=1.0
    private var draftReward=25.0
    private var draftStart=System.currentTimeMillis()
    private var draftEnd=System.currentTimeMillis()+7200000
    private val localPrefs by lazy { getSharedPreferences("campus_ui",MODE_PRIVATE) }
    private val green=Color.rgb(27,67,51)
    private val gold=Color.rgb(216,174,43)
    private val mutedGold=Color.rgb(148,109,17)
    private val dark get()=repo.preferences.darkMode
    private val bg get()=Color.parseColor(if(dark) "#15281F" else "#F7F6F0")
    private val cardColor get()=Color.parseColor(if(dark) "#213A2E" else "#FFFFFF")
    private val ink get()=Color.parseColor(if(dark) "#F1F4EA" else "#202F28")
    private val muted get()=Color.parseColor(if(dark) "#B0C3B4" else "#6C7C71")
    private val border get()=Color.parseColor(if(dark) "#3D5445" else "#E5E3D9")
    private val pale get()=Color.parseColor(if(dark) "#304D3A" else "#EEF2E7")
    private fun s(id:Int)=getString(id)
    private fun money(value:Double)="R"+if(value%1==0.0) value.toInt() else String.format(Locale.US,"%.2f",value)
    private fun date(value:Long)=SimpleDateFormat("EEE, d MMM · HH:mm",resources.configuration.locales[0]).format(Date(value))
    private fun time(value:Long)=SimpleDateFormat("HH:mm",resources.configuration.locales[0]).format(Date(value))

    override fun attachBaseContext(base:Context) {
        val lang=base.getSharedPreferences("campus_ui",Context.MODE_PRIVATE).getString("language","en")!!
        val config=Configuration(base.resources.configuration);config.setLocale(Locale.forLanguageTag(lang))
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        repo=CampusRepository(this)
        delegate.localNightMode=if(repo.preferences.darkMode)AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        googleSso=GoogleSso(this)
        handoffScanner=HandoffScanner(this){code->attempt{
            val d=repo.delivery(selectedDelivery)
            require(d.status=="IN_TRANSIT"){"Pick up the delivery before confirming its handoff."}
            require(code==d.handoffCode){"This QR code does not match the selected delivery."}
            val finish={attempt{repo.completeDelivery(d.id,code);render()}}
            if(repo.preferences.biometricLock)BiometricGate.authenticate(this,"Confirm delivery handoff",finish){notice("Biometric confirmation",it)}else finish()
        }}
        if(savedInstanceState!=null){screen=savedInstanceState.getString("screen","home");filter=savedInstanceState.getInt("filter");selectedDelivery=savedInstanceState.getString("delivery","")
            wizardStep=savedInstanceState.getInt("step");draftPickup=savedInstanceState.getString("pickup",draftPickup);draftDropoff=savedInstanceState.getString("dropoff",draftDropoff);draftDescription=savedInstanceState.getString("description","");draftWeight=savedInstanceState.getDouble("weight",1.0);draftReward=savedInstanceState.getDouble("reward",25.0);draftStart=savedInstanceState.getLong("start",draftStart);draftEnd=savedInstanceState.getLong("end",draftEnd)}
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){when{screen=="wizard"&&wizardStep>0->{wizardStep--;render()};screen=="register"->go("welcome");screen!="home"&&screen!="welcome"->go("home");screen=="welcome"->finish();else->finish()}}})
        if(!localPrefs.getBoolean("demo_session",false)) screen="welcome"
        render()
    }
    override fun onDestroy(){repo.close();super.onDestroy()}
    private fun openUniversity(){startActivity(Intent(this,LiveCampusActivity::class.java))}
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);out.putString("screen",screen);out.putInt("filter",filter);out.putString("delivery",selectedDelivery);out.putInt("step",wizardStep);out.putString("pickup",draftPickup);out.putString("dropoff",draftDropoff);out.putString("description",draftDescription);out.putDouble("weight",draftWeight);out.putDouble("reward",draftReward);out.putLong("start",draftStart);out.putLong("end",draftEnd)}
    private fun go(page:String){(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken,0);screen=page;render()}
    private fun render(){
        frame=FrameLayout(this).apply{setBackgroundColor(bg)}
        shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        frame.addView(shell,FrameLayout.LayoutParams(-1,-1));setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame){v,insets->val b=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);insets}
        ViewCompat.requestApplyInsets(frame)
        @Suppress("DEPRECATION")
        window.statusBarColor=bg
        @Suppress("DEPRECATION")
        window.navigationBarColor=cardColor
        androidx.core.view.WindowCompat.getInsetsController(window,frame).isAppearanceLightStatusBars=!dark
        val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false}
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(20),dp(24),dp(28))}
        scroll.addView(body,ViewGroup.LayoutParams(-1,-2));shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(screen){"welcome"->welcome();"register"->register();"home"->home();"track"->track();"settings"->settings();"detail"->details();"wizard"->wizard();"carpool"->carpool();"market"->market();"impact"->impact();"chat"->chat();"services"->services();"notifications"->notifications();else->home()}
        if(screen!="welcome"&&screen!="register"&&screen!="wizard") bottomNav()
        if(screen=="home")frame.addView(FrameLayout(this).apply{
            background=surface(gold,dp(19).toFloat());elevation=dp(5).toFloat();contentDescription="Create delivery request";isFocusable=true
            addView(RelayIcon(this@MainActivity,"plus",green),FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER))
            setOnClickListener{startWizard()}
        },FrameLayout.LayoutParams(dp(56),dp(56),Gravity.BOTTOM or Gravity.END).apply{rightMargin=dp(24);bottomMargin=dp(94)})
    }
    private fun text(value:String,size:Float=14f,color:Int=ink,bold:Boolean=false)=TextView(this).apply{text=value;textSize=size;setTextColor(color);typeface=Typeface.create(if(bold) "sans-serif-medium" else "sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL);includeFontPadding=false;setLineSpacing(dp(3).toFloat(),1f)}
    private fun col()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun space(parent:LinearLayout=body,h:Int=16){parent.addView(View(this),LinearLayout.LayoutParams(1,dp(h)))}
    private fun addLabel(label:String,parent:LinearLayout=body){parent.addView(text(label.uppercase(),11f,muted,true).apply{letterSpacing=.1f});space(parent,9)}
    private fun card(parent:LinearLayout=body,padding:Int=18):LinearLayout=col().also{it.background=surface(cardColor,dp(20).toFloat(),border);it.setPadding(dp(padding),dp(padding),dp(padding),dp(padding));parent.addView(it,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})}
    private fun iconButton(kind:String,description:String,action:()->Unit):FrameLayout=FrameLayout(this).apply{background=surface(cardColor,dp(30).toFloat(),border);contentDescription=description;isClickable=true;isFocusable=true;addView(RelayIcon(this@MainActivity,kind,ink),FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));setOnClickListener{action()}}
    private fun button(label:String,parent:LinearLayout=body,primary:Boolean=true,action:()->Unit):TextView=text(label,15f,if(primary) Color.WHITE else ink,true).apply{
        gravity=Gravity.CENTER;minHeight=dp(52);setPadding(dp(16),dp(15),dp(16),dp(15));background=surface(if(primary) green else cardColor,dp(15).toFloat(),if(primary)null else border);isClickable=true;isFocusable=true;contentDescription=label;setOnClickListener{action()};parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
    }
    private fun heading(title:String,subtitle:String?=null,back:Boolean=true){
        val r=row();if(back)r.addView(iconButton("back",s(R.string.back)){go("home")},LinearLayout.LayoutParams(dp(42),dp(42)).apply{rightMargin=dp(14)})
        val c=col();c.addView(text(title,25f,ink,true));if(subtitle!=null){space(c,5);c.addView(text(subtitle,12f,muted))};r.addView(c,LinearLayout.LayoutParams(0,-2,1f));body.addView(r);space(h=24)
    }
    private fun pill(label:String,color:Int=green,fill:Int=pale)=text(label,11f,color,true).apply{setPadding(dp(10),dp(5),dp(10),dp(5));background=surface(fill,dp(20).toFloat())}
    private fun section(title:String,action:String?=null,onAction:(()->Unit)?=null){val r=row();r.addView(text(title,18f,ink,true),LinearLayout.LayoutParams(0,-2,1f));if(action!=null)r.addView(text(action,12f,muted,true).apply{setPadding(dp(10),dp(12),0,dp(12));setOnClickListener{onAction?.invoke()}});body.addView(r);space(h=12)}
    private fun empty(title:String,detail:String){card().apply{addView(text(title,16f,ink,true));space(this,8);addView(text(detail,13f,muted))}}
    private fun notice(title:String,message:String){AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(s(R.string.done),null).show()}
    private fun attempt(action:()->Unit){try{action()}catch(e:IllegalArgumentException){notice("Check the details",e.message?:"Please check the details.")}catch(e:Exception){notice("Couldn’t save",e.message?:"Please try again.")}}
    private fun demoStrip(){body.addView(text(s(R.string.demo_notice),11f,muted).apply{setPadding(0,dp(8),0,dp(14))})}

    private fun welcome(){
        val top=row();top.addView(text("EMERIS PRETORIA  /  CAMPUS LIFE",11f,muted,true),LinearLayout.LayoutParams(0,-2,1f));top.addView(text(repo.preferences.language.uppercase()+" ⌄",12f,green,true).apply{setPadding(dp(10),dp(10),dp(10),dp(10));setOnClickListener{chooseLanguage()}});body.addView(top)
        space(h=40)
        val logo=FrameLayout(this).apply{background=surface(green,dp(28).toFloat());addView(RelayIcon(this@MainActivity,"leaf",Color.parseColor("#A6D2AD")),FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER))};body.addView(logo,LinearLayout.LayoutParams(dp(80),dp(80)));space(h=26)
        body.addView(text("CampusRelay",32f,ink,true));space(h=14);body.addView(text(s(R.string.welcome_title),35f,green,true));space(h=16);body.addView(text(s(R.string.welcome_subtitle),16f,muted))
        space(h=24)
        val art=CampusMap(this,dark).apply{deliveries=repo.deliveries().take(2)};body.addView(art,LinearLayout.LayoutParams(-1,dp(155)));space(h=20)
        button("Sign back in with Google"){if(ServiceConfig(this).configured)googleSso.start{ok,message->runOnUiThread{if(ok)openUniversity()else notice("Google sign-in",message)}} else go("services")}
        button("Create a new account",primary=false){if(ServiceConfig(this).configured)go("register") else go("services")}
        if(GoogleSession(this).read()?.isAuthorized==true&&BiometricGate.isAvailable(this))button("Unlock Google account",primary=false){BiometricGate.authenticate(this,"Unlock CampusRelay",{openUniversity()}){notice("Biometric sign-in",it)}}
        button(s(R.string.explore_demo),primary=false){val enter={localPrefs.edit().putBoolean("demo_session",true).apply();go("home")};if(repo.preferences.biometricLock)BiometricGate.authenticate(this,"Unlock CampusRelay",enter){notice("Biometric sign-in",it)}else enter()}
        space(h=12);body.addView(text(s(R.string.security_note),11f,muted).apply{gravity=Gravity.CENTER})
    }
    private fun register(){
        heading("Create your account","New accounts require proof that you are registered with the institution.")
        val fullName=field("Full name","As shown on your registration document",registrationName).apply{doAfterTextChanged{registrationName=it.toString()}}
        val studentNumber=field("Student number","Your institutional student number",registrationStudentNumber).apply{doAfterTextChanged{registrationStudentNumber=it.toString()}}
        card().apply{
            addView(text("Proof of registration",16f,ink,true));space(this,8)
            addView(text(if(registrationProof==null) "Upload a current registration letter or student document in PDF, JPG, or PNG format (maximum 5 MB)." else "Document selected and ready to submit.",12f,muted))
            button(if(registrationProof==null) "Choose document" else "Choose a different document",this,false){proofPicker.launch(arrayOf("application/pdf","image/jpeg","image/png"))}
        }
        button("Continue with Google and submit"){
            attempt{
                require(fullName.text.toString().isNotBlank()){ "Enter your full name." }
                require(studentNumber.text.toString().isNotBlank()){ "Enter your student number." }
                val proof=registrationProof ?: error("Choose proof of registration first.")
                googleSso.createAccount(fullName.text.toString().trim(),studentNumber.text.toString().trim(),proof){ok,message->runOnUiThread{
                    notice(if(ok) "Registration submitted" else "Registration failed",message)
                    if(ok){registrationProof=null;registrationName="";registrationStudentNumber="";go("welcome")}
                }}
            }
        }
        button("Back to sign in",primary=false){go("welcome")}
    }
    private fun home(){
        val header=row();val labels=col();labels.addView(text("EMERIS PRETORIA  /  CAMPUS LIFE",10f,muted,true).apply{letterSpacing=.12f});space(labels,6);labels.addView(text("Hi, ${repo.profile.name.substringBefore(" ")}",25f,ink,true));header.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(iconButton("bell",s(R.string.notifications)){go("notifications")},LinearLayout.LayoutParams(dp(43),dp(43)).apply{rightMargin=dp(10)})
        header.addView(text(initials(repo.profile.name),13f,Color.WHITE,true).apply{gravity=Gravity.CENTER;background=surface(green,dp(30).toFloat());setOnClickListener{go("settings")}},LinearLayout.LayoutParams(dp(43),dp(43)));body.addView(header);space(h=22)
        val eco=col().apply{background=surface(green,dp(22).toFloat());setPadding(dp(20),dp(20),dp(20),dp(20));setOnClickListener{go("impact")}}
        val er=row();er.addView(RelayIcon(this,"leaf",Color.parseColor("#ACD2B4")),LinearLayout.LayoutParams(dp(18),dp(18)).apply{rightMargin=dp(8)});er.addView(text(s(R.string.eco_impact),12f,Color.parseColor("#ACD2B4"),true),LinearLayout.LayoutParams(0,-2,1f));er.addView(RelayIcon(this,"arrow",Color.parseColor("#ACD2B4")),LinearLayout.LayoutParams(dp(18),dp(18)));eco.addView(er);space(eco,18)
        val stats=row();val co=col();co.addView(text(String.format(Locale.US,"%.2f",repo.profile.ecoScore)+" kg",28f,Color.WHITE,true));space(co,5);co.addView(text(s(R.string.co2_saved),11f,Color.parseColor("#CEE0D0")));stats.addView(co,LinearLayout.LayoutParams(0,-2,1f));val po=col();po.addView(text((repo.profile.ecoScore*100).roundToInt().toString(),28f,Color.WHITE,true));space(po,5);po.addView(text(s(R.string.eco_points),11f,Color.parseColor("#CEE0D0")));stats.addView(po,LinearLayout.LayoutParams(0,-2,1f));eco.addView(stats);space(eco,16);eco.addView(text("Small journeys. A lighter footprint.",11f,Color.parseColor("#ACD2B4")));body.addView(eco,LinearLayout.LayoutParams(-1,-2));space(h=20)
        val quick=row();quick.addView(quickLink("car",s(R.string.carpool)){go("carpool")},LinearLayout.LayoutParams(0,dp(51),1f).apply{rightMargin=dp(10)});quick.addView(quickLink("shop",s(R.string.marketplace)){go("market")},LinearLayout.LayoutParams(0,dp(51),1f));body.addView(quick);space(h=20)
        val chips=row();listOf(s(R.string.deliveries),s(R.string.requests),s(R.string.my_routes)).forEachIndexed{i,label->chips.addView(text(label,12f,if(filter==i)Color.WHITE else muted,true).apply{gravity=Gravity.CENTER;setPadding(dp(9),dp(11),dp(9),dp(11));background=surface(if(filter==i)green else cardColor,dp(30).toFloat(),if(filter==i)null else border);setOnClickListener{filter=i;render()}},LinearLayout.LayoutParams(0,-2,1f).apply{if(i<2)rightMargin=dp(7)})};body.addView(chips);space(h=14)
        if(filter==2){routes();return}
        val tasks=repo.deliveries().filter{if(filter==1)it.isMine&&it.status!="COMPLETED"&&it.status!="CANCELLED" else !it.isMine&&it.status=="ACTIVE"}
        val mc=card(padding=9);mc.addView(CampusMap(this,dark).apply{deliveries=tasks;onTask={openDelivery(it.id)}},LinearLayout.LayoutParams(-1,dp(166)));mc.addView(text("${tasks.size} ${s(R.string.routes_nearby)} · Sample campus",10f,muted).apply{setPadding(dp(5),dp(10),dp(5),dp(4))})
        space(h=10);section(if(filter==1) "Your requests" else s(R.string.nearby_tasks),"+ "+s(R.string.create_request)){startWizard()}
        if(tasks.isEmpty())empty(s(R.string.no_tasks),"You’re all caught up. Post a request or offer a route.") else tasks.forEach{deliveryCard(it)}
        button("+  "+s(R.string.create_request)){startWizard()};demoStrip()
    }
    private fun initials(name:String)=name.split(" ").filter{it.isNotBlank()}.take(2).joinToString(""){it.take(1).uppercase()}
    private fun quickLink(kind:String,label:String,action:()->Unit):LinearLayout=row().apply{background=surface(cardColor,dp(14).toFloat(),border);gravity=Gravity.CENTER;addView(RelayIcon(this@MainActivity,kind,if(dark)Color.WHITE else green),LinearLayout.LayoutParams(dp(21),dp(21)).apply{rightMargin=dp(10)});addView(text(label,13f,ink,true));setOnClickListener{action()};contentDescription=label}
    private fun deliveryCard(d:Delivery){
        card().apply{setOnClickListener{openDelivery(d.id)};isFocusable=true;contentDescription="${d.description}, ${d.statusLabel}, ${money(d.reward)}"
            val r=row();r.addView(text(d.description,15f,ink,true),LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(8)});r.addView(pill(money(d.reward),mutedGold,Color.parseColor("#FBF4E2")));addView(r);space(this,12)
            addView(text(CampusBuildings.name(d.pickupId)+" → "+CampusBuildings.name(d.dropoffId),12f,muted));space(this,10)
            val meta=row();meta.addView(RelayIcon(this@MainActivity,"leaf",Color.parseColor("#54976C")),LinearLayout.LayoutParams(dp(13),dp(13)).apply{rightMargin=dp(4)});meta.addView(text("${maxOf(2,(d.distanceKm*15).roundToInt())} min walk  ·  ${d.weightCategory}",11f,muted),LinearLayout.LayoutParams(0,-2,1f));if(d.status!="ACTIVE")meta.addView(pill(d.statusLabel));addView(meta)
        }
    }
    private fun bottomNav(){
        val nav=row().apply{setPadding(dp(8),dp(8),dp(8),dp(8));background=surface(cardColor)}
        listOf(Triple("home","home",s(R.string.home)),Triple("track","pin",s(R.string.track)),Triple("settings","settings",s(R.string.settings))).forEach{(page,icon,label)->
            val active=screen==page;val color=if(active)if(dark)Color.parseColor("#B5D4AE") else green else muted
            val c=col().apply{gravity=Gravity.CENTER;setPadding(0,dp(7),0,dp(6));if(active)background=surface(pale,dp(14).toFloat());addView(RelayIcon(this@MainActivity,icon,color),LinearLayout.LayoutParams(dp(22),dp(22)));space(this,5);addView(text(label,10f,color,active));setOnClickListener{go(page)};contentDescription=label;isFocusable=true}
            nav.addView(c,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(6);marginEnd=dp(6)})
        };shell.addView(nav,LinearLayout.LayoutParams(-1,-2))
    }
    private fun startWizard(){wizardStep=0;draftDescription="";draftStart=System.currentTimeMillis();draftEnd=draftStart+7200000;go("wizard")}
    private fun field(label:String,hint:String,value:String="",numeric:Boolean=false,parent:LinearLayout=body):EditText{
        addLabel(label,parent);return EditText(this).apply{setText(value);this.hint=hint;textSize=15f;setTextColor(ink);setHintTextColor(muted);background=surface(cardColor,dp(13).toFloat(),border);setPadding(dp(16),dp(14),dp(16),dp(14));minHeight=dp(53);inputType=if(numeric)InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;contentDescription=label;parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)})}
    }
    private fun select(label:String,values:List<String>,selected:Int=0,parent:LinearLayout=body):Spinner{
        addLabel(label,parent);val wrapper=FrameLayout(this).apply{background=surface(cardColor,dp(13).toFloat(),border)}
        val sp=Spinner(this).apply{adapter=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,values){override fun getView(position:Int,convertView:View?,parent:ViewGroup):View=super.getView(position,convertView,parent).apply{(this as TextView).setTextColor(ink);textSize=15f};override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View=super.getDropDownView(position,convertView,parent).apply{(this as TextView).setTextColor(Color.rgb(32,47,40));setBackgroundColor(Color.WHITE)}}.also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)};setSelection(selected.coerceAtLeast(0));contentDescription=label}
        wrapper.addView(sp,FrameLayout.LayoutParams(-1,dp(55)).apply{leftMargin=dp(8);rightMargin=dp(8)});parent.addView(wrapper,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)});return sp
    }
    private fun building(label:String,id:String,parent:LinearLayout=body)=select(label,CampusBuildings.all.map{it.name},CampusBuildings.all.indexOfFirst{it.id==id},parent)
    private fun wizard(){
        heading(s(R.string.new_delivery),"A little help, on someone’s way.")
        val steps=row();listOf(s(R.string.locations),s(R.string.item_details),s(R.string.reward_confirm)).forEachIndexed{i,t->val c=col().apply{gravity=Gravity.CENTER;addView(text(if(i<wizardStep)"✓" else (i+1).toString(),14f,if(i<=wizardStep)Color.WHITE else muted,true).apply{gravity=Gravity.CENTER;background=surface(if(i<=wizardStep)green else border,dp(30).toFloat())},LinearLayout.LayoutParams(dp(30),dp(30)));space(this,8);addView(text(t,10f,if(i==wizardStep)ink else muted,i==wizardStep).apply{gravity=Gravity.CENTER})};steps.addView(c,LinearLayout.LayoutParams(0,dp(70),1f))};body.addView(steps);space(h=28)
        when(wizardStep){
            0->{val pickup=building(s(R.string.pickup_building),draftPickup);val dropoff=building(s(R.string.dropoff_building),draftDropoff)
                pickup.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long){draftPickup=CampusBuildings.all[position].id};override fun onNothingSelected(p:AdapterView<*>?) {}}
                dropoff.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long){draftDropoff=CampusBuildings.all[position].id};override fun onNothingSelected(p:AdapterView<*>?) {}}
                var start=draftStart;var end=draftEnd
                dateSelector("Window starts",start){start=it;draftStart=it};dateSelector("Window ends",end){end=it;draftEnd=it}
                button(s(R.string.next)){attempt{draftPickup=CampusBuildings.all[pickup.selectedItemPosition].id;draftDropoff=CampusBuildings.all[dropoff.selectedItemPosition].id;require(draftPickup!=draftDropoff){"Choose different pickup and drop-off buildings."};require(end>start){"The window must end after it starts."};draftStart=start;draftEnd=end;wizardStep=1;render()}}
            }
            1->{val description=field(s(R.string.item_description),"e.g. Textbook & lecture notes",draftDescription);description.minLines=2;description.doAfterTextChanged{draftDescription=it.toString()}
                addLabel(s(R.string.weight));val weights=row();listOf(1.0 to "Small / Book",5.0 to "Medium / Bag",10.0 to "Large / Box").forEach{(kg,label)->weights.addView(text(label,11f,if(draftWeight==kg)Color.WHITE else ink,true).apply{gravity=Gravity.CENTER;minHeight=dp(60);background=surface(if(draftWeight==kg)green else cardColor,dp(12).toFloat(),border);setOnClickListener{draftDescription=description.text.toString();draftWeight=kg;render()}},LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(5)})};body.addView(weights);space(h=12);body.addView(text("Small ≤ 1 kg   ·   Medium ≤ 5 kg   ·   Large ≤ 10 kg",11f,muted));space(h=22)
                button(s(R.string.next)){attempt{require(description.text.toString().trim().length in 3..200){"Describe your item in 3–200 characters."};draftDescription=description.text.toString().trim();wizardStep=2;render()}}
                button(s(R.string.back),primary=false){draftDescription=description.text.toString();wizardStep=0;render()}
            }
            else->{card().apply{addView(text(draftDescription,20f,ink,true));space(this,16);addView(text(CampusBuildings.name(draftPickup)+"\n↓\n"+CampusBuildings.name(draftDropoff),14f,muted));space(this,16);addView(pill("${draftWeight.toInt()} kg  ·  ${time(draftStart)} – ${time(draftEnd)}"))}
                addLabel("Incentive tip");val amount=text(money(draftReward),32f,green,true);body.addView(amount)
                body.addView(SeekBar(this).apply{max=100;progress=draftReward.toInt();contentDescription="Incentive tip";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){draftReward=p.toDouble();amount.text=money(draftReward)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})},LinearLayout.LayoutParams(-1,dp(52)))
                body.addView(text("A thank-you for the student making your delivery. Local demo: no money is charged.",12f,muted));space(h=20)
                button(s(R.string.post_request)){attempt{val d=repo.createDelivery(draftDescription,draftPickup,draftDropoff,draftWeight,draftReward,draftStart,draftEnd);openDelivery(d.id)}}
                button(s(R.string.back),primary=false){wizardStep=1;render()}
            }
        }
    }
    private fun dateSelector(label:String,initial:Long,parent:LinearLayout=body,onChange:(Long)->Unit){
        addLabel(label,parent);var chosen=initial
        val t=text(date(chosen),14f,ink,true).apply{setPadding(dp(16),dp(15),dp(16),dp(15));background=surface(cardColor,dp(13).toFloat(),border)}
        t.setOnClickListener{val cal=Calendar.getInstance().apply{timeInMillis=chosen};DatePickerDialog(this,{_,y,m,d->cal.set(y,m,d);TimePickerDialog(this,{_,h,min->cal.set(Calendar.HOUR_OF_DAY,h);cal.set(Calendar.MINUTE,min);cal.set(Calendar.SECOND,0);chosen=cal.timeInMillis;t.text=date(chosen);onChange(chosen)},cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show()},cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()};parent.addView(t,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)})
    }
    private fun openDelivery(id:String){selectedDelivery=id;go("detail")}
    private fun details(){
        val d=repo.deliveries().firstOrNull{it.id==selectedDelivery}?:run{go("home");return}
        heading(s(R.string.request_details),"#"+d.id.takeLast(8).uppercase());body.addView(pill(d.statusLabel));space(h=18);body.addView(text(d.description,28f,ink,true));space(h=18)
        val mc=card(padding=8);mc.addView(CampusMap(this,dark).apply{deliveries=listOf(d)},LinearLayout.LayoutParams(-1,dp(180)))
        card().apply{val rt=row();rt.addView(text(money(d.reward),26f,ink,true),LinearLayout.LayoutParams(0,-2,1f));rt.addView(pill(d.weightCategory));addView(rt);space(this,18);addView(text("PICKUP",10f,muted,true));space(this,5);addView(text(CampusBuildings.name(d.pickupId),16f,ink,true));space(this,14);addView(text("DROP-OFF",10f,muted,true));space(this,5);addView(text(CampusBuildings.name(d.dropoffId),16f,ink,true));space(this,14);addView(text("${date(d.windowStart)} – ${time(d.windowEnd)}",12f,muted))}
        val steps=listOf("Posted","Matched","Picked up","Delivered");val active=when(d.status){"MATCHED"->1;"IN_TRANSIT"->2;"COMPLETED"->3;else->0};val timeline=row();steps.forEachIndexed{i,label->timeline.addView(text((if(i<=active)"●" else "○")+"\n"+label,11f,if(i<=active)if(dark)Color.parseColor("#AED6B5") else green else muted,i<=active).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,dp(48),1f))};body.addView(timeline);space(h=16)
        card().apply{addView(text(if(d.courierId.isNotEmpty())d.courierName else d.requesterName,16f,ink,true));space(this,5);addView(text(if(d.courierId.isNotEmpty())s(R.string.courier) else "Requester · local demo community",12f,muted))}
        when(d.status){
            "ACTIVE"->{if(!d.isMine)button(s(R.string.accept_delivery)){attempt{repo.acceptDelivery(d.id);render()}} else{section("Couriers on your way");val matches=repo.matches(d.id);if(matches.isEmpty())empty("No route matches yet","Your request is saved. Try a wider delivery window.") else matches.forEach{route->card().apply{addView(text(route.courierName,15f,ink,true));space(this,6);addView(text("${CampusBuildings.name(route.startId)} → ${CampusBuildings.name(route.endId)}",12f,muted));space(this,6);addView(text("${route.transport} · ${route.capacityKg.toInt()} kg capacity",11f,muted));button("Choose courier",this){attempt{repo.bookCourier(d.id,route.id);render()}}}}}}
            "MATCHED"->button(s(R.string.confirm_pickup)){attempt{repo.markPickedUp(d.id);render()}}
            "IN_TRANSIT"->{card().apply{addView(text("Demo recipient code",12f,muted));space(this,7);addView(ImageView(this@MainActivity).apply{setImageBitmap(HandoffQr.bitmap(d.handoffCode));contentDescription="Demo recipient handoff QR code";setBackgroundColor(Color.WHITE);setPadding(dp(8),dp(8),dp(8),dp(8))},LinearLayout.LayoutParams(dp(160),dp(160)).apply{gravity=Gravity.CENTER_HORIZONTAL});space(this,12);addView(text(d.handoffCode,28f,ink,true).apply{letterSpacing=.2f});space(this,8);addView(text("For this local walkthrough only. In a live delivery, the recipient shares their code at handoff.",11f,muted))};button(s(R.string.confirm_handoff)){handoff(d)}}
            "COMPLETED"->{card().apply{addView(text("Delivery complete",20f,ink,true));space(this,7);addView(text("+${CampusRules.deliveryCo2(d.distanceKm)} kg CO₂ saved · counted once",13f,muted))}}
        }
        if(d.status!="ACTIVE")button(s(R.string.messages),primary=false){go("chat")}
        if(d.isMine&&d.status in listOf("ACTIVE","MATCHED"))button("Cancel request",primary=false){AlertDialog.Builder(this).setTitle("Cancel this request?").setNegativeButton(s(R.string.back),null).setPositiveButton("Cancel request"){_,_->attempt{repo.cancelDelivery(d.id);go("track")}}.show()}
        demoStrip()
    }
    private fun handoff(d:Delivery){
        val box=col().apply{setPadding(dp(22),dp(10),dp(22),0)};val code=field(s(R.string.verification_code),"6-digit recipient code",parent=box)
        val dialog=AlertDialog.Builder(this).setTitle(s(R.string.confirm_handoff)).setView(box).setNeutralButton("Scan QR"){_,_->handoffScanner.launch()}.setNegativeButton(s(R.string.cancel),null).setPositiveButton(s(R.string.confirm_handoff),null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{attempt{
            val entered=code.text.toString().trim();require(entered==d.handoffCode){"That code doesn’t match. Ask the recipient to check it."}
            val finish={attempt{repo.completeDelivery(d.id,entered);dialog.dismiss();render()}}
            if(repo.preferences.biometricLock)BiometricGate.authenticate(this,"Confirm delivery handoff",finish){message->notice("Biometric confirmation",message)}else finish()
        }}};dialog.show()
    }
    private fun track(){heading(s(R.string.your_journey),"Your requests, deliveries, and reservations.",false);demoStrip();val all=repo.deliveries().filter{it.isMine||it.isMyJob};val active=all.filter{it.status!="COMPLETED"&&it.status!="CANCELLED"};section(s(R.string.active)+" · "+active.size);if(active.isEmpty())empty("Nothing on the way yet","Accept a nearby delivery or create your first request.")else active.forEach{deliveryCard(it)}
        val booked=repo.rides().filter{it.bookedByMe};if(booked.isNotEmpty()){section("Booked rides");booked.forEach{rideCard(it)}}
        val purchases=repo.listings().filter{it.purchasedByMe};if(purchases.isNotEmpty()){section("Reserved items");purchases.forEach{listingCard(it)}}
        section(s(R.string.completed));all.filter{it.status=="COMPLETED"||it.status=="CANCELLED"}.forEach{deliveryCard(it)}
    }
    private fun routes(){section(s(R.string.my_routes));repo.routes().filter{it.courierId=="me"}.let{list->if(list.isEmpty())empty("Make your walk count","Add your route and help with deliveries along the way.");list.forEach{r->card().apply{addView(text(CampusBuildings.name(r.startId)+" → "+CampusBuildings.name(r.endId),16f,ink,true));space(this,8);addView(text("${r.transport} · ${time(r.windowStart)}–${time(r.windowEnd)} · ${r.capacityKg} kg",12f,muted))}}};button("+ "+s(R.string.route_offer)){routeForm()};demoStrip()}
    private fun routeForm(){form("Offer your route"){c,finish->val from=building(s(R.string.origin),"engineering",c);val to=building(s(R.string.destination),"library",c);val cap=field("Capacity (kg)","Up to 30 kg","5",true,c);val transport=select("Getting around",listOf("Walking","Cycling"),parent=c);var start=System.currentTimeMillis();var end=start+7200000;dateSelector("Available from",start,c){start=it};dateSelector("Available until",end,c){end=it};button(s(R.string.save),c){attempt{repo.createRoute(CampusBuildings.all[from.selectedItemPosition].id,CampusBuildings.all[to.selectedItemPosition].id,cap.text.toString().toDoubleOrNull()?:0.0,start,end,transport.selectedItem.toString());finish();render()}}}}
    private fun form(title:String,build:(LinearLayout,()->Unit)->Unit){val content=col().apply{setPadding(dp(22),dp(14),dp(22),dp(18))};val sc=ScrollView(this).apply{addView(content)};val dialog=AlertDialog.Builder(this).setTitle(title).setView(sc).setNegativeButton(s(R.string.cancel),null).create();build(content){dialog.dismiss()};dialog.show()}
    private fun carpool(){heading(s(R.string.carpool),"Share the journey. Split the footprint.");val from=field(s(R.string.origin),"e.g. Hatfield");val to=field(s(R.string.destination),"e.g. Brooklyn");var day:Long?=null;val dateButton=text("Any date  ⌄",14f,ink,true).apply{setPadding(dp(15),dp(14),dp(15),dp(14));background=surface(cardColor,dp(12).toFloat(),border)};dateButton.setOnClickListener{val cal=Calendar.getInstance();DatePickerDialog(this,{_,y,m,d->cal.set(y,m,d,0,0,0);cal.set(Calendar.MILLISECOND,0);day=cal.timeInMillis/86400000;dateButton.text=SimpleDateFormat("EEE, d MMM",Locale.getDefault()).format(cal.time)},cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()};body.addView(dateButton);space(h=8)
        val results=col();button(s(R.string.search)){results.removeAllViews();val matches=repo.searchRides(from.text.toString(),to.text.toString(),day);if(matches.isEmpty())results.addView(text(s(R.string.no_results),15f,muted));matches.forEach{rideCard(it,results)}};button("+ "+s(R.string.offer_ride),primary=false){rideForm()};space(h=24);section("Available rides");body.addView(results);repo.rides().filter{it.status=="ACTIVE"||it.bookedByMe}.sortedBy{it.departure}.forEach{rideCard(it,results)};demoStrip()}
    private fun rideCard(ride:RideOffer,parent:LinearLayout=body){card(parent).apply{val r=row();r.addView(text("${ride.origin} → ${ride.destination}",17f,ink,true),LinearLayout.LayoutParams(0,-2,1f));r.addView(pill(money(ride.price)));addView(r);space(this,12);addView(text(date(ride.departure),13f,muted));space(this,8);addView(text("${ride.driverName} · ${ride.vehicle} · ${ride.availableSeats} seats left",12f,muted));if(ride.bookedByMe){space(this,12);addView(pill(if(ride.status=="COMPLETED")s(R.string.completed) else s(R.string.booking_confirmed)));if(ride.status!="COMPLETED"){button("Complete demo ride",this){attempt{repo.completeRide(ride.id);render()}};button("Cancel seat",this,false){attempt{repo.cancelSeat(ride.id);render()}}}}else if(ride.driverId!="me"&&ride.availableSeats>0)button("Book a seat",this){AlertDialog.Builder(this@MainActivity).setTitle("Book this seat?").setMessage("${ride.origin} → ${ride.destination}\n${date(ride.departure)}\n${money(ride.price)} per seat\n\nLocal reservation only; no payment is collected.").setNegativeButton(s(R.string.cancel),null).setPositiveButton("Book seat"){_,_->attempt{repo.bookSeat(ride.id);render()}}.show()}}}
    private fun rideForm(){form(s(R.string.offer_ride)){c,finish->val origin=field(s(R.string.origin),"Departure area",parent=c);val dest=field(s(R.string.destination),"Destination",parent=c);var departure=System.currentTimeMillis()+3600000;dateSelector(s(R.string.departure),departure,c){departure=it};val seats=field(s(R.string.seats),"1–8","3",true,c);val type=select(s(R.string.vehicle),listOf("CAR","SUV","VAN"),parent=c);val price=field(s(R.string.price_per_seat),"Cost share in rand","20",true,c);button(s(R.string.offer_ride),c){attempt{repo.createRide(origin.text.toString(),dest.text.toString(),departure,seats.text.toString().toIntOrNull()?:0,type.selectedItem.toString(),price.text.toString().toDoubleOrNull()?:-1.0);finish();render()}}}}
    private fun market(){heading(s(R.string.marketplace),"Good things deserve another chapter.");val search=field(s(R.string.search),"Books, electronics, and more");val list=col();val categories=listOf("All","BOOKS","ELECTRONICS","CLOTHING","FURNITURE","OTHER");val category=select("Category",categories);button(s(R.string.search)){list.removeAllViews();val items=repo.listings().filter{it.status=="ACTIVE"&&(category.selectedItemPosition==0||it.category==category.selectedItem.toString())&&(it.title+it.description).contains(search.text.toString(),true)};if(items.isEmpty())list.addView(text(s(R.string.no_results),15f,muted));items.forEach{listingCard(it,list)}};button("+ "+s(R.string.sell),primary=false){listingForm()};space(h=24);section("The campus edit");body.addView(list);repo.listings().filter{it.status=="ACTIVE"}.forEach{listingCard(it,list)};demoStrip()}
    private fun listingCard(item:Listing,parent:LinearLayout=body){card(parent).apply{val r=row();val kind=if(item.category=="BOOKS")"box" else "shop";r.addView(FrameLayout(this@MainActivity).apply{background=surface(pale,dp(14).toFloat());addView(RelayIcon(this@MainActivity,kind,if(dark)Color.WHITE else green),FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER))},LinearLayout.LayoutParams(dp(65),dp(70)).apply{rightMargin=dp(15)});val c=col();c.addView(text(item.title,16f,ink,true));space(c,6);c.addView(text(item.condition.replace('_',' ').lowercase().replaceFirstChar{it.uppercase()}+" · "+item.sellerName,11f,muted));space(c,7);c.addView(text(money(item.price),18f,if(dark)Color.WHITE else green,true));r.addView(c,LinearLayout.LayoutParams(0,-2,1f));addView(r);space(this,12);addView(text(item.description,12f,muted));if(item.status=="ACTIVE"&&item.sellerId!="me")button("Reserve item",this,false){AlertDialog.Builder(this@MainActivity).setTitle("Reserve ${item.title}?").setMessage("Agree collection with the seller. This local demo records a reservation. Confirm collection in Track to count its reuse impact. It does not send payment or contact a real seller.").setNegativeButton(s(R.string.cancel),null).setPositiveButton("Reserve"){_,_->attempt{repo.purchaseListing(item.id);render()}}.show()}else{space(this,10);addView(pill(if(item.purchasedByMe)if(item.status=="SOLD")"Collected" else "Reserved by you" else "Your listing"));if(item.purchasedByMe&&item.status=="PENDING")button("Confirm collection",this){attempt{repo.completePurchase(item.id);render()}}}}}
    private fun listingForm(){form("Pass something on"){c,finish->val title=field("Item name","e.g. Programming textbook",parent=c);val desc=field("Description","Tell buyers about the item",parent=c);val price=field("Price (R)","0.00",numeric=true,parent=c);val category=select("Category",listOf("BOOKS","ELECTRONICS","CLOTHING","FURNITURE","OTHER"),parent=c);val condition=select("Condition",listOf("NEW","LIKE_NEW","GOOD","FAIR"),parent=c);button("Post listing",c){attempt{repo.createListing(title.text.toString(),desc.text.toString(),price.text.toString().toDoubleOrNull()?:-1.0,category.selectedItem.toString(),condition.selectedItem.toString());finish();render()}}}}
    private fun chat(){val d=repo.deliveries().firstOrNull{it.id==selectedDelivery}?:run{go("track");return};heading(s(R.string.messages),d.description);body.addView(text("Local conversation · messages are saved on this device",11f,muted));space(h=18);val history=repo.messages(d.id);if(history.isEmpty())empty("Start a conversation","Share pickup details or a helpful landmark.");history.forEach{m->card().apply{addView(text(m.senderName+" · "+time(m.sentAt),11f,muted,true));space(this,7);addView(text(m.text,15f,ink))}};val input=field("Message","Type a message…");button(s(R.string.send)){attempt{repo.sendMessage(d.id,input.text.toString());render()}}}
    private fun impact(){if(screen!="impact"){go("impact");return};heading(s(R.string.eco_impact),"Every greener choice adds up.");card().apply{addView(RelayIcon(this@MainActivity,"leaf",if(dark)Color.WHITE else green),LinearLayout.LayoutParams(dp(35),dp(35)));space(this,16);addView(text(String.format(Locale.US,"%.2f kg",repo.profile.ecoScore),38f,ink,true));space(this,8);addView(text(s(R.string.co2_saved)+" · "+(repo.profile.ecoScore*100).roundToInt()+" "+s(R.string.eco_points),13f,muted))};body.addView(text("Estimates use 171 g CO₂/km for walking deliveries, 85.5 g/km for a shared ride, and category-based reuse estimates. These are illustrative, not verified carbon offsets.",12f,muted));space(h=22);section(s(R.string.eco_history));val activities=repo.activities();if(activities.isEmpty())empty("Your first green step starts here","Complete a delivery, collect a reused item, or share a ride to see your impact.");activities.forEach{a->card().apply{val r=row();r.addView(text(a.title,15f,ink,true),LinearLayout.LayoutParams(0,-2,1f));r.addView(pill("+${a.co2Kg} kg"));addView(r);space(this,8);addView(text(a.detail,12f,muted));space(this,6);addView(text(date(a.createdAt),10f,muted))}}}
    private fun settings(){heading(s(R.string.settings),"Your campus. Your way.",false)
        val p=repo.profile;card().apply{val r=row();r.addView(avatar(p.name,p.avatarUri),LinearLayout.LayoutParams(dp(66),dp(66)).apply{rightMargin=dp(16)});val c=col();c.addView(text(p.name,20f,ink,true));space(c,6);c.addView(text(s(R.string.local_account),11f,muted));space(c,6);c.addView(text(CampusBuildings.name(p.buildingId),11f,muted));r.addView(c);addView(r);space(this,16);addView(text(p.bio,13f,muted));button(s(R.string.edit_profile),this,false){profileForm()};button("Change profile photo",this,false){photoPicker.launch(arrayOf("image/*"))}}
        space(h=12);addLabel("YOUR IMPACT");val stats=card();stats.addView(text("${p.completedDeliveries} deliveries  ·  ${String.format(Locale.US,"%.2f",p.ecoScore)} kg CO₂",17f,ink,true));space(stats,8);stats.addView(text(if(p.completedDeliveries>=5)"Campus Eco-Hero · 5+ deliveries" else "Your first badge awaits · complete 5 deliveries",12f,muted));stats.setOnClickListener{go("impact")}
        space(h=12);addLabel("SECURITY");toggle(s(R.string.biometric_lock),"Confirm sensitive handoffs on this device",repo.preferences.biometricLock){checked->if(checked&&!BiometricGate.isAvailable(this)){notice("Biometrics unavailable","Enroll a fingerprint or face in your device settings first.");render()}else{repo.updatePreferences(repo.preferences.copy(biometricLock=checked))}}
        space(h=20);addLabel(s(R.string.notifications));toggle(s(R.string.notifications),"Remote push requires Firebase configuration",repo.preferences.notifications){repo.updatePreferences(repo.preferences.copy(notifications=it));PushSupport.setEnabled(this,it)}
        space(h=20);addLabel(s(R.string.preferences));card().apply{val r=row();r.addView(text(s(R.string.language),15f,ink,true),LinearLayout.LayoutParams(0,-2,1f));r.addView(text(when(repo.preferences.language){"zu"->"isiZulu";"af"->"Afrikaans";else->"English"}+"  ›",13f,muted));addView(r);setOnClickListener{chooseLanguage()}}
        toggle(s(R.string.dark_mode),"",repo.preferences.darkMode){repo.updatePreferences(repo.preferences.copy(darkMode=it));delegate.localNightMode=if(it)AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO;render()}
        button("Google & API connection",primary=false){go("services")};button(s(R.string.sign_out),primary=false){localPrefs.edit().putBoolean("demo_session",false).apply();googleSso.signOut();go("welcome")};demoStrip()
    }
    private fun toggle(title:String,description:String,checked:Boolean,change:(Boolean)->Unit){card().apply{val r=row();val c=col();c.addView(text(title,14f,ink,true));if(description.isNotEmpty()){space(c,6);c.addView(text(description,11f,muted))};r.addView(c,LinearLayout.LayoutParams(0,-2,1f));r.addView(Switch(this@MainActivity).apply{isChecked=checked;contentDescription=title;setOnCheckedChangeListener{_,isChecked->change(isChecked)}});addView(r)}}
    private fun chooseLanguage(){val values=listOf("English","isiZulu","Afrikaans");val tags=listOf("en","zu","af");AlertDialog.Builder(this).setTitle(s(R.string.language)).setSingleChoiceItems(values.toTypedArray(),tags.indexOf(repo.preferences.language)){dialog,index->repo.updatePreferences(repo.preferences.copy(language=tags[index]));localPrefs.edit().putString("language",tags[index]).apply();dialog.dismiss();recreate()}.setNegativeButton(s(R.string.cancel),null).show()}
    private fun profileForm(){form(s(R.string.edit_profile)){c,finish->val name=field(s(R.string.name),"Your full name",repo.profile.name,parent=c);val bio=field(s(R.string.bio),"A little about you",repo.profile.bio,parent=c);val location=building(s(R.string.campus_location),repo.profile.buildingId,c);button(s(R.string.save),c){attempt{repo.updateProfile(name.text.toString(),bio.text.toString(),CampusBuildings.all[location.selectedItemPosition].id);finish();render()}}}}
    private fun notifications(){
        heading(s(R.string.notifications),"Updates from your local activity.")
        val updates=repo.deliveries().filter{it.isMine||it.isMyJob}
        if(updates.isEmpty())empty("You’re all caught up","Delivery updates will appear here.")
        updates.forEach{d->card().apply{addView(text(d.statusLabel,15f,ink,true));space(this,7);addView(text(d.description,13f,muted));setOnClickListener{openDelivery(d.id)}}}
        body.addView(text("These are on-device updates. Remote push activates only with a configured Firebase project and university account.",12f,muted))
    }
    private fun avatar(name:String,uri:String):View {
        if(uri.isNotBlank())try{
            val parsed=Uri.parse(uri)
            val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            contentResolver.openInputStream(parsed)?.use{android.graphics.BitmapFactory.decodeStream(it,null,options)}
            var sample=1;while(options.outWidth/sample>512||options.outHeight/sample>512)sample*=2
            options.inJustDecodeBounds=false;options.inSampleSize=sample
            val bitmap=contentResolver.openInputStream(parsed)?.use{android.graphics.BitmapFactory.decodeStream(it,null,options)}
            if(bitmap!=null)return ImageView(this).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP;background=surface(green,dp(40).toFloat());clipToOutline=true;contentDescription="Profile photo"}
        }catch(_:Exception){}
        return text(initials(name),22f,Color.WHITE,true).apply{gravity=Gravity.CENTER;background=surface(green,dp(40).toFloat())}
    }
    private fun services(){heading("Connect CampusRelay","Sign in with Google through Firebase Authentication.");card().apply{addView(text("Google Sign-In",20f,ink,true));space(this,10);addView(text("Firebase configuration comes from app/google-services.json. Enter only the deployed CampusRelay API address here. Demo records remain on this device.",13f,muted))}
        val cfg=ServiceConfig(this)
        if(GoogleSession(this).read()?.isAuthorized==true)button("Open connected workspace"){openUniversity()}
        val api=field("CampusRelay API URL","https://your-api.example.com",cfg.apiBaseUrl)
        button("Save connection"){attempt{cfg.configure(api.text.toString().trim());notice("Connection saved","Use Continue with Google on the welcome screen. Firebase authenticates the Google account and the API validates its Firebase ID token.")}}
        button("Continue with Google",primary=false){if(cfg.configured)googleSso.start{ok,message->runOnUiThread{if(ok)openUniversity()else notice("Google sign-in",message)}}else notice("API URL needed","Enter the deployed HTTPS CampusRelay API URL first.")};demoStrip()
    }
}
