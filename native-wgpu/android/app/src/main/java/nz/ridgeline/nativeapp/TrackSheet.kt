package nz.ridgeline.nativeapp

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.widget.*
import java.util.concurrent.Executors
import kotlin.math.*

/** The summary of a tapped track name, from DOC: photo, name, difficulty and
 * time, distance, ascent, descent and highest point, the elevation profile
 * with huts marked, DOC's introduction, track facts, huts along the way and
 * DOC's page. Rests compact over the map; drag or tap the grip to expand,
 * drag down to close. */
internal class TrackSheet(private val activity:Activity,private val root:FrameLayout,private val loader:TileLoader){
    var onLine:(List<Coordinate>?)->Unit={}
    var onPoint:(Coordinate?)->Unit={}
    /** The map's own line for a tapped name (the geometry the map draws). */
    var mapLine:(String,Coordinate,(List<List<Coordinate>>)->Unit)->Unit={_,_,cb->cb(emptyList())}
    private val main=Handler(Looper.getMainLooper())
    private val parse=Executors.newSingleThreadExecutor{Thread(it,"ridgeline-doc").apply{isDaemon=true}}
    private fun dp(v:Int)=NativeUi.dp(activity,v)
    private fun dpf(v:Float)=v*activity.resources.displayMetrics.density

