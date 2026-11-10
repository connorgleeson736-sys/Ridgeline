package nz.ridgeline.nativeapp

import android.graphics.Rect
import android.view.*
import android.widget.*
import kotlin.math.*

/** Two Capacitor stops. Dragging transforms/clips; map resizes only at gesture boundaries. */
internal class NativeSheet(private val root:FrameLayout,private val map:FrameLayout) {
    private val context=root.context
    val panel=NativeUi.column(context).apply{
        background=android.graphics.drawable.GradientDrawable().apply{setColor(NativeUi.paper);val r=NativeUi.dp(context,20).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)};clipToOutline=true;elevation=NativeUi.dp(context,8).toFloat();visibility=View.GONE
    }
    val content=NativeUi.column(context).apply{setPadding(dp(16),0,dp(16),dp(8))}
    private val grip=FrameLayout(context).apply{contentDescription="Resize route panel";isClickable=true;isFocusable=true}
    private var index=0
    private var startY=0f;private var startHeight=0;private var dragged=false;private var dragging=false
    private var visible=false
    var onSnap:(Boolean)->Unit={}
    var onClose:()->Unit={}
    /** Height of map hidden under the sheet's top edge (its rounded corners overlap the map). */
    var onCover:(Int)->Unit={}
    private fun dp(v:Int)=NativeUi.dp(context,v)
    init{
        grip.addView(View(context).apply{background=NativeUi.background(context,0xff8a9691.toInt(),3)},FrameLayout.LayoutParams(dp(32),dp(4),Gravity.CENTER))
        grip.addView(NativeUi.button(context,"×"){onClose()}.apply{contentDescription="Close route";background=null;textSize=19f;setPadding(0,0,0,0)},FrameLayout.LayoutParams(dp(46),dp(28),Gravity.END))
        panel.addView(grip,LinearLayout.LayoutParams(-1,dp(28)))
        panel.addView(ScrollView(context).apply{isFillViewport=true;addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(panel,FrameLayout.LayoutParams(-1,dp(COMPACT_DP),Gravity.BOTTOM))
        root.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(visible&&!dragging)settle(index)}
        grip.setOnClickListener{settle(1-index)}
        grip.setOnKeyListener{_,key,event->if(event.action==KeyEvent.ACTION_UP&&key in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)){settle(if(key==KeyEvent.KEYCODE_DPAD_UP)1 else 0);true}else false}
        grip.setOnTouchListener{_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{startY=event.rawY;startHeight=panel.height;dragged=false;dragging=true;map.layoutParams=map.layoutParams.apply{height=root.height-root.paddingTop-root.paddingBottom};map.clipBounds=Rect(0,0,map.width,root.height-root.paddingTop-root.paddingBottom-startHeight+dp(24));panel.layoutParams=panel.layoutParams.apply{height=stops()[1]};panel.translationY=(stops()[1]-startHeight).toFloat();true}
            MotionEvent.ACTION_MOVE->{val delta=startY-event.rawY;dragged=dragged||abs(delta)>dp(4);val h=(startHeight+delta).toInt().coerceIn(stops()[0],stops()[1]);panel.translationY=(stops()[1]-h).toFloat();map.clipBounds=Rect(0,0,map.width,min(map.height,root.height-root.paddingTop-root.paddingBottom-h+dp(24)));true}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                val delta=startY-event.rawY;val h=stops()[1]-panel.translationY
                var next=if(abs(h-stops()[0])<abs(h-stops()[1]))0 else 1
                if(!dragged)next=1-index else if(abs(delta)>dp(32)&&next==index)next=(index+if(delta>0)1 else -1).coerceIn(0,1)
                dragging=false;settle(if(event.actionMasked==MotionEvent.ACTION_CANCEL)index else next);true
            };else->true
        }}
    }
    private fun stops():IntArray{val h=(root.height-root.paddingTop-root.paddingBottom).takeIf{it>0}?:context.resources.displayMetrics.heightPixels;val compact=min(dp(COMPACT_DP),(h*.33).toInt());return intArrayOf(compact,min(h-dp(80),max(compact+dp(80),(h*.64).toInt())))}
    fun expanded()=index==1
    fun show(value:Boolean){if(visible==value)return;visible=value;panel.visibility=if(value)View.VISIBLE else View.GONE;settle(index)}
    fun settle(next:Int){
        index=next.coerceIn(0,1);map.clipBounds=null;panel.translationY=0f
        val h=if(visible)stops()[index]else 0
        if(panel.layoutParams.height!=h&&visible)panel.layoutParams=panel.layoutParams.apply{height=h}
        val mapHeight=if(visible&&root.height>0)(root.height-root.paddingTop-root.paddingBottom-h+dp(24)).coerceAtLeast(dp(80))else -1
        if(map.layoutParams.height!=mapHeight)map.layoutParams=map.layoutParams.apply{height=mapHeight}
        onCover(if(visible&&root.height>0)dp(24)else 0)
        grip.contentDescription="Resize route panel · ${if(index==1)"expanded"else"compact"}"
        onSnap(index==1)
    }
    companion object{
        /** Compact (lowest) stop: grip, stats and tools, a little tighter (was 136 dp). */
        const val COMPACT_DP=116
    }
}
