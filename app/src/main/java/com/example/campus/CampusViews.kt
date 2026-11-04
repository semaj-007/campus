package com.example.campus

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.example.campus.data.*
import kotlin.math.*

fun Context.dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
fun surface(color: Int, radius: Float = 20f, border: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (border != null) setStroke(1, border)
}

/** Small, scalable line icons, drawn locally with no external assets. */
class RelayIcon(context: Context, private val kind: String, var tint: Int, private val solid: Boolean = false) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save(); c.scale(width / 24f, height / 24f)
        p.color = tint; p.style = Paint.Style.STROKE; p.strokeWidth = 1.65f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fun line(vararg a: Float) { val path = Path(); path.moveTo(a[0],a[1]); for(i in 2 until a.size step 2) path.lineTo(a[i],a[i+1]); c.drawPath(path,p) }
        when(kind) {
            "home" -> {line(3f,11f,12f,3f,21f,11f);line(5f,10f,5f,21f,10f,21f,10f,15f,14f,15f,14f,21f,19f,21f,19f,10f)}
            "pin", "track" -> { val path=Path();path.moveTo(12f,22f);path.cubicTo(9f,18f,4f,13f,4f,9f);path.cubicTo(4f,-1f,20f,-1f,20f,9f);path.cubicTo(20f,13f,15f,18f,12f,22f);c.drawPath(path,p);c.drawCircle(12f,9f,2.5f,p) }
            "leaf", "eco" -> {val path=Path();path.moveTo(3f,21f);path.cubicTo(2f,7f,9f,2f,22f,2f);path.cubicTo(22f,15f,13f,20f,3f,21f);c.drawPath(path,p);line(3f,21f,18f,6f);line(9f,15f,9f,9f);line(13f,11f,18f,11f)}
            "bell" -> {val path=Path();path.moveTo(5f,16f);path.lineTo(7f,13f);path.lineTo(7f,8f);path.cubicTo(7f,2f,17f,2f,17f,8f);path.lineTo(17f,13f);path.lineTo(19f,16f);path.close();c.drawPath(path,p);c.drawArc(10f,17f,14f,21f,0f,180f,false,p)}
            "settings" -> {line(3f,6f,21f,6f);line(3f,12f,21f,12f);line(3f,18f,21f,18f);p.style=Paint.Style.FILL;c.drawCircle(8f,6f,2.5f,p);c.drawCircle(16f,12f,2.5f,p);c.drawCircle(10f,18f,2.5f,p)}
            "box" -> {line(3f,6f,12f,2f,21f,6f,21f,18f,12f,22f,3f,18f,3f,6f,12f,10f,21f,6f);line(12f,10f,12f,22f);line(8f,4f,17f,8f,17f,13f)}
            "car" -> {c.drawRoundRect(3f,10f,21f,18f,2f,2f,p);line(5f,10f,7f,4f,17f,4f,19f,10f);line(6f,18f,6f,21f);line(18f,18f,18f,21f);c.drawCircle(7f,14f,1f,p);c.drawCircle(17f,14f,1f,p)}
            "shop" -> {line(3f,9f,5f,3f,19f,3f,21f,9f,3f,9f);line(5f,10f,5f,21f,19f,21f,19f,10f);line(10f,21f,10f,14f,15f,14f,15f,21f);line(8f,3f,7f,9f);line(16f,3f,17f,9f)}
            "arrow" -> {line(4f,12f,20f,12f);line(14f,6f,20f,12f,14f,18f)}
            "back" -> {line(15f,5f,8f,12f,15f,19f)}
            "plus" -> {line(5f,12f,19f,12f);line(12f,5f,12f,19f)}
            "check" -> line(4f,12f,10f,18f,21f,5f)
            "chat" -> {line(3f,4f,21f,4f,21f,17f,9f,17f,3f,22f,3f,4f);line(7f,9f,17f,9f);line(7f,13f,14f,13f)}
            "clock" -> {c.drawCircle(12f,12f,9f,p);line(12f,6f,12f,12f,16f,14f)}
            "sun" -> {c.drawCircle(12f,12f,4f,p);for(i in 0..7){val a=i*PI/4;line((12+cos(a)*7).toFloat(),(12+sin(a)*7).toFloat(),(12+cos(a)*10).toFloat(),(12+sin(a)*10).toFloat())}}
            "shield" -> {line(12f,2f,21f,6f,20f,15f,17f,20f,12f,23f,7f,20f,4f,15f,3f,6f,12f,2f);line(8f,12f,11f,15f,17f,8f)}
            "route" -> {c.drawCircle(5f,5f,3f,p);c.drawCircle(19f,19f,3f,p);line(5f,8f,5f,15f,13f,15f,13f,5f,19f,5f,19f,16f)}
            "person" -> {c.drawCircle(12f,7f,4f,p);c.drawArc(4f,13f,20f,27f,180f,180f,false,p)}
            else -> {c.drawCircle(12f,12f,9f,p);line(12f,8f,12f,13f);c.drawPoint(12f,17f,p)}
        }
        c.restore()
    }
}

