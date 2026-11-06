package nz.ridgeline.nativeapp

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.*

/** Capacitor map chrome: kea launcher and locate button (top left), 2D/3D
 * switch (top), compass and layers button (top right), scale bar (bottom
 * left) and compact attribution (bottom right). There are no zoom buttons. */
internal object MapControls {
    private fun dp(context:Context,v:Int)=NativeUi.dp(context,v)

    /** White rounded map button, as the Capacitor/MapLibre controls. */
    fun tile(context:Context,radius:Int=8)=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(context,radius).toFloat()}

    fun iconButton(context:Context,icon:Drawable,description:String,action:()->Unit)=ImageButton(context).apply{
        setImageDrawable(icon);contentDescription=description;scaleType=ImageView.ScaleType.CENTER
        background=NativeUi.pressable(context,tile(context));elevation=dp(context,2).toFloat()
        setPadding(0,0,0,0);setOnClickListener{action()}
    }

    fun logo(context:Context,action:()->Unit)=ImageButton(context).apply{
        val bitmap=runCatching{context.assets.open("ui/ridgeline-kea.png").use(BitmapFactory::decodeStream)}.getOrNull()
        if(bitmap!=null)setImageBitmap(bitmap)
        scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription="Ridgeline — open planner"
        background=NativeUi.pressable(context,tile(context,10));clipToOutline=true;elevation=dp(context,2).toFloat()
        setPadding(0,0,0,0);setOnClickListener{action()}
    }

    /** Capacitor's locate icon: ring, centre dot and four ticks. */
    class LocateIcon(private val context:Context):Drawable(){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
        var color=NativeUi.ink;set(value){field=value;invalidateSelf()}
        override fun getIntrinsicWidth()=dp(context,24);override fun getIntrinsicHeight()=dp(context,24)
        override fun draw(canvas:Canvas){
            val b=bounds;val cx=b.exactCenterX();val cy=b.exactCenterY();val u=b.width()/24f
            paint.color=color;paint.strokeWidth=2*u;paint.style=Paint.Style.STROKE
            canvas.drawCircle(cx,cy,7*u,paint)
            for((dx,dy) in listOf(0f to -1f,0f to 1f,-1f to 0f,1f to 0f))canvas.drawLine(cx+dx*7*u,cy+dy*7*u,cx+dx*11*u,cy+dy*11*u,paint)
            paint.style=Paint.Style.FILL;canvas.drawCircle(cx,cy,3*u,paint)
        }
        override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:ColorFilter?){}
        @Deprecated("Drawable API") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    /** Two stacked map sheets (the Capacitor layers icon). */
    class LayersIcon(private val context:Context):Drawable(){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
        override fun getIntrinsicWidth()=dp(context,20);override fun getIntrinsicHeight()=dp(context,20)
        override fun draw(canvas:Canvas){
            val b=bounds;val u=b.width()/24f;val x=b.left.toFloat();val y=b.top.toFloat()
            paint.color=NativeUi.ink;paint.strokeWidth=1.8f*u
            fun p(px:Float,py:Float)=PointF(x+px*u,y+py*u)
            val top=Path().apply{val a=p(12f,3f);moveTo(a.x,a.y);p(21f,8f).let{lineTo(it.x,it.y)};p(12f,13f).let{lineTo(it.x,it.y)};p(3f,8f).let{lineTo(it.x,it.y)};close()}
            canvas.drawPath(top,paint)
            for(dy in listOf(4.5f,9f)){val path=Path().apply{p(3f,8f+dy).let{moveTo(it.x,it.y)};p(12f,13f+dy).let{lineTo(it.x,it.y)};p(21f,8f+dy).let{lineTo(it.x,it.y)}};canvas.drawPath(path,paint)}
        }
        override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:ColorFilter?){}
        @Deprecated("Drawable API") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    /** MapLibre compass: dark north and grey south needle, rotated with the map. */
    class Compass(context:Context,private val action:()->Unit):View(context){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val north=Path();private val south=Path()
        var bearing=0f;set(value){if(abs(field-value)>.2f){field=value;invalidate()}}
        init{background=NativeUi.pressable(context,tile(context));elevation=dp(context,2).toFloat();contentDescription="Reset bearing to north";isClickable=true;setOnClickListener{action()}}
        override fun onDraw(canvas:Canvas){
            val cx=width/2f;val cy=height/2f;val r=min(width,height)*.32f
            canvas.save();canvas.rotate(-bearing,cx,cy)
            paint.style=Paint.Style.FILL
            north.rewind();north.moveTo(cx,cy-r);north.lineTo(cx+r*.42f,cy);north.lineTo(cx-r*.42f,cy);north.close()
            south.rewind();south.moveTo(cx,cy+r);south.lineTo(cx+r*.42f,cy);south.lineTo(cx-r*.42f,cy);south.close()
            paint.color=NativeUi.ink;canvas.drawPath(north,paint)
            paint.color=Color.rgb(170,178,174);canvas.drawPath(south,paint)
            canvas.restore()
        }
    }

    /** 2D | 3D segmented switch; the active side is filled dark green. */
    class Perspective(context:Context,private val choose:(Boolean)->Unit):LinearLayout(context){
        private val flat=segment("2D","Show the map in 2D"){choose(false)}
        private val relief=segment("3D","Show the map in 3D"){choose(true)}
        private fun segment(label:String,description:String,action:()->Unit)=TextView(context).apply{
            text=label;textSize=12f;gravity=Gravity.CENTER;contentDescription=description;isClickable=true;isFocusable=true
            setOnClickListener{action()}
        }
        init{
            background=tile(context,10);elevation=dp(context,2).toFloat();setPadding(dp(context,3),dp(context,3),dp(context,3),dp(context,3))
            addView(flat,LayoutParams(dp(context,38),-1));addView(relief,LayoutParams(dp(context,38),-1))
            set(true)
        }
        fun set(threeD:Boolean){
            for((view,active) in listOf(flat to !threeD,relief to threeD)){
                view.setTextColor(if(active)Color.WHITE else NativeUi.ink)
                view.background=if(active)GradientDrawable().apply{setColor(NativeUi.ink);cornerRadius=dp(context,8).toFloat()}else NativeUi.pressable(context,null)
                view.isSelected=active
            }
        }
    }

    /** MapLibre ScaleControl (max 100 CSS px, metric) measured at the map centre.
     * Fixed size: the bar is drawn inside, so camera changes only redraw it and
     * never re-lay-out the map screen. */
    class Scale(context:Context):View(context){
        private val border=Paint().apply{color=Color.WHITE;style=Paint.Style.STROKE}
        private val fill=Paint().apply{color=0x33000000}
        private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=12*context.resources.displayMetrics.scaledDensity;setShadowLayer(2f,0f,0f,0x99000000.toInt())}
        private var label="";private var bar=0f
        fun update(pose:CameraPose){
            val ppm=CameraMath.pixelsPerMeter(pose,1.0)
            if(!ppm.isFinite()||ppm<=0){if(bar!=0f){bar=0f;invalidate()};return}
            val max=100/ppm;val rounded=round(max)
            val next=if(rounded>=1000)"${fmt(rounded/1000)} km" else "${fmt(rounded)} m"
            val width=(rounded/max*100*resources.displayMetrics.density).toFloat()
            if(next!=label||abs(width-bar)>=.5f){label=next;bar=width;contentDescription="Scale $next";invalidate()}
        }
        private fun fmt(v:Double)=if(v==floor(v))v.toLong().toString()else String.format(java.util.Locale.ROOT,"%.1f",v)
        override fun onDraw(canvas:Canvas){
            if(bar<=0f)return
            val w=dp(context,2).toFloat();border.strokeWidth=w;val right=min(bar,width.toFloat())
            canvas.drawRect(0f,0f,right,height.toFloat(),fill)
            canvas.drawLines(floatArrayOf(w/2,0f,w/2,height-w/2f, w/2,height-w/2,right-w/2,height-w/2, right-w/2,height-w/2,right-w/2,0f),border)
            canvas.drawText(label,dp(context,6).toFloat(),height/2f+text.textSize/2.8f,text)
        }
        companion object{
            /** MapLibre getRoundNum: 1, 2, 3 or 5 times a power of ten. */
            fun round(value:Double):Double{
                val pow=10.0.pow(floor(log10(max(1.0,value))))
                val d=value/pow
                return pow*when{d>=10->10.0;d>=5->5.0;d>=3->3.0;d>=2->2.0;d>=1->1.0;else->d}
            }
        }
    }

    /** Compact "i" attribution: tap to show or hide the source credits. */
    class InfoIcon(private val context:Context):Drawable(){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth()=dp(context,24);override fun getIntrinsicHeight()=dp(context,24)
        override fun draw(canvas:Canvas){
            val b=bounds;val cx=b.exactCenterX();val cy=b.exactCenterY();val r=b.width()*.42f
            paint.style=Paint.Style.FILL;paint.color=Color.BLACK;canvas.drawCircle(cx,cy,r,paint)
            paint.color=Color.WHITE;canvas.drawCircle(cx,cy-r*.45f,r*.13f,paint)
            canvas.drawRoundRect(RectF(cx-r*.12f,cy-r*.18f,cx+r*.12f,cy+r*.55f),r*.06f,r*.06f,paint)
        }
        override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:ColorFilter?){}
        @Deprecated("Drawable API") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