    private val panel=object:LinearLayout(activity){
        private val slop=ViewConfiguration.get(activity).scaledTouchSlop
        private var downY=0f;private var downX=0f;private var candidate=false;private var owning=false
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;owning=false;candidate=e.y<header.bottom}
                MotionEvent.ACTION_MOVE->if(candidate&&!owning){val dy=e.rawY-downY;if(abs(dy)>slop&&abs(dy)>abs(e.rawX-downX)){owning=true;begin(downY);return true}}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->candidate=false
            }
            return false
        }
        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(!owning)return true // The sheet is not see-through to the map.
            when(e.actionMasked){MotionEvent.ACTION_MOVE->drag(e.rawY);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{owning=false;finish(e.rawY)}}
            return true
        }
        override fun performClick():Boolean{super.performClick();return true}
    }.apply{
        orientation=LinearLayout.VERTICAL;visibility=View.GONE;isClickable=true
        background=GradientDrawable().apply{setColor(NativeUi.paper);val r=dpf(20f);cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
        clipToOutline=true;elevation=dpf(12f)
    }
    private val grip=FrameLayout(activity).apply{isClickable=true;contentDescription="Expand track summary"}
    private val header=NativeUi.column(activity).apply{setPadding(dp(16),0,dp(16),dp(10))}
    private val photo=ImageView(activity).apply{scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=NativeUi.background(activity,NativeUi.line,12);visibility=View.GONE}
    private val name=TextView(activity).apply{textSize=17f;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD);maxLines=2;ellipsize=TextUtils.TruncateAt.END}
    private val subtitle=TextView(activity).apply{textSize=12.5f;setTextColor(NativeUi.muted);maxLines=2;ellipsize=TextUtils.TruncateAt.END}
    private val closedBadge=TextView(activity).apply{text="Closed";textSize=11f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD);setPadding(dp(7),dp(1),dp(7),dp(2));background=NativeUi.background(activity,Color.rgb(196,64,52),8);visibility=View.GONE}
    private val stats=listOf("Distance","Ascent","Descent","Highest").map{Stat(it)}
    private val body=NativeUi.column(activity).apply{setPadding(dp(16),0,dp(16),dp(18))}
    private val chart=ElevationProfileView(activity).apply{message=""}
    private val intro=TextView(activity).apply{textSize=13.5f;setTextColor(NativeUi.ink);setLineSpacing(0f,1.15f);visibility=View.GONE}
    /** DOC's walk name, when the track is part of a walk called something else. */
    private val walkTitle=TextView(activity).apply{textSize=14.5f;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD);visibility=View.GONE}
    /** DOC's page text: one collapsible row per section. */
    private val sections=NativeUi.column(activity).apply{visibility=View.GONE}
    private val facts=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
    private val factsScroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(facts);visibility=View.GONE}
    private val huts=NativeUi.column(activity).apply{visibility=View.GONE}
    private val page=TextView(activity).apply{
        text="DOC page";textSize=14f;gravity=Gravity.CENTER;setTextColor(NativeUi.orange);setTypeface(null,Typeface.BOLD)
        background=NativeUi.pressable(activity,GradientDrawable().apply{setColor(Color.TRANSPARENT);cornerRadius=dpf(12f);setStroke(dp(1),NativeUi.orange)});isClickable=true;visibility=View.GONE
    }
    /** One stat: small muted label, larger value (as the route sheet). */
    private inner class Stat(val label:String):TextView(activity){
        init{textSize=13f;setTextColor(NativeUi.ink);gravity=Gravity.CENTER;value(null)}
        fun value(v:String?){
            val s=android.text.SpannableString("$label\n${v?:"—"}")
            s.setSpan(android.text.style.RelativeSizeSpan(.8f),0,label.length,0);s.setSpan(android.text.style.ForegroundColorSpan(NativeUi.muted),0,label.length,0)
            s.setSpan(android.text.style.RelativeSizeSpan(1.25f),label.length+1,s.length,0);text=s
        }
    }
    init{
        grip.addView(View(activity).apply{background=NativeUi.background(activity,0xff8a9691.toInt(),3)},FrameLayout.LayoutParams(dp(32),dp(4),Gravity.CENTER))
        grip.addView(NativeUi.button(activity,"×"){hide()}.apply{contentDescription="Close track summary";background=null;textSize=19f;setPadding(0,0,0,0)},FrameLayout.LayoutParams(dp(46),dp(28),Gravity.END))
        grip.setOnClickListener{settle(!expanded)}
        header.isClickable=true;header.setOnClickListener{settle(!expanded)}
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(photo,LinearLayout.LayoutParams(dp(56),dp(56)).apply{marginEnd=dp(12)})
        val titles=NativeUi.column(activity)
        titles.addView(name)
        val line=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        line.addView(closedBadge,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)})
        line.addView(subtitle,LinearLayout.LayoutParams(0,-2,1f))
        titles.addView(line,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(2)})
        top.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(top,LinearLayout.LayoutParams(-1,-2))
        header.addView(LinearLayout(activity).apply{for((i,v) in stats.withIndex())addView(v,LinearLayout.LayoutParams(0,dp(40),1f).apply{if(i>0)marginStart=dp(8)})},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        body.addView(chart,LinearLayout.LayoutParams(-1,dp(230)))
        body.addView(walkTitle,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(intro,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(factsScroll,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(huts,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        body.addView(sections,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        body.addView(page,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(16)})
        panel.addView(grip,LinearLayout.LayoutParams(-1,dp(28)))
        panel.addView(header,LinearLayout.LayoutParams(-1,-2))
        panel.addView(ScrollView(activity).apply{isVerticalScrollBarEnabled=false;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(panel,FrameLayout.LayoutParams(-1,-1,Gravity.BOTTOM))
        root.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(visible&&!dragging)place(false)}
        // The compact stop follows the header (a subtitle or photo arriving).
        header.addOnLayoutChangeListener{_,_,top,_,bottom,_,oldTop,_,oldBottom->if(visible&&!dragging&&!expanded&&bottom-top!=oldBottom-oldTop)place(true)}
        chart.onInspect={p,_->if(chart.rangeEnds()==null)onPoint(p.point)}
        chart.onRange={_->onPoint(chart.rangeEnds()?.second)}
        chart.onLeave={onPoint(null)}
    }

    var visible=false;private set
    private var expanded=false
    private var dragging=false;private var startY=0f;private var startShift=0f
    /** Sheet height when expanded; compact shows the grip, title and stats. */
    private fun full()=((root.height-root.paddingTop-root.paddingBottom).takeIf{it>0}?:activity.resources.displayMetrics.heightPixels).let{(it*.78f).toInt()}
    private fun compact()=dp(28)+header.height.takeIf{it>0}.let{it?:dp(118)}
    private fun shift(open:Boolean)=if(open)0f else (full()-compact()).coerceAtLeast(0).toFloat()
    private fun place(animate:Boolean){
        val lp=panel.layoutParams as FrameLayout.LayoutParams
        if(lp.height!=full()){lp.height=full();panel.layoutParams=lp}
        val target=shift(expanded)
        if(animate)panel.animate().translationY(target).setDuration(180).start()else panel.translationY=target
        grip.contentDescription=if(expanded)"Collapse track summary" else "Expand track summary"
    }
    private fun settle(open:Boolean){expanded=open;place(true)}
    private fun begin(rawY:Float){dragging=true;startY=rawY;startShift=panel.translationY;panel.animate().cancel()}
    private fun drag(rawY:Float){if(dragging)panel.translationY=(startShift+rawY-startY).coerceIn(0f,full().toFloat())}
    private fun finish(rawY:Float){
        if(!dragging)return;dragging=false
        val y=panel.translationY;val closedAt=shift(false)
        when{
            y>closedAt+dp(48)->hide()
            rawY-startY>dp(32)->settle(false)
            startY-rawY>dp(32)->settle(true)
            else->settle(abs(y)<abs(y-closedAt))
        }
    }

    private var request=0L
    private var link=""
    fun show(trackName:String,at:Coordinate){
        val id=++request
        visible=true;expanded=false;link=""
        name.text=trackName;subtitle.text="";subtitle.visibility=View.GONE;closedBadge.visibility=View.GONE
        photo.setImageDrawable(null);photo.visibility=View.GONE
        for(s in stats)s.value(null)
        chart.data(emptyList());chart.markers=emptyList();chart.message="";chart.visibility=View.VISIBLE
        trackTitle=trackName;walkTitle.visibility=View.GONE;sections.removeAllViews();sections.visibility=View.GONE
        intro.visibility=View.GONE;factsScroll.visibility=View.GONE;facts.removeAllViews();huts.removeAllViews();huts.visibility=View.GONE;page.visibility=View.GONE
        onLine(null);onPoint(null);loader.cancelTrackProfile()
        if(panel.visibility!=View.VISIBLE){panel.visibility=View.VISIBLE;panel.translationY=full().toFloat()}
        panel.post{if(id==request)place(true)}
        var walk:DocTracks.Walk?=null;var track:DocTracks.Track?=null;var walkDone=false;var trackDone=false;var lineStarted=false
        var mapped:List<Coordinate>?=null;var nameDone=false;var placeStarted=false
        fun geometry(line:List<Coordinate>){if(id!=request)return;lineFound(id,line)}
        fun decide(){
            val map=mapped?:return
            if(lineStarted)return
            // The map's own track first (what is drawn, and finer than DOC's
            // walk lines, which cut corners on zigzags); DOC's lines only when
            // the map has none.
            if(map.size>=2){lineStarted=true;geometry(map);return}
            if(!walkDone||!trackDone)return;lineStarted=true
            val pieces=track?.paths.orEmpty()
            val guid=walk?.guid
            fun fallback(){if(pieces.isNotEmpty())geometry(DocTracks.chain(pieces,at))else geometry(emptyList())}
            if(guid==null)fallback()
            else fetch(DocTracks.lineUrl(guid),{DocTracks.chain(DocTracks.parseLines(text(it)),at)}){found->
                val line=found.orEmpty()
                // The walk's own line, unless it is somewhere else (a different walk of the same name).
                if(line.size>=2&&line.minOf{DocTracks.meters(it,at)}<2000)geometry(line)else fallback()
            }
        }
        fun walkFound(w:DocTracks.Walk?){
            if(id!=request||walkDone)return
            walk=w;walkDone=true;w?.let{showWalk(id,it)};describe(walk,track);decide()
        }
        // No walk of this name: the DOC walk whose line runs along the track,
        // whatever it is called (the map's Rabbit Pass route is part of DOC's
        // "Wilkin Valley - East Matukituki Traverse").
        fun matchByPlace(){
            val map=mapped?:return
            if(!nameDone||walkDone||placeStarted)return;placeStarted=true
            val b=DocTracks.bounds(map,150.0)
            if(map.size<2||b==null){walkFound(null);return}
            fetch(DocTracks.linesNearUrl(b),{DocTracks.alongTrack(DocTracks.parseWalkLines(text(it)),map)}){line->
                if(id!=request)return@fetch
                if(line==null){walkFound(null);return@fetch}
                fetch(DocTracks.walkByGuidUrl(line.guid),{DocTracks.parseWalks(text(it)).firstOrNull()}){w->walkFound(w)}
            }
        }
        mapLine(trackName,at){paths->if(id==request){mapped=DocTracks.chain(paths,at,25.0);decide();matchByPlace()}}
        fetch(DocTracks.walkUrl(trackName,at.longitude,at.latitude),{DocTracks.nearestWalk(DocTracks.parseWalks(text(it)),at)}){found->
            if(id!=request)return@fetch
            nameDone=true
            if(found!=null)walkFound(found)else matchByPlace()
        }
        fetch(DocTracks.trackUrl(trackName,at.longitude,at.latitude),{DocTracks.parseTracks(text(it))}){found->
            if(id!=request)return@fetch
            val all=found.orEmpty()
            // Pieces of the tapped track only: the nearest piece and those chained to it.
            track=all.minByOrNull{t->t.paths.flatten().minOfOrNull{DocTracks.meters(it,at)}?:Double.MAX_VALUE}
                ?.takeIf{t->t.paths.flatten().any{DocTracks.meters(it,at)<1500}}
                ?.let{nearest->DocTracks.Track(nearest.name,nearest.status,nearest.category,nearest.dogs,nearest.type,nearest.meters,all.flatMap{it.paths})}
            trackDone=true;describe(walk,track);decide()
        }
    }
    fun hide(){
        if(!visible)return
        visible=false;request++;loader.cancelTrackProfile();onLine(null);onPoint(null);chart.clearInspection()
        panel.animate().translationY(full().toFloat()).setDuration(160).withEndAction{if(!visible)panel.visibility=View.GONE}.start()
    }
    fun back():Boolean{if(!visible)return false;if(expanded)settle(false)else hide();return true}

    private var trackTitle=""
    private fun showWalk(id:Long,w:DocTracks.Walk){
        if(w.name.isNotBlank()&&!w.name.equals(trackTitle,true)){walkTitle.text=w.name;walkTitle.visibility=View.VISIBLE}
        if(w.introduction.isNotBlank()){intro.text=w.introduction;intro.visibility=View.VISIBLE}
        if(w.page.startsWith("https://")){link=w.page;page.visibility=View.VISIBLE;page.setOnClickListener{open(link)}
            // DOC's full page text, after everything else (about a second).
            fetch(w.page,{DocPages.parse(text(it))}){found->if(id==request&&!found.isNullOrEmpty())showSections(found)}}
        val size=dp(56)
        if(w.thumbnail.startsWith("https://"))fetch(w.thumbnail,{bytes->
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            var sample=1;while(min(bounds.outWidth,bounds.outHeight)/(sample*2)>=size)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
        }){bitmap->if(id==request&&bitmap!=null){photo.setImageBitmap(bitmap);photo.visibility=View.VISIBLE}}
    }
    /** Difficulty and time (DOC's walk), else the track's category and time; facts as chips. */
    private fun describe(w:DocTracks.Walk?,t:DocTracks.Track?){
        val line=if(w!=null)listOf(w.difficulty,w.time)else listOf(t?.category.orEmpty(),"")
        val summary=line.filter{it.isNotBlank()}.joinToString(" · ")
        subtitle.text=summary;subtitle.visibility=if(summary.isEmpty())View.GONE else View.VISIBLE
        closedBadge.visibility=if(t?.closed==true)View.VISIBLE else View.GONE
        facts.removeAllViews()
        if(t!=null)for(v in listOf(t.type,t.dogs,if(w!=null)t.category else"").filter{it.isNotBlank()}.map(DocTracks::tidy).distinct())
            facts.addView(TextView(activity).apply{text=v;textSize=12f;setTextColor(NativeUi.ink);setPadding(dp(10),dp(5),dp(10),dp(5));background=NativeUi.background(activity,Color.rgb(236,240,232),14)},
                LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)})
        factsScroll.visibility=if(facts.childCount>0)View.VISIBLE else View.GONE
    }
    private fun lineFound(id:Long,line:List<Coordinate>){
        if(line.size<2){chart.visibility=View.GONE;return}
        onLine(line)
        stats[0].value(distance(DocTracks.length(line)))
        loader.trackProfile(line){values,_->
            if(id!=request)return@trackProfile
            chart.data(values);val totals=NativeGeo.totals(values.map{it.height});val measured=values.count{it.height!=null}>=2
            values.lastOrNull()?.let{stats[0].value(distance(it.distance))}
            stats[1].value(if(measured&&totals.complete)metres(totals.ascent)else null)
            stats[2].value(if(measured&&totals.complete)metres(totals.descent)else null)
            stats[3].value(totals.high?.let(::metres))
            chart.refreshInspection()
        }
        val b=DocTracks.bounds(line)?:return
        fetch(DocTracks.hutsUrl(b),{DocTracks.stops(DocTracks.parseHuts(text(it)),line)}){found->
            val stops=found.orEmpty()
            if(id!=request||stops.isEmpty())return@fetch
            showHuts(stops)
            fetch(DocTracks.hutPagesUrl(b),{pages->DocTracks.joinHutPages(stops.map{it.hut},text(pages));true}){joined->if(id==request&&joined==true)showHuts(stops)}
        }
    }
    private fun showSections(list:List<DocPages.Section>){
        sections.removeAllViews()
        for(section in list){
            val content=NativeUi.column(activity).apply{visibility=View.GONE;setPadding(0,0,0,dp(10))}
            val chevron=TextView(activity).apply{text="›";textSize=20f;setTextColor(NativeUi.muted);gravity=Gravity.CENTER;rotation=90f}
            val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(10));isClickable=true;background=NativeUi.pressable(activity,null);contentDescription=section.title}
            row.addView(TextView(activity).apply{text=section.title;textSize=14.5f;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
            row.addView(chevron,LinearLayout.LayoutParams(dp(24),dp(24)))
            row.setOnClickListener{
                if(content.childCount==0)fill(content,section.blocks)
                val open=content.visibility!=View.VISIBLE
                content.visibility=if(open)View.VISIBLE else View.GONE
                chevron.animate().rotation(if(open)270f else 90f).setDuration(150).start()
            }
            sections.addView(View(activity).apply{setBackgroundColor(NativeUi.line)},LinearLayout.LayoutParams(-1,dp(1)))
            sections.addView(row,LinearLayout.LayoutParams(-1,-2))
            sections.addView(content,LinearLayout.LayoutParams(-1,-2))
        }
        sections.addView(View(activity).apply{setBackgroundColor(NativeUi.line)},LinearLayout.LayoutParams(-1,dp(1)))
        sections.visibility=if(list.isEmpty())View.GONE else View.VISIBLE
    }
    private fun fill(content:LinearLayout,blocks:List<DocPages.Block>){
        for(b in blocks){
            val v=TextView(activity).apply{textSize=13.5f;setTextColor(NativeUi.ink);setLineSpacing(0f,1.15f);setTextIsSelectable(false)}
            val lp=LinearLayout.LayoutParams(-1,-2)
            when(b){
                is DocPages.Heading->{v.text=b.text;v.setTypeface(null,Typeface.BOLD);lp.topMargin=dp(12)}
                is DocPages.Paragraph->{v.text=b.text;lp.topMargin=dp(6)}
                is DocPages.Bullet->{v.text=android.text.SpannableString("•  "+b.text).apply{setSpan(android.text.style.LeadingMarginSpan.Standard(0,dp(14)),0,length,0)};lp.topMargin=dp(4)}
            }
            content.addView(v,lp)
        }
    }
    private fun showHuts(stops:List<DocTracks.Stop>){
        chart.markers=stops.map{it.along to it.hut.name}
        huts.removeAllViews()
        for(s in stops){val h=s.hut
            val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(8));contentDescription=h.name
                if(h.page.startsWith("https://")){isClickable=true;background=NativeUi.pressable(activity,null);setOnClickListener{open(h.page)}}}
            row.addView(HutIcon(),LinearLayout.LayoutParams(dp(30),dp(30)).apply{marginEnd=dp(12)})
            val text=NativeUi.column(activity)
            text.addView(TextView(activity).apply{this.text=h.name;textSize=14f;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD);maxLines=1;ellipsize=TextUtils.TruncateAt.END})
            val detail=listOf(h.category.removeSuffix(" Hut").let{if(it.isBlank())"" else "$it hut"},bunks(h.bunks),if(h.bookable)"Bookable" else "").filter{it.isNotBlank()}.joinToString(" · ")
            if(detail.isNotEmpty())text.addView(TextView(activity).apply{this.text=detail;textSize=12f;setTextColor(NativeUi.muted);maxLines=1;ellipsize=TextUtils.TruncateAt.END})
            row.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(TextView(activity).apply{this.text=distance(s.along);textSize=12.5f;setTextColor(NativeUi.muted)},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            huts.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        huts.visibility=View.VISIBLE
    }
    /** "20 each" → "20 bunks". */
    private fun bunks(v:String)=Regex("\\d+").find(v)?.value?.let{"$it bunks"}.orEmpty()
    private inner class HutIcon:View(activity){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val shape=Path()
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
            val r=w*.24f;val cx=w/2f;val cy=h/2f+r*.15f
            shape.reset();shape.moveTo(cx-r*1.15f,cy-r*.15f);shape.lineTo(cx,cy-r*1.2f);shape.lineTo(cx+r*1.15f,cy-r*.15f);shape.lineTo(cx+r*.8f,cy-r*.15f)
            shape.lineTo(cx+r*.8f,cy+r);shape.lineTo(cx-r*.8f,cy+r);shape.lineTo(cx-r*.8f,cy-r*.15f);shape.close()
        }
        override fun onDraw(c:Canvas){
            paint.color=Color.rgb(236,240,232);c.drawCircle(width/2f,height/2f,width/2f,paint)
            paint.color=NativeUi.ink;c.drawPath(shape,paint)
        }
    }
    private fun open(url:String){runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}}
    private fun metres(v:Double)=String.format(java.util.Locale.ROOT,"%,d m",v.roundToInt())
    private fun distance(m:Double)=if(m>=1000)String.format(java.util.Locale.ROOT,if(m>=10_000)"%.1f km" else "%.2f km",m/1000)else"${m.roundToInt()} m"

    /** One DOC request through the map's transport (shared cache, 1-day
     * freshness); [callback] on the main thread with the body, or null. */
    private fun <T> fetch(url:String,read:(ByteArray)->T?,callback:(T?)->Unit){
        val owned=loader.layerConsumer(1)
        val body=MapLayers.startLeased(owned){loader.layerBody(owned,url)}
        if(body==null){main.post{callback(null)};return}
        body.whenComplete{result,error->
            runCatching{owned.close()}
            val bytes=if(error==null&&result!=null&&result.status in 200..299)result.bytes else null
            runCatching{parse.execute{val value=bytes?.let{b->runCatching{read(b)}.getOrNull()};main.post{callback(value)}}}
        }
    }
    private fun text(b:ByteArray)=String(b,Charsets.UTF_8)
    fun close(){request++;parse.shutdownNow()}
}
