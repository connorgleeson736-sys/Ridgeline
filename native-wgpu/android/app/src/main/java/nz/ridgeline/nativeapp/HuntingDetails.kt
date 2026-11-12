package nz.ridgeline.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView

/** Details for the hunting permit areas under a tap: name, permit area and
 * type, size, and DOC's page for the permit. DOC's data is a guide only. */
internal object HuntingDetails {
    fun show(activity:Activity,areas:List<HuntingAreas.Area>){
        if(areas.isEmpty())return
        fun dp(v:Int)=NativeUi.dp(activity,v)
        val content=NativeUi.column(activity).apply{setPadding(dp(20),dp(8),dp(20),dp(4))}
        for((i,a) in areas.withIndex()){
            if(i>0)NativeUi.divider(content)
            content.addView(NativeUi.text(activity,a.name.ifBlank{"Hunting permit area"},15f).apply{setTypeface(null,android.graphics.Typeface.BOLD)})
            val facts=listOf(a.type.let{if(it=="Ordinary")"Hunting permit area" else it},a.permitArea,if(a.hectares>0)String.format(java.util.Locale.ROOT,"%,.0f ha",a.hectares)else"").filter{it.isNotBlank()}
            content.addView(NativeUi.text(activity,facts.joinToString("\n"),13f).apply{setTextColor(NativeUi.muted);setPadding(0,dp(2),0,dp(4))})
            if(a.webPage.startsWith("https://"))content.addView(TextView(activity).apply{
                text="Permit and conditions on the DOC website ↗";textSize=13.5f;setTextColor(android.graphics.Color.rgb(196,104,46));setPadding(0,dp(6),0,dp(6));isClickable=true;background=NativeUi.pressable(activity,null)
                setOnClickListener{runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(a.webPage)))}}
            },LinearLayout.LayoutParams(-2,-2))
        }
        content.addView(NativeUi.text(activity,"Department of Conservation data (CC BY 4.0), shown live from DOC. A guide only, not legal boundaries. You need a DOC hunting permit.",11f).apply{setTextColor(NativeUi.muted);setPadding(0,dp(10),0,0)})
        AlertDialog.Builder(activity).setView(android.widget.ScrollView(activity).apply{addView(content)}).setPositiveButton("Close",null).show()
    }
}