/** An interactive schematic, not a GPS map. Buildings match the route data. */
class CampusMap(context: Context, private val dark: Boolean = false) : View(context) {
    var deliveries: List<Delivery> = emptyList()
    var onTask: ((Delivery) -> Unit)? = null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val positions=listOf(.35f to .50f,.18f to .72f,.23f to .29f,.64f to .63f,.79f to .25f,.56f to .15f)
    init { contentDescription="Campus building schematic. Tap a delivery marker to view its request."; isClickable=true }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        val w=width.toFloat(); val h=height.toFloat()
        fun stroke(color:Int,width:Float){p.color=color;p.style=Paint.Style.STROKE;p.strokeWidth=width;p.strokeCap=Paint.Cap.ROUND}
        p.color=Color.parseColor(if(dark) "#243A33" else "#EBEEE4");p.style=Paint.Style.FILL;c.drawRoundRect(0f,0f,w,h,context.dp(18).toFloat(),context.dp(18).toFloat(),p)
        // Quiet parkland, pathways, and building footprints.
        p.color=Color.parseColor(if(dark) "#304A3B" else "#DDE7D1");c.drawCircle(w*.82f,h*.81f,w*.21f,p);c.drawCircle(w*.1f,h*.03f,w*.24f,p)
        val road=Path().apply{moveTo(-10f,h*.64f);cubicTo(w*.25f,h*.78f,w*.42f,h*.18f,w*1.1f,h*.38f)}
        stroke(Color.parseColor(if(dark) "#526158" else "#FCFCF6"),context.dp(18).toFloat());c.drawPath(road,p)
        stroke(Color.parseColor(if(dark) "#364D42" else "#DADFD2"),context.dp(1).toFloat());c.drawPath(road,p)
        positions.forEachIndexed { i,pos ->
            val x=pos.first*w;val y=pos.second*h
            p.color=Color.parseColor(if(dark) "#537560" else "#C3D6B7");p.style=Paint.Style.FILL
            c.drawRoundRect(x-20,y-14,x+28,y+15,5f,5f,p)
            p.color=Color.parseColor(if(dark) "#D4DFD2" else "#75866C");p.textSize=context.dp(8).toFloat();p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            c.drawText(CampusBuildings.all[i].shortName,x-25,y+context.dp(24),p)
        }
        val visible=deliveries.filter{it.status!="COMPLETED"&&it.status!="CANCELLED"}.take(4)
        visible.forEachIndexed { index,d ->
            val i=CampusBuildings.all.indexOfFirst{it.id==d.pickupId}.coerceAtLeast(0)
            val j=CampusBuildings.all.indexOfFirst{it.id==d.dropoffId}.coerceAtLeast(0)
            val a=positions[i];val b=positions[j]
            stroke(Color.parseColor("#537E62"),context.dp(2).toFloat());p.pathEffect=DashPathEffect(floatArrayOf(6f,8f),0f)
            c.drawLine(a.first*w,a.second*h,b.first*w,b.second*h,p);p.pathEffect=null
            p.style=Paint.Style.FILL;p.color=Color.parseColor("#D5AB28");c.drawCircle(a.first*w,a.second*h,context.dp(10).toFloat(),p)
            p.color=Color.parseColor("#193F32");c.drawCircle(a.first*w,a.second*h,context.dp(3).toFloat(),p)
        }
        p.color=Color.parseColor("#1D4736");p.style=Paint.Style.FILL;c.drawCircle(w*.46f,h*.77f,context.dp(6).toFloat(),p)
        stroke(Color.WHITE,context.dp(2).toFloat());c.drawCircle(w*.46f,h*.77f,context.dp(6).toFloat(),p)
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean {
        if(event.action==android.view.MotionEvent.ACTION_UP){
            performClick()
            val d=deliveries.filter{it.status!="COMPLETED"&&it.status!="CANCELLED"}.minByOrNull{
                val p=positions[CampusBuildings.all.indexOfFirst{b->b.id==it.pickupId}.coerceAtLeast(0)]
                hypot(event.x-p.first*width,event.y-p.second*height)
            }; if(d!=null) onTask?.invoke(d)
        };return true
    }
}
