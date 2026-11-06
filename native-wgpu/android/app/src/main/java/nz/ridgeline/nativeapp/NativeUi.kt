package nz.ridgeline.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

/** Shared native equivalents of the Capacitor paper/green/orange controls. */
internal object NativeUi {
    val paper=Color.rgb(250,250,245)
    val ink=Color.rgb(35,61,52)
    val muted=Color.rgb(101,115,105)
    val orange=Color.rgb(232,121,68)
    val line=Color.rgb(214,221,210)
    fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density+.5f).toInt()
    fun background(context:Context,color:Int=paper,radius:Int=10,stroke:Boolean=false)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(context,radius).toFloat();if(stroke)setStroke(dp(context,1),line)
    }
    fun text(context:Context,value:String,size:Float=13f)=TextView(context).apply{text=value;textSize=size;setTextColor(ink)}
    fun heading(context:Context,value:String)=text(context,value,19f).apply{setTypeface(null,Typeface.BOLD);setPadding(0,dp(context,12),0,dp(context,10))}
    fun button(context:Context,value:String,primary:Boolean=false,action:()->Unit)=Button(context).apply{
        text=value;isAllCaps=false;textSize=12f;minimumHeight=dp(context,44);minimumWidth=0
        setTextColor(if(primary)Color.WHITE else ink);setPadding(dp(context,9),0,dp(context,9),0)
        background=background(context,if(primary)ink else Color.WHITE,7,true)
        setOnClickListener{action()}
    }
    fun column(context:Context)=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    fun space(parent:LinearLayout,height:Int=10){parent.addView(View(parent.context),LinearLayout.LayoutParams(1,dp(parent.context,height)))}
    fun divider(parent:LinearLayout){parent.addView(View(parent.context).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(parent.context,1)).apply{topMargin=dp(parent.context,10);bottomMargin=dp(parent.context,10)})}
}
