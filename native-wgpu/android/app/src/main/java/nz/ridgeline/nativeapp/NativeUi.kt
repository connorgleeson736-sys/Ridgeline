package nz.ridgeline.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

/** Shared native equivalents of the Capacitor paper/green/orange controls. */
internal object NativeUi {
    /** Dark colours when the phone is in dark mode (MainActivity sets this before any view). */
    @Volatile var dark=false
    val paper get()=if(dark)Color.rgb(27,31,29) else Color.rgb(250,250,245)
    val ink get()=if(dark)Color.rgb(226,233,228) else Color.rgb(35,61,52)
    val muted get()=if(dark)Color.rgb(152,165,157) else Color.rgb(101,115,105)
    val orange=Color.rgb(232,121,68)
    val line get()=if(dark)Color.rgb(64,73,68) else Color.rgb(214,221,210)
    /** Buttons, fields and map controls. */
    val surface get()=if(dark)Color.rgb(41,47,44) else Color.WHITE
    /** Chips, tabs, the search field and thumbnails. */
    val chip get()=if(dark)Color.rgb(48,56,52) else Color.rgb(236,240,232)
    /** Notes over the map (status, credits, hints). */
    val glass get()=if(dark)Color.argb(235,30,35,33) else Color.argb(235,255,255,255)
    /** Text on a filled (ink) control. */
    val onInk get()=if(dark)Color.rgb(22,26,24) else Color.WHITE
    /** Orange text links. */
    val link get()=if(dark)Color.rgb(238,146,98) else Color.rgb(196,104,46)
    /** Field hints and secondary details. */
    val hint get()=if(dark)Color.rgb(128,140,133) else Color.rgb(132,142,136)
    val detail get()=if(dark)Color.rgb(150,162,155) else Color.rgb(120,132,124)
    fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density+.5f).toInt()
    fun background(context:Context,color:Int=paper,radius:Int=10,stroke:Boolean=false)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(context,radius).toFloat();if(stroke)setStroke(dp(context,1),line)
    }
    fun text(context:Context,value:String,size:Float=13f)=TextView(context).apply{text=value;textSize=size;setTextColor(ink)}
    fun heading(context:Context,value:String)=text(context,value,19f).apply{setTypeface(null,Typeface.BOLD);setPadding(0,dp(context,12),0,dp(context,10))}
    fun button(context:Context,value:String,primary:Boolean=false,action:()->Unit)=Button(context).apply{
        text=value;isAllCaps=false;textSize=12f;minimumHeight=dp(context,44);minimumWidth=0
        // No Material press elevation: its shadow drew dark, uneven corners.
        stateListAnimator=null;elevation=0f
        setTextColor(if(primary)onInk else ink);setPadding(dp(context,9),0,dp(context,9),0)
        background=pressable(context,background(context,if(primary)ink else surface,7,true))
        setOnClickListener{action()}
    }
    /** Visible touch feedback on every control (a ripple over its background). */
    fun pressable(context:Context,content:android.graphics.drawable.Drawable?)=android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(if(dark)Color.argb(50,255,255,255)else Color.argb(40,35,61,52)),content,
        if(content==null)GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(context,8).toFloat()}else null)
    fun column(context:Context)=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    fun space(parent:LinearLayout,height:Int=10){parent.addView(View(parent.context),LinearLayout.LayoutParams(1,dp(parent.context,height)))}
    fun divider(parent:LinearLayout){parent.addView(View(parent.context).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(parent.context,1)).apply{topMargin=dp(parent.context,10);bottomMargin=dp(parent.context,10)})}
}
