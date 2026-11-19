package nz.ridgeline.nativeapp

import android.app.Activity
import android.app.AlertDialog
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
 * DOC's page. Rests compact over the map; drag or tap the grip to expand.
 * It stays open until its X: a map tap or a drag down does not close it. A hut
 * opened from a track or walk returns to it when closed. */
internal class TrackSheet(private val activity:Activity,private val root:FrameLayout,private val loader:TileLoader,private val map:FrameLayout){
    /** The tapped track ([line]) and the section in view ([part]) on the map. */
    var onLine:(List<Coordinate>?,List<Coordinate>?)->Unit={_,_->}
    /** Height of the map the sheet covers now, following it as it moves (0 when closed). */
    var onCover:(Int)->Unit={}
    /** Height of the map the sheet covers at the stop it is at or going to (0 when closed). */
    var onInset:(Int)->Unit={}
    /** The sheet closed: the route sheet (if open) sizes the map again. */
    var onClosed:()->Unit={}
    /** DOC's walk as DOC describes it (route, page sections, DOC line); see MapLayers.walkRoute. */
    var walkRoute:(String,String,(WalkRoutes.Route?,List<DocPages.Section>,List<List<Coordinate>>,DocPages.Overview?)->Unit)->Unit={_,_,cb->cb(null,emptyList(),emptyList(),null)}
    /** The map's track pieces (name, points) along a line; see MapLayers.alongLine. */
    var alongLine:(List<List<Coordinate>>,(List<Pair<String,List<Coordinate>>>)->Unit)->Unit={_,cb->cb(emptyList())}
    var onPoint:(Coordinate?)->Unit={}
    /** A and B of a section dragged on the chart (empty: none). */
    var onRange:(List<Coordinate>)->Unit={}
    /** The map's own line for a tapped name (the geometry the map draws). */
    var mapLine:(String,Coordinate,(List<List<Coordinate>>)->Unit)->Unit={_,_,cb->cb(emptyList())}
    /** Frames the map on a line (summaries opened from search). */
    var onFit:(List<Coordinate>)->Unit={}
    private var fitNext=false
    /** The DOC walk list entry for a walk id, if loaded. */
    var walkInfo:(String)->DocWalks.Info?={null}
    /** A walk's line continued to its tracks' natural ends; see MapLayers.extendWalk. */
    var extendWalk:(List<Coordinate>,Double,(List<Coordinate>?)->Unit)->Unit={_,_,cb->cb(null)}
    /** The compiled walk for a DOC walk id, if bundled; see WalkRecordStore. */
    var walkRecord:(String,(WalkRecords.Walk?)->Unit)->Unit={_,cb->cb(null)}
    /** A walk followed as DOC's description tells it; see MapLayers.followWalk. */
    var followWalk:(List<DocPages.Section>,String,String,String,List<List<Coordinate>>,List<Coordinate>,(WalkDirections.Result?)->Unit)->Unit={_,_,_,_,_,_,cb->cb(null)}
    /** A loop walk's line closed over the map's tracks; see MapLayers.closeWalk. */
    var closeWalk:(List<Coordinate>,Double,Boolean?,(List<Coordinate>?)->Unit)->Unit={_,_,_,cb->cb(null)}
    private val LOOP=Regex("\\b(loop|circuit)\\b",RegexOption.IGNORE_CASE)
    /** Walks are shown on DOC's own line (as up to 0.1.46), not compiled or rerouted. */
    private val DOC_ROUTES=true
    /** A walk's start and end options; see MapLayers.walkOptions. */
    var walkOptions:(String,List<DocPages.Leg>,List<DocPages.Start>,String,String,List<List<Coordinate>>,(WalkOptions.Choice?)->Unit)->Unit={_,_,_,_,_,_,cb->cb(null)}
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
                MotionEvent.ACTION_MOVE->if(candidate&&!owning){val dy=e.rawY-downY;if(abs(dy)>slop&&abs(dy)>abs(e.rawX-downX)){owning=true;begin(e.rawY);return true}}
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
    /** Parts of the line to look at on their own: DOC's legs and the map's
     * named tracks ("Siberia Track" in the Gillespie Pass Circuit). */
    private class Part(val title:String,val detail:String,val spans:List<Pair<Double,Double>>,val blocks:List<DocPages.Block>)
    private val parts=ArrayList<Part>()
    /** Where the walk in view starts and ends, when DOC gives choices. */
    private fun optionChip()=TextView(activity).apply{textSize=12.5f;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD);maxLines=1;setPadding(dp(12),dp(7),dp(12),dp(7))
        background=NativeUi.pressable(activity,NativeUi.background(activity,NativeUi.chip,14))}
    private val fromChip=optionChip();private val toChip=optionChip()
    private val optionScroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;visibility=View.GONE
        addView(LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL
            addView(fromChip,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)});addView(toChip,LinearLayout.LayoutParams(-2,-2))})}
    private val partRow=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
    private val partScroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(partRow);visibility=View.GONE}
    /** The selected leg's own DOC text. */
    private val partText=NativeUi.column(activity).apply{visibility=View.GONE}
    private var lineNow=emptyList<Coordinate>()
    private var wholeStats=arrayOfNulls<String>(4);private var wholeSubtitle:CharSequence=""
    private var selected=-1
    /** The larger DOC walk a tapped track is part of (guid, name). */
    private var walkRef:Pair<String,String>?=null
    /** DOC's page text: one collapsible row per section. */
    private val sections=NativeUi.column(activity).apply{visibility=View.GONE}
    private val facts=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
    private val factsScroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(facts);visibility=View.GONE}
    private val huts=NativeUi.column(activity).apply{visibility=View.GONE}
    private val page=TextView(activity).apply{
        text="DOC page";textSize=14f;gravity=Gravity.CENTER;setTextColor(NativeUi.orange);setTypeface(null,Typeface.BOLD)
        background=NativeUi.pressable(activity,GradientDrawable().apply{setColor(Color.TRANSPARENT);cornerRadius=dpf(12f);setStroke(dp(1),NativeUi.orange)});isClickable=true;visibility=View.GONE
    }
    // ---- Te Araroa (TeAraroa, bundled) and live alerts ----
    /** The trail, once loaded. */
    @Volatile var teAraroa:TeAraroa?=null
    /** Saves a line as a route in the route planner. */
    var saveRoute:(String,List<Coordinate>)->Unit={_,_->}
    private val prefs=activity.getSharedPreferences("te-araroa",0)
    /** The walker's direction: northbound (Bluff to Cape Reinga) or southbound. */
    private var nobo=prefs.getBoolean("nobo",false)
    private var taNotes:Map<String,TeAraroa.Notes>?=null
    /** Back (top left): to the page this one was opened from. */
    private val backButton=TextView(activity).apply{textSize=13.5f;setTextColor(NativeUi.orange);setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(10),0)
        isClickable=true;background=NativeUi.pressable(activity,null);visibility=View.GONE;maxLines=1;ellipsize=TextUtils.TruncateAt.END;maxWidth=dp(230)}
    /** Beside the title: Te Araroa's direction switch. */
    private val aside=FrameLayout(activity)
    private lateinit var statsRow:LinearLayout
    /** The page below the name; scrolled back to its top when the panel is lowered, so the figures show under the name. */
    private val bodyScroll=ScrollView(activity).apply{isVerticalScrollBarEnabled=false}
    /** Above the chart (Te Araroa: tabs and lists, or previous/next and alerts). */
    private val taTop=NativeUi.column(activity).apply{visibility=View.GONE}
    /** Below the introduction (Te Araroa: food, bypasses, a stop's or shop's details). */
    private val taBelow=NativeUi.column(activity).apply{visibility=View.GONE}
    /** DOC's live alerts, below the chart and introduction. */
    private val docAlerts=NativeUi.column(activity).apply{visibility=View.GONE}
    /** At the bottom: "Part of Te Araroa" (walks and tracks), "On Te Araroa" (huts). */
    private val taLink=NativeUi.column(activity).apply{visibility=View.GONE}
    private val saveButton=TextView(activity).apply{
        text="Save as route";textSize=14f;gravity=Gravity.CENTER;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD)
        background=NativeUi.pressable(activity,GradientDrawable().apply{setColor(Color.TRANSPARENT);cornerRadius=dpf(12f);setStroke(dp(1),NativeUi.line)});isClickable=true;visibility=View.GONE}
    /** Te Araroa pages: download this stretch of trail for offline use. */
    private val offlineButton=TextView(activity).apply{
        text="Download for offline";textSize=14f;gravity=Gravity.CENTER;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD)
        background=NativeUi.pressable(activity,GradientDrawable().apply{setColor(Color.TRANSPARENT);cornerRadius=dpf(12f);setStroke(dp(1),NativeUi.line)});isClickable=true;visibility=View.GONE}
    private val offlineStatus=TextView(activity).apply{textSize=12.5f;setTextColor(NativeUi.muted);visibility=View.GONE}
    private val fine=TextView(activity).apply{textSize=11.5f;setTextColor(NativeUi.muted);setLineSpacing(0f,1.1f);visibility=View.GONE}
    /** One stat: small muted label, larger value (as the route sheet). */
    private inner class Stat(var label:String):TextView(activity){
        init{textSize=13f;setTextColor(NativeUi.ink);gravity=Gravity.CENTER;value(null)}
        fun value(v:String?){
            val s=android.text.SpannableString("$label\n${v?:"—"}")
            s.setSpan(android.text.style.RelativeSizeSpan(.8f),0,label.length,0);s.setSpan(android.text.style.ForegroundColorSpan(NativeUi.muted),0,label.length,0)
            s.setSpan(android.text.style.RelativeSizeSpan(1.25f),label.length+1,s.length,0);text=s
        }
    }
    init{
        grip.addView(View(activity).apply{background=NativeUi.background(activity,0xff8a9691.toInt(),3)},FrameLayout.LayoutParams(dp(32),dp(4),Gravity.CENTER))
        grip.addView(NativeUi.button(activity,"×"){closeOrReturn()}.apply{contentDescription="Close track summary";background=null;textSize=19f;setPadding(0,0,0,0)},FrameLayout.LayoutParams(dp(46),dp(28),Gravity.END))
        grip.addView(backButton,FrameLayout.LayoutParams(-2,dp(28),Gravity.START))
        // On a Te Araroa section Back always leads to the whole trail (as on the website), the map left where it is.
        backButton.setOnClickListener{if(taBack){stack.clear();returnTo=null;restoring=true;keepView=true;showTeAraroa(false)}else goBack()}
        grip.setOnClickListener{settle(if(level<2)level+1 else 1)}
        header.isClickable=true;header.setOnClickListener{settle(if(level<2)level+1 else 1)}
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(photo,LinearLayout.LayoutParams(dp(56),dp(56)).apply{marginEnd=dp(12)})
        val titles=NativeUi.column(activity)
        titles.addView(name)
        val line=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        line.addView(closedBadge,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)})
        line.addView(subtitle,LinearLayout.LayoutParams(0,-2,1f))
        titles.addView(line,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(2)})
        top.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(aside,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
        header.addView(top,LinearLayout.LayoutParams(-1,-2))
        // Stats size to their text (two lines, larger phone fonts too) and line up by their tops.
        statsRow=LinearLayout(activity).apply{isBaselineAligned=false;for((i,v) in stats.withIndex())addView(v.apply{minHeight=dp(40);maxLines=2},LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)marginStart=dp(8)})}
        // The figures scroll with the page; only the name and photo stay at the top.
        body.addView(statsRow,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
        body.addView(taTop,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        body.addView(optionScroll,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        fromChip.setOnClickListener{pickOption(it,true)};toChip.setOnClickListener{pickOption(it,false)}
        body.addView(chart,LinearLayout.LayoutParams(-1,dp(230)))
        body.addView(partScroll,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        body.addView(partText,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        body.addView(walkTitle,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        walkTitle.setOnClickListener{walkRef?.let{(guid,name)->showWalk(guid,name,lineNow.firstOrNull()?:Coordinate(0.0,0.0))}}
        body.addView(intro,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(docAlerts,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(taBelow,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(factsScroll,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        body.addView(huts,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        body.addView(sections,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        body.addView(page,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(16)})
        body.addView(saveButton,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(10)})
        body.addView(offlineButton,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(10)})
        body.addView(offlineStatus,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        body.addView(taLink,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        body.addView(fine,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(14)})
        panel.addView(grip,LinearLayout.LayoutParams(-1,dp(28)))
        panel.addView(header,LinearLayout.LayoutParams(-1,-2))
        panel.addView(bodyScroll.apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(panel,FrameLayout.LayoutParams(-1,-1,Gravity.BOTTOM))
        root.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(visible&&!dragging)place(false)}
        // The compact stop follows the header (a subtitle or photo arriving).
        header.addOnLayoutChangeListener{_,_,top,_,bottom,_,oldTop,_,oldBottom->if(visible&&!dragging&&level<2&&bottom-top!=oldBottom-oldTop)place(true)}
        chart.onInspect={p,_->if(chart.rangeEnds()==null)onPoint(p.point)}
        // As on routes: a dragged section shows A and B on the map (the chart shows its distance, ascent and descent).
        chart.onRange={_->val ends=chart.rangeEnds();if(ends!=null){onPoint(null);onRange(listOf(ends.first,ends.second))}else onRange(emptyList())}
        chart.onLeave={onPoint(null);onRange(emptyList())}
    }

    var visible=false;private set
    /** 0 compact (title and stats), 1 middle (the chart too; the map stays
     * usable above), 2 full. */
    private var level=0
    private var dragging=false;private var startY=0f;private var startShift=0f
    private fun inner()=(root.height-root.paddingTop-root.paddingBottom).takeIf{it>0}?:activity.resources.displayMetrics.heightPixels
    private fun full()=(inner()*.86f).toInt()
    // Collapsed: the name and photo, and the figures below them (first in the page).
    private fun compact()=dp(28)+(header.height.takeIf{it>0}?:dp(64))+(if(statsRow.visibility==View.VISIBLE)(statsRow.height.takeIf{it>0}?:dp(44))+dp(10) else 0)
    /** Middle: the chart and the section chips below it, with the map still usable above. */
    private fun middle()=max(compact()+dp(300),(inner()*.55f).toInt()).coerceAtMost(full())
    private fun stop(l:Int)=when(l){0->compact();1->middle();else->full()}
    /** Where the sheet is sliding to (NaN: not sliding). */
    private var slidingTo=Float.NaN
    /** The map is never resized for the sheet (that made the renderer reload and the
     * view jump): the sheet slides over it. [onCover] follows the sheet's top as it
     * moves (the map's own controls ride above it); [onInset] gives the stop it
     * settles at, so framing a track uses the map above the sheet. */
    private fun cover(){onCover(if(visible&&panel.visibility==View.VISIBLE)(full()-panel.translationY).toInt().coerceAtLeast(0)else 0)}
    private fun place(animate:Boolean){
        val lp=panel.layoutParams as FrameLayout.LayoutParams
        if(lp.height!=full()){lp.height=full();panel.layoutParams=lp}
        if(map.layoutParams.height!=-1)map.layoutParams=map.layoutParams.apply{height=-1}
        val shown=stop(level);val target=(full()-shown).coerceAtLeast(0).toFloat()
        onInset(shown)
        if(animate||!slidingTo.isNaN()){
            if(slidingTo!=target){
                slidingTo=target
                panel.animate().translationY(target).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                    .setUpdateListener{cover()}.withEndAction{slidingTo=Float.NaN;cover()}.start()
            }
        }else{panel.translationY=target;cover()}
        grip.contentDescription=if(level==2)"Collapse track summary" else "Expand track summary"
    }
    private fun settle(l:Int){level=l.coerceIn(0,2);if(level==0)bodyScroll.smoothScrollTo(0,0);place(true)}
    private fun begin(rawY:Float){dragging=true;startY=rawY;panel.animate().cancel();slidingTo=Float.NaN;startShift=panel.translationY}
    private fun drag(rawY:Float){if(dragging){panel.translationY=(startShift+rawY-startY).coerceIn(0f,full().toFloat());cover()}}
    private fun finish(rawY:Float){
        if(!dragging)return;dragging=false
        val shown=full()-panel.translationY
        val nearest=(0..2).minByOrNull{abs(stop(it)-shown)}!!
        val moved=startY-rawY
        settle(when{moved>dp(32)&&nearest<=level->level+1;moved< -dp(32)&&nearest>=level->level-1;else->nearest})
    }

    private var request=0L
    private var link=""
    /** Clears the sheet for a new track or hut and slides it up. */
    /** The next page keeps the sheet where it is (Te Araroa's previous and next). */
    private var keepStop=false
    private fun begin(title:String,hut:Boolean):Long{
        val id=++request
        if(!(keepStop&&visible))level=0;keepStop=false
        visible=true;link="";sectionOf=false;parts.clear();partRow.removeAllViews();partScroll.visibility=View.GONE;choice=null;optionScroll.visibility=View.GONE;pageWords="";nearHuts=emptyList();recordHuts=null;pageLegs=emptyList();partText.removeAllViews();partText.visibility=View.GONE;walkRef=null
        name.text=title;subtitle.text="";subtitle.visibility=View.GONE;wholeSubtitle="";closedBadge.visibility=View.GONE;subtitle.maxLines=2
        photo.setImageDrawable(null);photo.visibility=View.GONE
        val labels=if(hut)listOf("Bunks","Elevation","Bookings","From road")else listOf("Distance","Ascent","Descent","Highest")
        for((i,s) in stats.withIndex()){s.label=labels[i];s.value(null)}
        chart.data(emptyList());chart.markers=emptyList();chart.message="";chart.visibility=if(hut)View.GONE else View.VISIBLE
        trackTitle=title;walkTitle.visibility=View.GONE;sections.removeAllViews();sections.visibility=View.GONE
        intro.visibility=View.GONE;intro.text="";factsScroll.visibility=View.GONE;facts.removeAllViews();huts.removeAllViews();huts.visibility=View.GONE;page.visibility=View.GONE;page.text="DOC page"
        aside.removeAllViews();taTop.removeAllViews();taTop.visibility=View.GONE;taBelow.removeAllViews();taBelow.visibility=View.GONE;docAlerts.removeAllViews();docAlerts.visibility=View.GONE
        taLink.removeAllViews();taLink.visibility=View.GONE;saveButton.visibility=View.GONE;offlineButton.visibility=View.GONE;offlineStatus.visibility=if(taDownloading)View.VISIBLE else View.GONE;fine.visibility=View.GONE;statsRow.visibility=View.VISIBLE;chart.markers=emptyList()
        onLine(null,null);onPoint(null);onRange(emptyList());loader.cancelTrackProfile();chart.showAll();lineNow=emptyList()
        if(panel.visibility!=View.VISIBLE){panel.visibility=View.VISIBLE;panel.translationY=full().toFloat()}
        panel.post{if(id==request)place(true)}
        return id
    }
    /** [walkOf]: the walk the map gives this track (a section of a large walk). */
    fun show(trackName:String,at:Coordinate,walkOf:String?=null){
        remember({show(trackName,at,walkOf)},false)
        val id=begin(trackName,false);fitNext=false
        var walk:DocTracks.Walk?=null;var track:DocTracks.Track?=null;var walkDone=false;var trackDone=false;var lineStarted=false
        var mapped:List<Coordinate>?=null;var nameDone=false;var placeStarted=false
        var byName:DocTracks.Walk?=null;var byPlace:DocTracks.Walk?=null;var placeDone=false;var placeMeters=0.0
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
        fun walkFound(w:DocTracks.Walk?,fromName:Boolean){
            if(id!=request||walkDone)return
            val guid=w?.guid
            val collection=w!=null&&DocWalks.collection(w.name)
            // The track is the DOC walk (DOC's name for it, or the walk's line runs
            // along it and is not much longer): the walk's own summary. A DOC
            // collection ("Wilkin Valley tracks") stays on the tapped track.
            val same=w!=null&&guid!=null&&!collection&&(fromName||DocWalks.sameName(trackName,w.name)||DocTracks.length(mapped.orEmpty())>=.6*placeMeters)
            if(same){showWalk(guid!!,w!!.name.ifBlank{trackName},at,mapName=trackName);return}
            // Found by place and longer than the track: the track is one section of it.
            val partOf=w!=null&&!fromName
            walk=w;walkDone=true;w?.let{applyWalk(id,it,partOf=partOf)};sectionOf=partOf;describe(walk,track);decide()
            if(partOf&&guid!=null)legOf(id,guid,w!!.page,mapped.orEmpty())
        }
        // DOC's walk of this name; else the walk whose line runs along the
        // track, whatever it is called (the map's Rabbit Pass route is part of
        // DOC's "Wilkin Valley - East Matukituki Traverse"). Both are asked at
        // once, so a different name costs no extra wait.
        fun resolve(){if(!nameDone)return;if(byName!=null)walkFound(byName,true)else if(placeDone)walkFound(byPlace,false)}
        fun matchByPlace(){
            val map=mapped?:return
            if(placeStarted)return;placeStarted=true
            if(walkOf!=null){
                // The map's walk for this section: no search by place.
                placeMeters=Double.MAX_VALUE
                fetch(DocTracks.walkByGuidUrl(walkOf),{DocTracks.parseWalks(text(it)).firstOrNull()}){w->
                    byPlace=w?:DocTracks.Walk(walkInfo(walkOf)?.name.orEmpty(),"","","","","https://www.doc.govt.nz/link/${walkOf.replace("-","")}.aspx",null)
                    placeDone=true;resolve()}
                return
            }
            val b=DocTracks.bounds(map,150.0)
            if(map.size<2||b==null){placeDone=true;resolve();return}
            fetch(DocTracks.linesNearUrl(b),{DocTracks.alongTrack(DocTracks.parseWalkLines(text(it)),map)}){line->
                if(id!=request)return@fetch
                if(line==null){placeDone=true;resolve();return@fetch}
                placeMeters=line.meters
                // Not every DOC walk is in DOC's walk list (the Milford Track): then its DOC page.
                fetch(DocTracks.walkByGuidUrl(line.guid),{DocTracks.parseWalks(text(it)).firstOrNull()}){w->
                    byPlace=w?:DocTracks.Walk(walkInfo(line.guid)?.name.orEmpty(),"","","","","https://www.doc.govt.nz/link/${line.guid.replace("-","")}.aspx",null)
                    placeDone=true;resolve()}
            }
        }
        mapLine(trackName,at){paths->if(id==request){mapped=DocTracks.chain(paths,at,25.0);decide();matchByPlace()}}
        fetch(DocTracks.walkUrl(trackName,at.longitude,at.latitude),{DocTracks.nearestWalk(DocTracks.parseWalks(text(it)),at)}){found->
            if(id!=request)return@fetch
            nameDone=true;byName=found;resolve()
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
    /** What is shown now (again, to come back to it) and whether it is a hut. */
    private var current:(()->Unit)?=null;private var currentIsHut=false
    /** The track or walk a hut was opened from: its X goes back there. */
    private var returnTo:(()->Unit)?=null
    private var restoring=false
    /** Back to a track from a hut: the map stays where it is (no re-framing). */
    private var keepView=false
    /** Pages to go back to (Back, top left): from a Te Araroa page to what it opened. */
    private val stack=ArrayList<Pair<String,()->Unit>>()
    private var currentLabel="";private var currentTa=false;private var taBack=false
    /** A Te Araroa page is open (taps on the trail stay in it). */
    val inTeAraroa get()=visible&&currentTa
    /** [push]: Back returns to the page open now (always so from a Te Araroa page). */
    private fun remember(again:()->Unit,hut:Boolean,label:String="",ta:Boolean=false,push:Boolean=false){
        if(restoring)restoring=false
        else if((push||currentTa)&&visible&&current!=null){stack.add((currentLabel.ifBlank{"Back"}) to current!!);returnTo=null}
        else{stack.clear()
            if(hut){if(visible&&!currentIsHut&&current!=null)returnTo=current else if(!visible)returnTo=null}
            else returnTo=null}
        current=again;currentIsHut=hut;currentLabel=label;currentTa=ta;taBack=false
        backButton.text="‹ ${stack.lastOrNull()?.first.orEmpty()}";backButton.visibility=if(stack.isEmpty())View.GONE else View.VISIBLE
    }
    /** Back to the page this one was opened from, the map left where it is. */
    private fun goBack(){val (_,again)=stack.removeLastOrNull()?:return;restoring=true;keepView=true;again()}
    /** The X (and back at the lowest stop): to the track a hut was opened from, else closed. */
    fun closeOrReturn(){
        val again=returnTo
        if(again!=null&&visible){returnTo=null;restoring=true;keepView=true;again()}else hide()
    }
    fun hide(){
        returnTo=null;stack.clear();backButton.visibility=View.GONE;currentTa=false;taBack=false
        if(!visible)return
        visible=false;request++;loader.cancelTrackProfile();onLine(null,null);onPoint(null);onRange(emptyList());chart.clearInspection()
        if(map.layoutParams.height!=-1)map.layoutParams=map.layoutParams.apply{height=-1}
        onInset(0);onClosed();slidingTo=Float.NaN
        panel.animate().translationY(full().toFloat()).setDuration(160).setUpdateListener{onCover((full()-panel.translationY).toInt().coerceAtLeast(0))}.withEndAction{if(!visible)panel.visibility=View.GONE;onCover(0);onClosed()}.start()
    }
    fun back():Boolean{if(!visible)return false;if(level>0)settle(level-1)else if(stack.isNotEmpty())goBack()else closeOrReturn();return true}

    /** A track section named by its large DOC walk (a circuit): the walk's own
     * details and line, followed on the map's tracks. */
    /** [mapName]: the map's name for the tapped track (it can differ from DOC's: "Mt Iron Track"). */
    fun showWalk(guid:String,label:String,at:Coordinate,fit:Boolean=false,mapName:String?=null){
        remember({showWalk(guid,label,at,false,mapName)},false)
        val info=walkInfo(guid)
        val id=begin(info?.name?.takeIf{it.isNotBlank()}?:label,false);fitNext=fit
        if(info!=null)setSubtitle(listOf(info.difficulty,info.time).filter{it.isNotBlank()}.joinToString(" · "))
        var listed=false
        fetch(DocTracks.walkByGuidUrl(guid),{DocTracks.parseWalks(text(it)).firstOrNull()}){w->if(id==request&&w!=null){listed=true;applyWalk(id,w,false)}}
        docAlertsFor(id,guid,info?.page?:"https://www.doc.govt.nz/link/${guid.replace("-","")}.aspx")
        val page=info?.page?:"https://www.doc.govt.nz/link/${guid.replace("-","")}.aspx"
        // DOC's line is the walk; DOC's legs give its route and direction when
        // they cover most of it. The map's own tracks along it are drawn.
        walkRoute(guid,page){route,pageSections,docPaths,overview->
            if(id!=request)return@walkRoute
            // DOC's page summary: everything for walks not in DOC's walk list (the Milford Track).
            overview?.let{o->
                if(o.title.isNotBlank())name.text=o.title
                if(wholeSubtitle.isEmpty())setSubtitle(listOf(o.difficulty,o.time).filter{it.isNotBlank()}.joinToString(" · "))
                if(!listed){
                    if(o.intro.isNotBlank()&&intro.text.isEmpty()){intro.text=o.intro;intro.visibility=View.VISIBLE}
                    if(photo.visibility!=View.VISIBLE)thumbnail(id,o.image)
                }
            }
            if(page.startsWith("https://")){link=page;this.page.visibility=View.VISIBLE;this.page.setOnClickListener{open(link)}}
            if(pageSections.isNotEmpty())showSections(pageSections)
            // DOC's legs as ways to walk it (from the Tiki Trail or the gondola, to
            // the summit or the saddle): followed over the map's tracks.
            fun follow(){
            val distance=overview?.distance.orEmpty()
            val main=WalkRoutes.walkLine(route,docPaths,distance)
            val byLegs=WalkRoutes.extent(route,docPaths,distance).second
            // No DOC line (DOC has none for the Mount Iron Track): the map's tracks of the walk's name.
            val corridor=if(main.size>=2)listOf(main)else emptyList()
            val withAlong:((List<Pair<String,List<Coordinate>>>)->Unit)->Unit=if(corridor.isEmpty()){cb->cb(emptyList())}else{cb->alongLine(corridor,cb)}
            withAlong{along->
                if(id!=request)return@withAlong
                // The map's tracks named like the walk (or the tapped track) join it:
                // DOC's line can stop short (the Ben Lomond Track's, at the saddle).
                val names=(listOf(label,name.text.toString()).filter{it.isNotBlank()&&DocWalks.sameName(it,name.text.toString())}+listOfNotNull(mapName?.takeIf{it.isNotBlank()})).distinct()
                val named=ArrayList<Pair<String,List<Coordinate>>>();var waiting=names.size
                fun finish(line:List<Coordinate>,pieces:List<Pair<String,List<Coordinate>>>,turned:Boolean=false){
                if(line.size<2){lineFound(id,emptyList());return}
                val found=ArrayList<Part>()
                if(byLegs){
                    // Each leg on the final line: looked for from about where it is
                    // along DOC's route, so a loop's legs stay in order.
                    val scale=DocTracks.length(line)/max(1.0,route!!.meters)
                    for(sec in route.sections){
                        val a=DocTracks.along(line,DocTracks.pointAt(route.points,sec.from),max(0.0,sec.from*scale-2000))
                        val b=DocTracks.along(line,DocTracks.pointAt(route.points,sec.to),a)
                        val km=sec.km?.let{if(it%1.0==0.0)"${it.toInt()} km" else "$it km"}
                        if(b-a>100)found.add(Part(WalkRoutes.title(sec.title),
                            listOfNotNull(sec.time.takeIf{it.isNotBlank()},km).joinToString(" · "),listOf(a to b),sec.blocks))
                    }
                }
                for((n,spans) in DocTracks.namedSpans(pieces,line))if(!DocWalks.sameName(n,name.text.toString()))found.add(Part(n,"",spans,emptyList()))
                lineFound(id,line,found,byLegs||turned)
                }
                fun ready(){
                if(id!=request)return
                // (Two names for one track find its pieces twice: once each.)
                val pieces=along+named.distinctBy{it.second}.filter{n->along.none{it.second==n.second}}
                val start=main.firstOrNull()?:at
                var mapped=DocTracks.chain(pieces.map{it.second},start,150.0)
                if(mapped.size>=2&&DocTracks.meters(mapped.last(),start)<DocTracks.meters(mapped.first(),start))mapped=mapped.reversed()
                if(byLegs&&route!!.sections.size>=2&&mapped.size>=2){
                    // A loop: walk it the way DOC describes.
                    val a=DocTracks.along(mapped,DocTracks.pointAt(route.points,route.sections[0].to));val b=DocTracks.along(mapped,DocTracks.pointAt(route.points,route.sections[1].to))
                    if(b<a)mapped=mapped.reversed()
                }
                val drawn=if(mapped.size>=2&&DocTracks.length(mapped)>=.7*DocTracks.length(main))mapped else main
                // A line that stops short of the length DOC states (where the map's
                // track changes name): on to the tracks' natural ends when that fits.
                val target=WalkRoutes.stated(distance)
                fun asBefore(){
                fun extendOrFinish(){
                    if(!byLegs&&target!=null&&drawn.size>=2&&DocTracks.length(drawn)<(1-WalkOptions.MATCH)*target){
                        extendWalk(drawn,target){longer->if(id==request)finish(longer?:drawn,pieces)};return}
                    finish(drawn,pieces)
                }
                // A loop (DOC: "14 km loop") that does not come back to its start
                // (the Lake Rere Walk returns on the Greenstone Track): closed over
                // the map's tracks, the way DOC goes round it.
                val loop=!byLegs&&LOOP.containsMatchIn(listOf(distance,overview?.time.orEmpty(),info?.time.orEmpty(),name.text).joinToString(" "))
                val way=DocPages.clockwise(pageSections)
                if(loop&&drawn.size>=2&&DocTracks.meters(drawn.first(),drawn.last())<=WalkOptions.CLOSED_METERS){
                    if(way!=null){finish(WalkOptions.turn(drawn,way),pieces,true);return}}
                else if(loop&&target!=null&&drawn.size>=2){
                    closeWalk(drawn,target,way){closed->if(id==request){if(closed!=null)finish(closed,pieces,way!=null)else extendOrFinish()}};return}
                extendOrFinish()
                }
                // DOC's description followed first (its places and tracks in order;
                // a loop back to its start; the way round DOC says).
                if(!byLegs&&docPaths.isNotEmpty())followWalk(pageSections,name.text.toString(),distance,info?.time?.takeIf{it.isNotBlank()}?:overview?.time.orEmpty(),docPaths,drawn){r->
                    if(id!=request)return@followWalk
                    if(r!=null)finish(r.line,r.pieces,true)else asBefore()}
                else asBefore()
                }
                if(waiting==0)ready()
                for(n in names)mapLine(n,at){paths->named.addAll(paths.map{n to it});waiting--;if(waiting==0)ready()}
            }
            }
            val level=info?.difficulty?.takeIf{it.isNotBlank()}?:overview?.difficulty.orEmpty()
            fun runtime(){
            val legs=DocPages.legs(pageSections);val starts=DocPages.starts(pageSections)
            if((WalkOptions.alternatives(legs)||starts.size>=2)&&docPaths.isNotEmpty())walkOptions(guid,legs,starts,name.text.toString(),info?.time?.takeIf{it.isNotBlank()}?:overview?.time.orEmpty(),docPaths){c->
                if(id!=request)return@walkOptions
                if(c!=null)showChoice(id,c,level)else follow()}
            else follow()
            }
            // The walk as DOC draws it (its own line, as in DOC's GPX): DOC's pieces joined
            // end to end from where it was tapped, not rerouted. Without a DOC line, the
            // map's tracks of the walk's name. (Compiled and rerouted walks: [DOC_ROUTES] off.)
            if(!DOC_ROUTES){walkRecord(guid){rec->
                if(id!=request)return@walkRecord
                if(rec==null){runtime();return@walkRecord}
                pageLegs=DocPages.legs(pageSections);recordHuts=rec.huts
                if(rec.verified&&rec.ways.isNotEmpty())showRecord(id,rec,level)
                else{
                    val line=rec.line.takeIf{it.size>=2}?:WalkRoutes.walkLine(null,docPaths,overview?.distance.orEmpty())
                    if(line.size>=2)lineFound(id,line)
                    else mapLine(name.text.toString(),at){paths->if(id==request)lineFound(id,DocTracks.chain(paths,at))}
                }
            };return@walkRoute}
            // DOC's pieces as one route (DocRoute): split where they meet, a loop closed
            // at its car park, side trips left out, from where DOC's line starts.
            val line=DocRoute.route(docPaths,WalkRoutes.stated(overview?.distance.orEmpty()))
            if(line.size>=2)lineFound(id,line,pieces=docPaths)
            else mapLine(name.text.toString(),at){paths->if(id==request)lineFound(id,DocTracks.chain(paths,at))}
        }
    }
    /** A compiled walk's ways as From and To choices (loops have no To). */
    private fun showRecord(id:Long,rec:WalkRecords.Walk,difficulty:String){
        val ways=rec.ways.sortedByDescending{it.main}
        val starts=ways.map{it.from}.distinct();val ends=ways.map{if(it.shape=="loop")"" else it.to}.distinct()
        val options=ways.map{w->WalkOptions.Option(starts.indexOf(w.from),ends.indexOf(if(w.shape=="loop")"" else w.to),w.line,null,emptyList(),w.time,w.stops)}
            .distinctBy{it.start to it.end}
        showChoice(id,WalkOptions.Choice(starts,ends,options),difficulty)
    }
    /** DOC's legs ("Mid Caples Hut to McKellar Hut") on a compiled way, from where its named stops are. */
    private fun legParts(o:WalkOptions.Option):List<Part>{
        if(o.stops.size<2||pageLegs.isEmpty())return emptyList()
        val out=ArrayList<Part>();var from=0
        for(leg in pageLegs){
            val a=(from until o.stops.size).firstOrNull{WalkRoutes.same(o.stops[it].first,leg.from)}?:continue
            val b=(a+1 until o.stops.size).firstOrNull{WalkRoutes.same(o.stops[it].first,leg.to)}?:continue
            val km=leg.km?.let{if(it%1.0==0.0)"${it.toInt()} km" else "$it km"}
            out.add(Part(WalkRoutes.title(leg.title),listOfNotNull(leg.time.takeIf{it.isNotBlank()},km).joinToString(" · "),listOf(o.stops[a].second to o.stops[b].second),leg.blocks))
            from=b
        }
        return out
    }
    private var pageLegs=emptyList<DocPages.Leg>()
    /** The huts DOC links as on the compiled walk in view (null: not a compiled walk). */
    private var recordHuts:List<WalkRecords.Hut>?=null
    /** DOC's ways to walk the walk in view (WalkOptions) and the one shown. */
    private var choice:WalkOptions.Choice?=null;private var choiceStart=0;private var choiceEnd=0;private var choiceLevel=""
    private fun showChoice(id:Long,c:WalkOptions.Choice,difficulty:String){
        choice=c;choiceLevel=difficulty;choiceStart=c.options[0].start;choiceEnd=c.options[0].end;showOption(id)
    }
    /** The chosen way: its line, DOC's time for it, and the map's tracks along it. */
    private fun showOption(id:Long){
        val c=choice?:return;val o=c.option(choiceStart,choiceEnd)?:return
        val starts=c.starts.indices.filter{s->c.options.any{it.start==s}};val ends=c.ends.indices.filter{e->c.option(choiceStart,e)!=null}
        fromChip.text=if(starts.size>1)"From ${c.starts[choiceStart]}  ▾" else "From ${c.starts[choiceStart]}"
        toChip.text=if(ends.size>1)"To ${c.ends[choiceEnd]}  ▾" else "To ${c.ends[choiceEnd]}"
        toChip.visibility=if(c.ends[choiceEnd].isBlank())View.GONE else View.VISIBLE
        fromChip.isClickable=starts.size>1;toChip.isClickable=ends.size>1
        optionScroll.visibility=if(c.options.size>1)View.VISIBLE else View.GONE
        setSubtitle(listOf(choiceLevel,o.leg?.time.orEmpty().ifBlank{o.time}).filter{it.isNotBlank()}.joinToString(" · "))
        val found=legParts(o)+DocTracks.namedSpans(o.pieces,o.line).filter{(n,_)->!DocWalks.sameName(n,name.text.toString())}.map{(n,spans)->Part(n,"",spans,emptyList())}
        onRange(emptyList());chart.showAll();lineFound(id,o.line,found,true)
    }
    private fun pickOption(anchor:View,start:Boolean){
        val c=choice?:return
        val items=if(start)c.starts.indices.filter{s->c.options.any{it.start==s}}.map{it to c.starts[it]}
            else c.ends.indices.filter{e->c.option(choiceStart,e)!=null}.map{it to c.ends[it]}
        val menu=android.widget.PopupMenu(activity,anchor);for((i,t) in items)menu.menu.add(0,i,0,(if(start)"From " else "To ")+t)
        menu.setOnMenuItemClickListener{item->
            if(start){choiceStart=item.itemId;if(c.option(choiceStart,choiceEnd)==null)choiceEnd=c.options.first{it.start==choiceStart}.end}else choiceEnd=item.itemId
            showOption(request);true}
        menu.show()
    }
    /** A hut or bivouac: DOC's category, bunks, bookings, walk from the road
     * end, facilities, photo, page text, and its elevation. */
    fun showHut(hutName:String,at:Coordinate){
        remember({showHut(hutName,at)},true)
        val id=begin(hutName,true);fitNext=false
        onPoint(at);onTeAraroa(id,at)
        loader.trackProfile(listOf(at,Coordinate(at.longitude+.00002,at.latitude))){values,_->
            if(id==request)values.firstOrNull()?.height?.let{stats[1].value(metres(it))}}
        var asset:DocTracks.HutAsset?=null;var hutPage:DocTracks.HutPage?=null
        fun describeHut(){
            // The map names some huts only "Hut" or "Bivouac": DOC's name instead.
            if(hutName=="Hut"||hutName=="Bivouac")(hutPage?.name?:asset?.name)?.takeIf{it.isNotBlank()}?.let{name.text=it}
            val category=asset?.category.orEmpty().let{c->if(c.isBlank()||Regex("hut|biv",RegexOption.IGNORE_CASE).containsMatchIn(c))c else "$c hut"}
            val summary=listOf(category,hutPage?.place.orEmpty()).filter{it.isNotBlank()}.joinToString(" · ")
            setSubtitle(summary)
            closedBadge.visibility=if(asset?.closed==true)View.VISIBLE else View.GONE
            asset?.let{a->Regex("\\d+").find(a.bunks)?.let{stats[0].value(it.value)};if(a.roadEnd.isNotBlank())stats[3].value(a.roadEnd.replace(" hours"," hr").replace(" hour"," hr"))}
            hutPage?.let{p->stats[2].value(if(p.bookable)"Required" else "Not needed")}
            facts.removeAllViews()
            for(v in (hutPage?.facilities?:listOfNotNull(asset?.facilities?.takeIf{it.isNotBlank()})).map{it.substringBefore(" - ").trim()}.distinct())
                facts.addView(chip(v),LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)})
            factsScroll.visibility=if(facts.childCount>0)View.VISIBLE else View.GONE
        }
        fetch(DocTracks.hutAssetsUrl(at.longitude,at.latitude),{DocTracks.matchHut(DocTracks.parseHutAssets(text(it)),hutName,at,{a->a.name},{a->a.point})}){found->
            if(id!=request)return@fetch;asset=found;describeHut()}
        fetch(DocTracks.hutPagesNearUrl(at.longitude,at.latitude),{DocTracks.matchHut(DocTracks.parseHutPages(text(it)),hutName,at,{p->p.name},{p->p.point})}){found->
            if(id!=request||found==null)return@fetch;hutPage=found;describeHut()
            if(found.page.startsWith("https://")){link=found.page;page.visibility=View.VISIBLE;page.setOnClickListener{open(link)};pageText(id,found.page,true)}
            thumbnail(id,found.thumbnail)
        }
    }
    private fun chip(v:String)=TextView(activity).apply{text=v;textSize=12f;setTextColor(NativeUi.ink);setPadding(dp(10),dp(5),dp(10),dp(5));background=NativeUi.background(activity,NativeUi.chip,14)}
    /** DOC's page text (and, for huts, its lead paragraph as the introduction). */
    private fun pageText(id:Long,url:String,lead:Boolean=false){
        if(!url.startsWith("https://"))return
        fetch(url,{bytes->val html=text(bytes);DocPages.intro(html) to DocPages.parse(html)}){found->
            if(id!=request||found==null)return@fetch
            if(lead&&found.first.isNotBlank()&&intro.visibility!=View.VISIBLE){intro.text=found.first;intro.visibility=View.VISIBLE}
            if(found.second.isNotEmpty())showSections(found.second)
        }
    }
    private fun thumbnail(id:Long,url:String){
        val size=dp(56)
        if(url.startsWith("https://"))fetch(url,{bytes->
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            var sample=1;while(min(bounds.outWidth,bounds.outHeight)/(sample*2)>=size)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
        }){bitmap->if(id==request&&bitmap!=null){photo.setImageBitmap(bitmap);photo.visibility=View.VISIBLE}}
    }
    private var trackTitle=""
    /** A DOC walk's details; [withPage]: also its page text (not yet asked). */
    /** [partOf]: the tapped track is one section of this (larger) walk: the
     * walk is linked ("Part of …", its own time), and the track keeps its own
     * DOC facts in the header. */
    private fun applyWalk(id:Long,w:DocTracks.Walk,withPage:Boolean=true,partOf:Boolean=false){
        if(partOf){walkRef=w.guid?.let{it to w.name}
            walkTitle.text=android.text.SpannableString("Part of ${w.name}"+(if(w.time.isNotBlank())" · ${w.time}" else "")+"  ›").apply{setSpan(android.text.style.ForegroundColorSpan(NativeUi.orange),0,length,0)}
            walkTitle.visibility=View.VISIBLE;walkTitle.isClickable=walkRef!=null}
        else if(w.name.isNotBlank()&&!w.name.equals(trackTitle,true)){walkTitle.text=w.name;walkTitle.visibility=View.VISIBLE;walkTitle.isClickable=false}
        if(w.introduction.isNotBlank()){intro.text=w.introduction;intro.visibility=View.VISIBLE}
        if(!partOf&&(w.difficulty.isNotBlank()||w.time.isNotBlank()))setSubtitle(listOf(w.difficulty,w.time).filter{it.isNotBlank()}.joinToString(" · "))
        if(w.page.startsWith("https://")){link=w.page;page.visibility=View.VISIBLE;page.setOnClickListener{open(link)}
            // DOC's full page text arrives after everything else (about a second).
            if(withPage)pageText(id,w.page)}
        thumbnail(id,w.thumbnail)
    }
    /** Difficulty and time (DOC's walk), else the track's category and time; facts as chips. */
    /** The tapped track is a section of a larger DOC walk (its header shows the track's own DOC facts). */
    private var sectionOf=false
    private fun describe(w:DocTracks.Walk?,t:DocTracks.Track?){
        val line=if(w!=null&&!sectionOf)listOf(w.difficulty,w.time)else listOf(t?.category.orEmpty(),t?.duration.orEmpty())
        val summary=line.filter{it.isNotBlank()}.joinToString(" · ")
        setSubtitle(summary)
        closedBadge.visibility=if(t?.closed==true)View.VISIBLE else View.GONE
        // No tags under walks and tracks (type, dogs, category): the header says what matters.
        facts.removeAllViews();factsScroll.visibility=View.GONE
    }
    /** [line] in view: map, profile, stats and huts. [found]: its parts;
     * [oriented]: in DOC's direction already (otherwise it is turned to start
     * at its lower end, where tracks are usually walked from). */
    /** [pieces]: all of DOC's line, side trips included, for the huts on them. */
    private fun lineFound(id:Long,line:List<Coordinate>,found:List<Part> = emptyList(),oriented:Boolean=false,pieces:List<List<Coordinate>> = emptyList()){
        if(line.size<2){chart.visibility=View.GONE;return}
        if(!currentTa&&!currentIsHut)partOfTeAraroa(id,line)
        lineNow=line;onLine(line,null)
        if(keepView){keepView=false;fitNext=false}else if(fitNext){fitNext=false;onFit(line)}else onFit(line)
        stats[0].value(distance(DocTracks.length(line)));wholeStats[0]=distance(DocTracks.length(line))
        showParts(found)
        var checked=oriented
        loader.trackProfile(line){values,_->
            if(id!=request)return@trackProfile
            if(!checked){checked=true
                val first=values.firstOrNull{it.height!=null}?.height;val last=values.lastOrNull{it.height!=null}?.height
                if(first!=null&&last!=null&&first>last+30){
                    val total=DocTracks.length(line)
                    lineFound(id,line.reversed(),found.map{p->Part(p.title,p.detail,p.spans.map{(a,b)->total-b to total-a}.reversed(),p.blocks)},true,pieces);return@trackProfile}
            }
            chart.data(values);val totals=NativeGeo.totals(values.map{it.height});val measured=values.count{it.height!=null}>=2
            wholeStats=arrayOf(values.lastOrNull()?.let{distance(it.distance)}?:wholeStats[0],if(measured&&totals.complete)metres(totals.ascent)else null,
                if(measured&&totals.complete)metres(totals.descent)else null,totals.high?.let(::metres))
            if(selected<0)for(i in 0..3)stats[i].value(wholeStats[i])else select(selected,false)
            chart.refreshInspection()
        }
        val b=DocTracks.bounds(line+pieces.flatten())?:return
        fetch(DocTracks.hutsUrl(b),{DocTracks.stops(DocTracks.parseHuts(text(it)),line,pieces=pieces)}){stops->
            if(id!=request||stops.isNullOrEmpty()||lineNow!==line)return@fetch
            showHuts(stops)
            fetch(DocTracks.hutPagesUrl(b),{pages->DocTracks.joinHutPages(stops.map{it.hut},text(pages));true}){joined->if(id==request&&joined==true&&lineNow===line)showHuts(stops)}
        }
    }
    private fun showParts(found:List<Part>){
        parts.clear();parts.addAll(found);partRow.removeAllViews();selected=-1
        if(found.isEmpty()){partScroll.visibility=View.GONE;return}
        val titles=listOf("Whole")+found.map{it.title}
        for((i,t) in titles.withIndex()){
            val chip=TextView(activity).apply{text=t;textSize=12.5f;setPadding(dp(12),dp(6),dp(12),dp(6));isClickable=true;maxLines=1
                setOnClickListener{select(i-1,true)}}
            partRow.addView(chip,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)})
        }
        partScroll.visibility=View.VISIBLE;styleChips()
    }
    /** The header line for the whole line (kept while a part is shown). */
    private fun setSubtitle(text:CharSequence){wholeSubtitle=text;if(selected<0){subtitle.text=text;subtitle.visibility=if(text.isEmpty())View.GONE else View.VISIBLE}}
    private fun styleChips(){for(i in 0 until partRow.childCount){val c=partRow.getChildAt(i) as TextView;val on=i-1==selected
        c.setTextColor(if(on)NativeUi.onInk else NativeUi.ink);c.background=NativeUi.background(activity,if(on)NativeUi.ink else NativeUi.chip,14)}}
    /** Shows part [i] alone (-1: the whole line): chart zoomed and shaded,
     * its stats, DOC's time and distance for a leg, its DOC text, and on the map. */
    private fun select(i:Int,move:Boolean){
        selected=i.coerceIn(-1,parts.size-1);styleChips()
        if(selected<0){
            chart.showAll();onLine(lineNow,null);for(k in 0..3)stats[k].value(wholeStats[k])
            subtitle.text=wholeSubtitle;subtitle.visibility=if(wholeSubtitle.isEmpty())View.GONE else View.VISIBLE
            partText.visibility=View.GONE;if(intro.text.isNotEmpty())intro.visibility=View.VISIBLE
            if(move)onFit(lineNow);return
        }
        val p=parts[selected]
        onRange(emptyList());chart.focus(p.spans)
        val length=p.spans.sumOf{it.second-it.first};var up=0.0;var down=0.0;var complete=true;var high:Double?=null
        for((a,b) in p.spans){val sec=chart.sectionStats(a,b);if(sec==null||!sec.complete)complete=false else{up+=sec.ascent;down+=sec.descent}
            chart.highest(a,b)?.let{h->high=max(high?:h,h)}}
        stats[0].value(distance(length));stats[1].value(if(complete)metres(up)else null);stats[2].value(if(complete)metres(down)else null);stats[3].value(high?.let(::metres))
        if(p.detail.isNotBlank()){subtitle.text=p.detail;subtitle.visibility=View.VISIBLE}
        partText.removeAllViews()
        if(p.blocks.isNotEmpty()){fill(partText,p.blocks);partText.visibility=View.VISIBLE;intro.visibility=View.GONE}else{partText.visibility=View.GONE;if(intro.text.isNotEmpty())intro.visibility=View.VISIBLE}
        val pieces=p.spans.map{(a,b)->DocTracks.slice(lineNow,a,b)}.filter{it.size>=2}
        onLine(lineNow,pieces.flatten().takeIf{it.isNotEmpty()})
        if(move&&pieces.isNotEmpty())onFit(pieces.flatten())
    }
    /** For a track that is one section of a larger DOC walk: DOC's leg that
     * covers it ("Siberia Hut to Kerin Forks Hut", its time, distance and
     * text) instead of the whole walk's introduction. */
    private fun legOf(id:Long,guid:String,page:String,track:List<Coordinate>){
        if(track.size<2)return
        walkRoute(guid,page){route,_,_,_->
            if(id!=request||route==null)return@walkRoute
            val samples=DocTracks.resample(track,50.0,200)
            val leg=route.sections.mapNotNull{sec->
                val piece=DocTracks.slice(route.points,sec.from,sec.to);if(piece.size<2)return@mapNotNull null
                val share=samples.count{p->DocTracks.meters(DocTracks.pointAt(piece,DocTracks.along(piece,p)),p)<150}.toDouble()/samples.size
                if(share>=.5)sec to share else null
            }.minByOrNull{(sec,_)->sec.to-sec.from}?.first?:return@walkRoute
            partText.removeAllViews()
            val km=leg.km?.let{if(it%1.0==0.0)"${it.toInt()} km" else "$it km"}
            partText.addView(TextView(activity).apply{text=WalkRoutes.title(leg.title);textSize=14.5f;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
            listOfNotNull(leg.time.takeIf{it.isNotBlank()},km).joinToString(" · ").takeIf{it.isNotEmpty()}?.let{partText.addView(TextView(activity).apply{text=it;textSize=12.5f;setTextColor(NativeUi.muted)})}
            fill(partText,leg.blocks);partText.visibility=View.VISIBLE;intro.visibility=View.GONE
        }
    }
    private fun showSections(list:List<DocPages.Section>){
        sections.removeAllViews()
        pageWords=list.joinToString(" "){sec->DocPages.words(sec.blocks).joinToString(" "){it.text}}
        if(nearHuts.isNotEmpty())showHuts(nearHuts)
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
                is DocPages.Image->{photoBlock(content,b);continue}
            }
            content.addView(v,lp)
        }
    }
    /** A photo in DOC's text: full width, loaded when shown, with DOC's caption and credit. */
    private fun photoBlock(content:LinearLayout,b:DocPages.Image){
        val id=request
        val view=ImageView(activity).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;clipToOutline=true;background=NativeUi.background(activity,NativeUi.line,10);visibility=View.GONE;contentDescription=b.text.lineSequence().firstOrNull().orEmpty()}
        val params=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10);gravity=Gravity.CENTER_HORIZONTAL}
        content.addView(view,params)
        if(b.text.isNotBlank())content.addView(TextView(activity).apply{text=b.text;textSize=11.5f;setTextColor(NativeUi.muted);setLineSpacing(0f,1.1f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        val width=max(1,(content.width.takeIf{it>0}?:activity.resources.displayMetrics.widthPixels))
        fetch(b.url,{bytes->
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            var sample=1;while(bounds.outWidth/(sample*2)>=width)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
        }){bitmap->if(id==request&&bitmap!=null){
            // DOC publishes most photos small (390 px): shown at most 1.5 times their
            // pixels (stretched further they blur), large ones across the width.
            val shown=min(width,(bitmap.width*1.5f).roundToInt())
            params.width=if(shown>=width)-1 else shown;view.layoutParams=params
            view.setImageBitmap(bitmap);view.visibility=View.VISIBLE}}
    }
    /** DOC's page text for the walk in view (which huts it names), and the huts near its line. */
    private var pageWords="";private var nearHuts=emptyList<DocTracks.Stop>()
    private fun showHuts(near:List<DocTracks.Stop>){
        nearHuts=near
        // A compiled walk: exactly the huts DOC links as on it (details from DOC's hut
        // data near the line where it has them).
        val stops=recordHuts?.let{rh->rh.mapNotNull{h->near.firstOrNull{s->DocTracks.meters(s.hut.point,h.point)<150||DocTracks.hutName(s.hut.name).equals(h.name,true)}
            ?:lineNow.takeIf{it.size>=2}?.let{l->DocTracks.Stop(DocTracks.Hut(h.name,"","","",h.point),DocTracks.along(l,h.point))}}.sortedBy{it.along}}
            ?:DocTracks.named(near,pageWords)
        chart.markers=stops.map{it.along to DocTracks.hutName(it.hut.name)}
        huts.removeAllViews()
        for(s in stops){val h=s.hut
            val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(8));contentDescription=h.name
                isClickable=true;background=NativeUi.pressable(activity,null);setOnClickListener{showHut(h.name,h.point)}}
            row.addView(HutIcon(),LinearLayout.LayoutParams(dp(30),dp(30)).apply{marginEnd=dp(12)})
            val text=NativeUi.column(activity)
            text.addView(TextView(activity).apply{this.text=DocTracks.hutName(h.name);textSize=14f;setTextColor(NativeUi.ink);setTypeface(null,Typeface.BOLD);maxLines=1;ellipsize=TextUtils.TruncateAt.END})
            val detail=listOf(h.category.removeSuffix(" Hut").let{if(it.isBlank())"" else "$it hut"},bunks(h.bunks),if(h.bookable)"Bookable" else "").filter{it.isNotBlank()}.joinToString(" · ")
            if(detail.isNotEmpty())text.addView(TextView(activity).apply{this.text=detail;textSize=12f;setTextColor(NativeUi.muted);maxLines=1;ellipsize=TextUtils.TruncateAt.END})
            row.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(TextView(activity).apply{this.text=distance(s.along);textSize=12.5f;setTextColor(NativeUi.muted)},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            huts.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        huts.visibility=if(stops.isEmpty())View.GONE else View.VISIBLE
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
            paint.color=NativeUi.chip;c.drawCircle(width/2f,height/2f,width/2f,paint)
            paint.color=NativeUi.ink;c.drawPath(shape,paint)
        }
    }
    // ---- Te Araroa pages ----
    private fun km(v:Double)=TeAraroa.km(v)
    private fun kmIn(d:TeAraroa,v:Double)=d.kmIn(v,nobo)
    private fun kmRange(d:TeAraroa,a:Double,b:Double):String{val x=kmIn(d,a);val y=kmIn(d,b);return "km ${km(min(x,y))}–${km(max(x,y))}"}
    private fun shortName(n:String)=if(n.length<=24)n else "Back"
    /** Frames the map round a point (about [half] degrees each way), unless going back. */
    private fun fitPoint(p:Coordinate,half:Double){if(keepView){keepView=false;return};onFit(listOf(Coordinate(p.longitude-half,p.latitude-half*.7),Coordinate(p.longitude+half,p.latitude+half*.7)))}
    private fun label(text:CharSequence,size:Float=13.5f,color:Int=NativeUi.ink,bold:Boolean=false)=TextView(activity).apply{this.text=text;textSize=size;setTextColor(color);setLineSpacing(0f,1.15f);if(bold)setTypeface(null,Typeface.BOLD)}
    private fun dot(colour:Int,edge:Int=Color.WHITE)=View(activity).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(colour);setStroke(dp(2),edge)}}
    /** A list row: optional dot, title and detail, a value on the right. */
    private fun row(title:String,detail:String="",right:String="",colour:Int?=null,edge:Int=Color.WHITE,number:String="",action:()->Unit):View{
        val r=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(9),0,dp(9));isClickable=true;background=NativeUi.pressable(activity,null);contentDescription=title;setOnClickListener{action()}}
        if(number.isNotEmpty())r.addView(label(number,12.5f,NativeUi.muted).apply{gravity=Gravity.END},LinearLayout.LayoutParams(dp(26),-2).apply{marginEnd=dp(10)})
        colour?.let{r.addView(dot(it,edge),LinearLayout.LayoutParams(dp(12),dp(12)).apply{marginEnd=dp(12)})}
        val text=NativeUi.column(activity)
        text.addView(label(title,14f,NativeUi.ink,true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
        if(detail.isNotEmpty())text.addView(label(detail,12f,NativeUi.muted).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
        r.addView(text,LinearLayout.LayoutParams(0,-2,1f))
        if(right.isNotEmpty())r.addView(label(right,12.5f,NativeUi.muted),LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
        return r
    }
    private fun divider()=View(activity).apply{setBackgroundColor(NativeUi.line)}
    /** Text with tappable names in orange (each runs its action). */
    private fun links(vararg parts:Any):TextView{
        val b=android.text.SpannableStringBuilder()
        for(part in parts)when(part){
            is Pair<*,*>->{val start=b.length;b.append(part.first as String);@Suppress("UNCHECKED_CAST")val go=part.second as ()->Unit
                b.setSpan(object:android.text.style.ClickableSpan(){override fun onClick(v:View){go()};override fun updateDrawState(ds:android.text.TextPaint){ds.color=NativeUi.orange;ds.isUnderlineText=false}},start,b.length,0)}
            else->b.append(part.toString())}
        return label(b).apply{movementMethod=android.text.method.LinkMovementMethod.getInstance();highlightColor=Color.TRANSPARENT}
    }
    /** Southbound | Northbound, beside the title; changing it redraws the page in place. */
    private fun directionSwitch(){
        aside.removeAllViews()
        val pill=LinearLayout(activity).apply{background=GradientDrawable().apply{setColor(NativeUi.chip);cornerRadius=dpf(14f);setStroke(dp(1),NativeUi.line)};setPadding(dp(1),dp(1),dp(1),dp(1))}
        for((value,text) in listOf(false to "Southbound",true to "Northbound")){
            val on=value==nobo
            pill.addView(TextView(activity).apply{this.text=text;textSize=11.5f;setTypeface(null,Typeface.BOLD);setPadding(dp(8),dp(3),dp(8),dp(4))
                setTextColor(if(on)NativeUi.onInk else NativeUi.muted);background=if(on)NativeUi.background(activity,NativeUi.ink,13)else null;isClickable=true
                setOnClickListener{if(nobo!=value){nobo=value;prefs.edit().putBoolean("nobo",value).apply();restoring=true;keepView=true;current?.invoke()}}})
        }
        aside.addView(pill)
    }
    /** Reads the Trust's notes (bundled) once. */
    private fun notes(callback:(Map<String,TeAraroa.Notes>)->Unit){
        taNotes?.let{callback(it);return}
        runCatching{liveIo.execute{
            // The build stores a .gz asset decompressed, without its .gz (notes.json in the APK).
            val n=runCatching{activity.assets.open("te-araroa/notes.json").bufferedReader().use{TeAraroa.Notes.parseAll(it.readText())}}
                .recoverCatching{java.util.zip.GZIPInputStream(activity.assets.open("te-araroa/notes.json.gz")).bufferedReader().use{TeAraroa.Notes.parseAll(it.readText())}}.getOrDefault(emptyMap())
            main.post{taNotes=n;callback(n)}}}
    }
    /** Te Araroa's whole trail: direction, then Sections, Food (carries) and Bypasses. */
    fun showTeAraroa(push:Boolean=false,tab:Int=0){
        val d=teAraroa?:return
        var shown=tab
        remember({showTeAraroa(false,shown)},false,"Te Araroa",true,push)
        val restored=keepView
        val id=begin("Te Araroa",false);fitNext=false
        setSubtitle("Cape Reinga to Bluff");statsRow.visibility=View.GONE;chart.visibility=View.GONE
        directionSwitch()
        if(keepView)keepView=false else onFit(listOf(Coordinate(172.6,-34.4),Coordinate(168.3,-46.6)))
        val tabs=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        val list=NativeUi.column(activity)
        fun show(i:Int){shown=i
            for(k in 0 until tabs.childCount){val c=tabs.getChildAt(k) as TextView;val on=k==i
                c.setTextColor(if(on)NativeUi.onInk else NativeUi.ink);c.background=NativeUi.background(activity,if(on)NativeUi.ink else NativeUi.chip,14)}
            list.removeAllViews()
            when(i){
                0->{var region=""
                    for(s in d.walking(d.mains.sortedBy{it.from},nobo)){
                        if(s.region!=region){region=s.region;list.addView(label(region,13f,NativeUi.muted,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(14);bottomMargin=dp(2)})}
                        list.addView(row(s.name,number="${s.n}"){showTaSection(s.slug,push=true,fromList=true)},LinearLayout.LayoutParams(-1,-2))
                        list.addView(divider(),LinearLayout.LayoutParams(-1,dp(1)))}}
                1->{list.addView(label("Carries between places to buy food, ${if(nobo)"northbound" else "southbound"}. Days are rough.",13f,NativeUi.muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(6)})
                    for(c in d.carries(nobo)){
                        val to=d.stops.indexOf(c.to)
                        val detail=listOf("${km(c.km)} km",TeAraroa.daysText(c.days),c.hitch.takeIf{it.isNotEmpty()}?.joinToString(", "){it.name}?.let{"hitch: $it"}.orEmpty()).filter{it.isNotEmpty()}.joinToString(" · ")
                        list.addView(row("${c.from.name}  →  ${c.to.name}",detail,colour=MapLayers.foodColour(c.to.kind),edge=if(c.to.kind=="box")MapLayers.TA_LINE else NativeUi.line){showTaStop(to,push=true)}.apply{if(c.km>=100)setBackgroundColor(Color.argb(28,239,59,93))},LinearLayout.LayoutParams(-1,-2))
                        list.addView(divider(),LinearLayout.LayoutParams(-1,dp(1)))}}
                else->{list.addView(label("Ways around parts of the trail: for rivers in flood, by road, or by water. Dashed red on the map.",13f,NativeUi.muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(6)})
                    for(o in d.walking(d.options.sortedBy{it.from},nobo)){
                        list.addView(row(o.name,TeAraroa.optionKind(o).first,number="↳"){showTaSection(o.slug,push=true,fromList=true)},LinearLayout.LayoutParams(-1,-2))
                        list.addView(divider(),LinearLayout.LayoutParams(-1,dp(1)))}}
            }
        }
        for((i,t) in listOf("Sections","Food","Bypasses").withIndex())tabs.addView(TextView(activity).apply{text=t;textSize=13f;setTypeface(null,Typeface.BOLD);setPadding(dp(14),dp(6),dp(14),dp(6));isClickable=true;setOnClickListener{show(i)}},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)})
        taTop.addView(tabs,LinearLayout.LayoutParams(-1,-2));taTop.addView(list,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)});taTop.visibility=View.VISIBLE
        show(tab)
        fine.text="From Te Araroa Trust (teararoa.org.nz, CC BY 4.0); food stops from walkers' resupply guides.";fine.visibility=View.VISIBLE
        offlineButton.visibility=View.VISIBLE;offlineButton.setOnClickListener{taOffline(d,null)}
        middle(id)
    }
    /** One section (or bypass): previous and next, profile, introduction, live alerts,
     * the next food, bypasses, the Trust's notes; [fromList]: opened from the trail page. */
    fun showTaSection(slug:String,push:Boolean=false,fromList:Boolean=false){
        val d=teAraroa?:return;val s=d.bySlug[slug]?:return
        remember({showTaSection(slug,false,fromList)},false,shortName(s.name),true,push)
        // "‹ Te Araroa" takes precedence over any earlier page.
        stack.clear();returnTo=null;taBack=true;backButton.text="‹ Te Araroa";backButton.visibility=View.VISIBLE
        val id=begin(s.name,false);fitNext=false
        fun sub()=if(s.alternative)"${TeAraroa.optionKind(s).first} · leaves the trail at km ${km(kmIn(d,if(nobo)s.to else s.from))}, rejoins at km ${km(kmIn(d,if(nobo)s.from else s.to))}"
            else "Section ${s.n} · ${kmRange(d,s.from,s.to)}"
        setSubtitle(sub());subtitle.maxLines=if(s.alternative)2 else 1
        if(!fromList)directionSwitch()
        // Previous and next along the trail (walking order), on one line.
        val (prev,next)=d.neighbours(s,nobo)
        if(prev!=null||next!=null){
            val steps=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
            for((t,back) in listOf(prev to true,next to false)){
                val cell=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(7),dp(8),dp(7))}
                if(t!=null){
                    cell.background=NativeUi.pressable(activity,GradientDrawable().apply{setColor(Color.TRANSPARENT);cornerRadius=dpf(10f);setStroke(dp(1),NativeUi.line)});cell.isClickable=true
                    cell.contentDescription="${if(back)"Previous" else "Next"} section: ${t.name}"
                    cell.setOnClickListener{restoring=true;keepStop=true;showTaSection(t.slug,false,fromList)}
                    val name=label(t.name,13f,NativeUi.ink,true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;gravity=Gravity.CENTER;if(back)setPadding(0,0,dp(10),0)else setPadding(dp(10),0,0,0)}
                    val arrow=label(if(back)"‹" else "›",18f,NativeUi.ink)
                    if(back){cell.addView(arrow);cell.addView(name,LinearLayout.LayoutParams(0,-2,1f))}else{cell.addView(name,LinearLayout.LayoutParams(0,-2,1f));cell.addView(arrow)}
                }
                steps.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply{if(back)marginEnd=dp(4)else marginStart=dp(4)})
            }
            taTop.addView(steps,LinearLayout.LayoutParams(-1,-2))
        }
        if(s.alternative)taTop.addView(label(TeAraroa.optionKind(s).second),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        taTop.visibility=View.VISIBLE
        // The Trust's alerts where a walk shows DOC's: below the profile and introduction.
        live(TeAraroa.trackApi(slug)){body->if(id==request)alerts(docAlerts,body?.let{runCatching{TeAraroa.Alert.parseTrust(it)}.getOrNull()},"Trail alert","Couldn’t load Te Araroa’s current alerts.",TeAraroa.ALERTS_PAGE,d,liveSaved[TeAraroa.trackApi(slug)])}
        // The line in walking order: map, profile and stats (DOC's huts along it marked).
        val line=d.line(s).let{if(nobo)it.reversed()else it}
        lineFound(id,line,oriented=true)
        // Food: the next place to buy it, and towns a hitch away before it.
        val (stop,gap,hitch)=d.nextFood(s,nobo)
        val food=LinearLayout(activity).apply{gravity=Gravity.TOP}
        food.addView(dot(MapLayers.foodColour("supermarket")),LinearLayout.LayoutParams(dp(10),dp(10)).apply{topMargin=dp(5);marginEnd=dp(10)})
        val foodText=NativeUi.column(activity)
        if(stop!=null){val i=d.stops.indexOf(stop)
            foodText.addView(links("Next food: ",stop.name to {showTaStop(i,push=true)},if(gap<.5)" (at the start)" else " in ${km(gap)} km"))
            if(hitch.isNotEmpty())foodText.addView(links("Or hitch to: ",*hitch.flatMapIndexed{k,h->listOf(if(k>0)", " else "",h.name to {showTaStop(d.stops.indexOf(h),push=true)}," (${km(h.off/1000)} km off)")}.toTypedArray()).apply{textSize=12.5f;setTextColor(NativeUi.muted)})
        }else foodText.addView(label("No more food stops this way."))
        food.addView(foodText,LinearLayout.LayoutParams(0,-2,1f))
        taBelow.addView(food,LinearLayout.LayoutParams(-1,-2))
        // Bypasses here (or, for one, the section it replaces).
        val others=if(s.alternative)d.mains.filter{min(it.to,s.to)-max(it.from,s.from)>2}else d.options.filter{min(it.to,s.to)-max(it.from,s.from)>.3}
        if(others.isNotEmpty())taBelow.addView(links(if(s.alternative)"Instead of: " else "Bypasses here: ",*others.flatMapIndexed{k,o->listOf(if(k>0)", " else "",(if(o.alternative)"${o.name} (${km(o.length)} km)" else o.name) to {showTaSection(o.slug,push=true,fromList=fromList)})}.toTypedArray()),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        taBelow.visibility=View.VISIBLE
        // The Trust's notes: introduction, then folded sections.
        notes{all->if(id!=request)return@notes
            val n=all[slug]?:return@notes
            TeAraroa.Html.text(n.intro).takeIf{it.isNotBlank()}?.let{intro.text=it;intro.visibility=View.VISIBLE}
            val near=d.places.filter{!it.approx&&it.point!=null&&(it.section==slug||it.km>=s.from-2&&it.km<=s.to+2)}.map{it.name to it.point!!}
            val shopsNear=d.shops.filter{x->d.stops.getOrNull(x.stop)?.let{it.km>=s.from-15&&it.km<=s.to+15}==true}.map{it.name to it.point}
            taFolds(listOf("The walk" to n.walk,"Getting there" to n.access,"Transport" to n.transport,"Accommodation" to n.accommodation,"Food and supplies" to n.food,"Track conditions and hazards" to n.conditions),
                mapOf("Accommodation" to near,"Food and supplies" to shopsNear))
        }
        link=TeAraroa.sectionPage(slug);page.text="Te Araroa page";page.visibility=View.VISIBLE;page.setOnClickListener{open(link)}
        if(line.size>=2){saveButton.visibility=View.VISIBLE;saveButton.setOnClickListener{saveRoute("Te Araroa: ${s.name}",DocTracks.simplify(line,2000))}}
        offlineButton.visibility=View.VISIBLE;offlineButton.setOnClickListener{taOffline(d,s)}
        fine.text="From Te Araroa Trust (teararoa.org.nz, CC BY 4.0). Alerts are live.";fine.visibility=View.VISIBLE
        middle(id)
    }
    /** Te Araroa pages open at the middle stop: their lists and details show. */
    private fun middle(id:Long){panel.post{if(id==request&&level==0)settle(1)}}
    /** The Trust's notes as folded rows; in some, "On map" after an entry naming a place. */
    private fun taFolds(list:List<Pair<String,String>>,known:Map<String,List<Pair<String,Coordinate>>>){
        sections.removeAllViews()
        fun fold(t:String)=java.text.Normalizer.normalize(t,java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"),"").lowercase().replace(Regex("[’']"),"").replace(Regex("\\s+")," ").trim()
        for((title,html) in list){
            val blocks=TeAraroa.Html.blocks(html);if(blocks.isEmpty())continue
            val names=known[title].orEmpty().map{(n,p)->fold(n) to p}.filter{it.first.length>=4}.sortedByDescending{it.first.length}
            val content=NativeUi.column(activity).apply{visibility=View.GONE;setPadding(0,0,0,dp(10))}
            val chevron=TextView(activity).apply{text="›";textSize=20f;setTextColor(NativeUi.muted);gravity=Gravity.CENTER;rotation=90f}
            val r=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(10));isClickable=true;background=NativeUi.pressable(activity,null);contentDescription=title}
            r.addView(label(title,14.5f,NativeUi.ink,true),LinearLayout.LayoutParams(0,-2,1f));r.addView(chevron,LinearLayout.LayoutParams(dp(24),dp(24)))
            r.setOnClickListener{
                if(content.childCount==0)for((kind,text) in blocks){
                    val hit=names.firstOrNull{fold(text).contains(it.first)}
                    val shown=if(kind=="li")"•  $text" else text
                    val v=if(hit!=null)links(shown,"  ",("On map" to {onPoint(hit.second);onFit(listOf(Coordinate(hit.second.longitude-.004,hit.second.latitude-.003),Coordinate(hit.second.longitude+.004,hit.second.latitude+.003)))}))else label(shown)
                    if(kind=="h")v.setTypeface(null,Typeface.BOLD)
                    content.addView(v,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(kind=="h")12 else if(kind=="li")4 else 6)})}
                val open=content.visibility!=View.VISIBLE
                content.visibility=if(open)View.VISIBLE else View.GONE
                chevron.animate().rotation(if(open)270f else 90f).setDuration(150).start()
            }
            sections.addView(divider(),LinearLayout.LayoutParams(-1,dp(1)));sections.addView(r,LinearLayout.LayoutParams(-1,-2));sections.addView(content,LinearLayout.LayoutParams(-1,-2))
        }
        if(sections.childCount>0){sections.addView(divider(),LinearLayout.LayoutParams(-1,dp(1)));sections.visibility=View.VISIBLE}
    }
    /** A food stop: what it has, the stops either side, and its shops and cafés. */
    fun showTaStop(i:Int,push:Boolean=false){
        val d=teAraroa?:return;val r=d.stops.getOrNull(i)?:return
        remember({showTaStop(i,false)},false,shortName(r.name),true,push)
        val id=begin(r.name,false);fitNext=false
        setSubtitle("${TeAraroa.KINDS[r.kind]?:TeAraroa.LEVELS[r.level]?:"Food"} · km ${km(kmIn(d,r.km))}${if(r.onTrail)"" else " · ${km(r.off/1000)} km off the trail"}")
        statsRow.visibility=View.GONE;chart.visibility=View.GONE
        onPoint(r.point);fitPoint(r.point,.06)
        if(r.note.isNotBlank()){intro.text=r.note;intro.visibility=View.VISIBLE}
        if(r.bounce)taBelow.addView(label("Walkers send food boxes here.",13.5f,NativeUi.ink,true),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)})
        val (behind,ahead)=d.foodAround(r.km,nobo)
        for((title,x) in listOf("Next: " to ahead,"Previous: " to behind))if(x!=null){val k=d.stops.indexOf(x)
            taBelow.addView(row(title+x.name,TeAraroa.KINDS[x.kind]?:TeAraroa.LEVELS[x.level].orEmpty(),"${km(abs(x.km-r.km))} km · ${TeAraroa.daysText(d.days(min(r.km,x.km),max(r.km,x.km)))}",MapLayers.foodColour(x.kind),if(x.kind=="box")MapLayers.TA_LINE else NativeUi.line){showTaStop(k,push=true)},LinearLayout.LayoutParams(-1,-2))}
        val order=mapOf("supermarket" to 0,"store" to 1,"cafe" to 2)
        val here=d.shops.withIndex().filter{it.value.stop==i}.sortedWith(compareBy<IndexedValue<TeAraroa.Shop>>{order[it.value.kind]?:3}.thenBy{it.value.name})
        if(here.isNotEmpty()){
            taBelow.addView(label("Shops and cafés",14.5f,NativeUi.ink,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(14)})
            for((k,x) in here)taBelow.addView(row(x.name,listOf(TeAraroa.KINDS[x.kind].orEmpty(),x.address).filter{it.isNotBlank()}.joinToString(" · "),colour=MapLayers.foodColour(x.kind),edge=NativeUi.line){showTaShop(k,push=true)},LinearLayout.LayoutParams(-1,-2))
        }
        if(r.shops.isNotEmpty())taFolds(listOf("Shops in Te Araroa’s notes" to r.shops.joinToString(""){"<li>${it.replace("<","&lt;")}</li>"}),emptyMap())
        taBelow.visibility=View.VISIBLE
        fine.text="From walkers’ resupply guides and Te Araroa’s notes. Shops and hours change.";fine.visibility=View.VISIBLE
        middle(id)
    }
    /** A shop or café at a food stop (OpenStreetMap). */
    fun showTaShop(k:Int,push:Boolean=false){
        val d=teAraroa?:return;val x=d.shops.getOrNull(k)?:return
        remember({showTaShop(k,false)},false,shortName(x.name),true,push)
        val id=begin(x.name,false);fitNext=false;middle(id)
        setSubtitle(listOfNotNull(TeAraroa.KINDS[x.kind],d.stops.getOrNull(x.stop)?.name).joinToString(" · "))
        statsRow.visibility=View.GONE;chart.visibility=View.GONE
        onPoint(x.point);fitPoint(x.point,.003)
        if(x.address.isNotBlank())taBelow.addView(label(x.address),LinearLayout.LayoutParams(-1,-2))
        if(x.hours.isNotBlank())taBelow.addView(label(android.text.SpannableString("Hours: "+x.hours.replace(Regex(";\\s*"),"; ")).apply{setSpan(android.text.style.StyleSpan(Typeface.BOLD),0,6,0)}),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        if(x.phone.isNotBlank())taBelow.addView(links(x.phone to {runCatching{activity.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+x.phone.replace(Regex("[^\\d+]"),""))))}}),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        val ways=ArrayList<Pair<String,()->Unit>>()
        if(x.website.startsWith("https://"))ways.add("Website ↗" to {open(x.website)})
        ways.add("Google Maps ↗" to {open("https://www.google.com/maps/search/?api=1&query="+Uri.encode("${x.name} ${x.point.latitude},${x.point.longitude}"))})
        taBelow.addView(links(*ways.flatMapIndexed{i,w->listOf(if(i>0)"     " else "",w)}.toTypedArray()).apply{textSize=14f},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        taBelow.visibility=View.VISIBLE
        fine.text="© OpenStreetMap contributors (ODbL). Hours change: check before you rely on them.";fine.visibility=View.VISIBLE
    }
    /** A place to stay on the trail (a campsite, holiday park or lodge). */
    fun showTaPlace(k:Int,push:Boolean=false){
        val d=teAraroa?:return;val pl=d.places.getOrNull(k)?:return
        remember({showTaPlace(k,false)},false,shortName(pl.name),true,push)
        val id=begin(pl.name,false);fitNext=false;middle(id)
        setSubtitle(listOf(pl.type,if(pl.approx&&pl.town.isNotBlank())"in ${pl.town}" else if(!pl.km.isNaN())"km ${km(kmIn(d,pl.km))}" else "",if(!pl.approx&&pl.off>150)"${km(pl.off/1000)} km off the trail" else "").filter{it.isNotBlank()}.joinToString(" · "))
        statsRow.visibility=View.GONE;chart.visibility=View.GONE
        pl.point?.takeIf{!pl.approx}?.let{onPoint(it);fitPoint(it,.006)}
        val facts=listOf(if(pl.booking)"Booking required" else if(pl.fromNotes)"" else "No booking",if(pl.pass)"Te Araroa Pass" else "").filter{it.isNotEmpty()}.joinToString(" · ")
        if(facts.isNotEmpty())taBelow.addView(label(facts,13.5f,NativeUi.ink,true),LinearLayout.LayoutParams(-1,-2))
        for(t in listOf(pl.facilities,pl.address))if(t.isNotBlank())taBelow.addView(label(t),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        if(pl.phone.isNotBlank())taBelow.addView(links(pl.phone to {runCatching{activity.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+pl.phone.replace(Regex("[^\\d+]"),""))))}}),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        taBelow.visibility=View.VISIBLE
        DocTracks.guidOf(pl.page)?.let{docAlertsFor(id,it,pl.page)}
        if(pl.page.startsWith("https://")){link=pl.page;page.visibility=View.VISIBLE;page.setOnClickListener{open(link)}}
        else if(pl.website.startsWith("https://")){link=pl.website;page.text="Website";page.visibility=View.VISIBLE;page.setOnClickListener{open(link)}}
        if(pl.fromNotes){fine.text="From the accommodation notes for ${d.bySlug[pl.section]?.name?:"this section"} (teararoa.org.nz).";fine.visibility=View.VISIBLE}
    }
    /** "Part of Te Araroa": the sections a walk or track runs along, linked (at the bottom). */
    private fun partOfTeAraroa(id:Long,line:List<Coordinate>){
        val d=teAraroa?:return
        runCatching{liveIo.execute{val secs=runCatching{d.sectionsAlong(line)}.getOrDefault(emptyList())
            main.post{if(id!=request||secs.isEmpty()||currentTa)return@post
                taLink.removeAllViews()
                val box=NativeUi.column(activity).apply{background=NativeUi.background(activity,Color.argb(28,239,59,93),12);setPadding(dp(12),dp(10),dp(12),dp(10))}
                box.addView(label("Part of Te Araroa",14f,NativeUi.ink,true))
                box.addView(label(if(secs.size==1)"Section ${secs[0].n} · ${kmRange(d,secs[0].from,secs[0].to)}" else "${secs.size} sections",12.5f,NativeUi.muted))
                for(s in secs.take(3))box.addView(links((if(s.alternative)s.name else "${s.n}. ${s.name}")+"  ›" to {showTaSection(s.slug,push=true)}).apply{textSize=14f},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
                taLink.addView(box,LinearLayout.LayoutParams(-1,-2));taLink.visibility=View.VISIBLE}}}
    }
    /** "On Te Araroa" for a hut the trail passes (within 400 m). */
    private fun onTeAraroa(id:Long,at:Coordinate){
        val d=teAraroa?:return
        runCatching{liveIo.execute{val hit=runCatching{d.nearest(at,400.0)}.getOrNull()
            main.post{val s=hit?.first?.section?.let{d.bySlug[it]}?:return@post;if(id!=request)return@post
                taLink.removeAllViews()
                val box=NativeUi.column(activity).apply{background=NativeUi.background(activity,Color.argb(28,239,59,93),12);setPadding(dp(12),dp(10),dp(12),dp(10))}
                box.addView(label("On Te Araroa",14f,NativeUi.ink,true))
                box.addView(label("km ${km(kmIn(d,hit.second))}",12.5f,NativeUi.muted))
                box.addView(links("${s.n}. ${s.name}  ›" to {showTaSection(s.slug,push=true)}).apply{textSize=14f},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
                taLink.addView(box,LinearLayout.LayoutParams(-1,-2));taLink.visibility=View.VISIBLE}}}
    }
    // ---- Te Araroa offline: the map along a stretch of trail and what a walker
    // needs with it (TeAraroaOffline, TileLoader.downloadRegion) ----
    /** Starts a region download (OfflineUi.startCorridor); false if one is running. */
    var onOfflineDownload:(String,Map<Int,Int>,MapSettings.Base,((String)->ByteArray?)->Unit,List<List<Coordinate>>,(String)->Unit,()->Unit)->Boolean={_,_,_,_,_,_,_->false}
    @Volatile private var taDownloading=false
    /** [from]: the section open (null: the trail's page). */
    /** The Te Araroa download chooser for the whole trail (the Offline maps page). */
    fun downloadTeAraroa():Boolean{val d=teAraroa?:return false;taOffline(d,null);return true}
    private fun taOffline(d:TeAraroa,from:TeAraroa.Section?){
        if(taDownloading){Toast.makeText(activity,"A Te Araroa download is already running.",Toast.LENGTH_SHORT).show();return}
        val island=from?.island?:""
        val islandName=if(island=="SI")"South Island" else "North Island"
        val scopes=ArrayList<Pair<String,()->List<TeAraroa.Section>>>()
        if(from!=null){
            scopes+="This section · ${from.name.let{if(it.length<=32)it else it.take(31)+"…"}}" to {listOf(from)}
            if(!from.alternative)scopes+="This and the next 4 sections" to {TeAraroaOffline.following(d,from,5,nobo)}
            scopes+="All of the $islandName" to {TeAraroaOffline.island(d,island.ifBlank{"NI"})}
        }else{
            scopes+="North Island" to {TeAraroaOffline.island(d,"NI")}
            scopes+="South Island" to {TeAraroaOffline.island(d,"SI")}
        }
        scopes+="The whole trail" to {d.mains.sortedBy{it.from}}
        AlertDialog.Builder(activity).setTitle("Download Te Araroa for offline").setItems(scopes.map{it.first}.toTypedArray()){_,i->
            val chosen=TeAraroaOffline.withBypasses(d,scopes[i].second())
            val details=listOf("Standard · zoom 14" to 1,"Detailed · zoom 15" to 2,"Maximum · zoom 17" to 3)
            AlertDialog.Builder(activity).setTitle("Map detail along the trail").setItems(details.map{it.first}.toTypedArray()){_,k->
                confirmTaOffline(d,scopes[i].first,chosen,details[k].second)}.setNegativeButton("Cancel",null).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun confirmTaOffline(d:TeAraroa,label:String,sections:List<TeAraroa.Section>,detail:Int){
        val base=loader.mapSettings.state.let{if(it.base==MapSettings.Base.TOPO)it.lastPhoto else it.base}
        val wait=Toast.makeText(activity,"Working out the size…",Toast.LENGTH_SHORT);wait.show()
        runCatching{parse.execute{
            val cells=TeAraroaOffline.cells(d,sections,detail)
            val tiles=PaintedArea.tilesOf(cells)
            val estimate=if(tiles.size<=TileLoader.MAX_PAINTED_TILES)runCatching{loader.estimateTiles(tiles,base,true)}.getOrNull()else null
            main.post{
                if(tiles.size>TileLoader.MAX_PAINTED_TILES){
                    AlertDialog.Builder(activity).setTitle("Too large").setMessage("$label at this detail is more than one download can hold. Choose a lower detail or fewer sections (for example one island at a time).").setPositiveButton("OK",null).show();return@post}
                val mains=sections.count{!it.alternative};val bypasses=sections.size-mains
                val size=estimate?.let{String.format(java.util.Locale.ROOT,"About %,.0f MB · %,d files.",it.mib,it.files)}?:""
                val message="$size\n\n$mains section${if(mains==1)"" else "s"}${if(bypasses>0)" and $bypasses bypass${if(bypasses==1)"" else "es"}" else ""}: the map ${(TeAraroaOffline.DETAIL_HALF_METERS*2/1000).toInt()} km wide along the trail at ${PaintedArea.NAMES[detail].lowercase()} detail and ${(TeAraroaOffline.OVERVIEW_HALF_METERS*2/1000).toInt()} km wide around it, NZ Topo, elevation and the elevation profiles, DOC's huts and hut pages along the way, and the Trust's current alerts (shown, with their date, when offline).\n\nTrail notes, food stops, shops and places to stay are already in the app."
                AlertDialog.Builder(activity).setTitle("Te Araroa · $label").setMessage(message).setNegativeButton("Cancel",null).setPositiveButton("Download"){_,_->
                    startTaOffline(d,label,sections,cells,base)}.show()
            }
        }}
    }
    private fun startTaOffline(d:TeAraroa,label:String,sections:List<TeAraroa.Section>,cells:Map<Int,Int>,base:MapSettings.Base){
        val extra:((String)->ByteArray?)->Unit={get->
            val seen=HashSet<String>()
            fun once(url:String)=if(seen.add(url))get(url)else null
            for(s in sections){
                // The Trust's alerts: saved for offline, reduced to the alerts (shown with
                // their date); not kept in the region (each section's data is ~600 KB).
                val alertsUrl=TeAraroa.trackApi(s.slug)
                runCatching{(java.net.URL(alertsUrl).openConnection() as java.net.HttpURLConnection).run{
                    connectTimeout=8000;readTimeout=20000;setRequestProperty("Accept","application/json")
                    try{if(responseCode==200)inputStream.use{String(it.readBytes(),Charsets.UTF_8)}else null}finally{disconnect()}}}
                    .getOrNull()?.let{TeAraroa.Alert.trustAlertsOnly(it)}?.let{snapshots.save(alertsUrl,it)}
                val line=d.line(s);if(line.size<2)continue
                // As the section's page asks for them: DOC's huts along it and their pages.
                val b=DocTracks.bounds(line)?:continue
                val stops=once(DocTracks.hutsUrl(b))?.let{runCatching{DocTracks.stops(DocTracks.parseHuts(String(it,Charsets.UTF_8)),line)}.getOrNull()}.orEmpty()
                once(DocTracks.hutPagesUrl(b))
                for(st in stops){val p=st.hut.point
                    once(DocTracks.hutAssetsUrl(p.longitude,p.latitude))
                    val pages=once(DocTracks.hutPagesNearUrl(p.longitude,p.latitude))?.let{runCatching{DocTracks.parseHutPages(String(it,Charsets.UTF_8))}.getOrNull()}
                    val page=pages?.let{DocTracks.matchHut(it,st.hut.name,p,{x->x.name},{x->x.point})}?.page
                    if(page!=null&&page.startsWith("https://"))once(page)
                }
            }
        }
        fun status(text:String){offlineStatus.text=text;offlineStatus.visibility=View.VISIBLE}
        val started=runCatching{onOfflineDownload("Te Araroa · $label",cells,base,extra,sections.map{d.line(it)}.filter{it.size>=2},{p->status("Downloading Te Araroa · $label · $p")}){
            taDownloading=false;status("Te Araroa · $label is saved for offline (Offline maps lists it).")
            Toast.makeText(activity,"Te Araroa · $label downloaded for offline.",Toast.LENGTH_LONG).show()}}.getOrElse{false}
        if(!started){Toast.makeText(activity,"Another map download is running. Try again when it finishes.",Toast.LENGTH_LONG).show();return}
        taDownloading=true;status("Downloading Te Araroa · $label · preparing…")
    }

    // ---- Live alerts (the Trust's, DOC's) ----
    private val liveIo=Executors.newFixedThreadPool(2){Thread(it,"ridgeline-live").apply{isDaemon=true}}
    private val liveCache=HashMap<String,Pair<Long,String?>>()
    /** The last copy of each live request, kept on the phone (LiveSnapshots). */
    private val snapshots=LiveSnapshots(activity.filesDir)
    /** When the body last given for a live request was a saved copy: its date (ms). */
    private val liveSaved=java.util.concurrent.ConcurrentHashMap<String,Long>()
    /** A live JSON request (no shared cache: alerts change), kept 10 minutes; null when
     * unreadable. Offline, the copy saved when it last loaded (or downloaded for
     * offline), with its date in [liveSaved]. */
    private fun live(url:String,callback:(String?)->Unit){
        synchronized(liveCache){liveCache[url]}?.takeIf{it.first>System.currentTimeMillis()}?.let{callback(it.second);return}
        runCatching{liveIo.execute{
            var body=runCatching{(java.net.URL(url).openConnection() as java.net.HttpURLConnection).run{
                connectTimeout=8000;readTimeout=15000;setRequestProperty("Accept","application/json")
                try{when(responseCode){200->inputStream.use{String(it.readBytes(),Charsets.UTF_8)};204->"[]";else->null}}finally{disconnect()}}}.getOrNull()
            // Kept small: the Trust's section data reduced to its alerts.
            if(body!=null){snapshots.save(url,if(url.startsWith(TeAraroa.TRUST_API))TeAraroa.Alert.trustAlertsOnly(body)?:body else body);liveSaved.remove(url)}
            else snapshots.load(url)?.let{(saved,at)->body=saved;liveSaved[url]=at}
            synchronized(liveCache){liveCache[url]=System.currentTimeMillis()+(if(body!=null&&!liveSaved.containsKey(url))600_000L else 60_000L) to body}
            main.post{callback(body)}}}.onFailure{callback(null)}
    }
    /** DOC's current alerts for a walk, track or hut, below the chart and introduction. */
    private fun docAlertsFor(id:Long,guid:String,page:String){
        val url="https://www.doc.govt.nz/api/alerts/guid/${guid.lowercase()}"
        live(url){body->
            if(id!=request)return@live
            alerts(docAlerts,body?.let{runCatching{TeAraroa.Alert.parseDoc(it)}.getOrNull()},"DOC alert","Couldn’t load DOC’s current alerts.",page.takeIf{it.startsWith("https://")}?:"https://www.doc.govt.nz/parks-and-recreation/know-before-you-go/alerts/",null,liveSaved[url])}
    }
    /** An alerts box: closures first, each folded (one alone opened); nothing when none. */
    /** [saved]: the alerts are the copy saved then (offline), not live. */
    private fun alerts(box:LinearLayout,list:List<TeAraroa.Alert>?,noun:String,failed:String,page:String,d:TeAraroa?,saved:Long?=null){
        box.removeAllViews()
        if(list!=null&&list.isEmpty()&&saved==null){box.visibility=View.GONE;return}
        box.background=NativeUi.background(activity,if(NativeUi.dark)Color.rgb(70,44,30)else Color.rgb(253,236,224),12);box.setPadding(dp(12),dp(10),dp(12),dp(10))
        val ink=if(NativeUi.dark)Color.rgb(255,214,190)else Color.rgb(110,48,16)
        if(list==null){box.addView(links("⚠  $failed  ","Check them ↗" to {open(page)}).apply{setTextColor(ink)});box.visibility=View.VISIBLE;return}
        if(saved!=null){val date=java.text.SimpleDateFormat("d MMM, h:mm a",java.util.Locale.getDefault()).format(java.util.Date(saved))
            box.addView(label("Offline · ${noun.lowercase()}s as saved $date",12f,ink).apply{alpha=.85f})
            if(list.isEmpty()){box.addView(label("None then.",13f,ink));box.visibility=View.VISIBLE;return}}
        box.addView(label("⚠  "+if(list.size==1)noun else "${list.size} ${noun}s",14f,ink,true))
        for(a in list){
            val where=if(a.from!=null&&d!=null)"km ${km(a.from)}${if(a.to!=null&&a.to!=a.from)"–${km(a.to)}" else ""} SOBO" else ""
            val text=label(a.text,13f,ink).apply{visibility=if(list.size==1)View.VISIBLE else View.GONE}
            val head=NativeUi.column(activity).apply{isClickable=true;setPadding(0,dp(8),0,dp(4));setOnClickListener{text.visibility=if(text.visibility==View.VISIBLE)View.GONE else View.VISIBLE}}
            head.addView(label(a.title,13.5f,ink,true))
            listOf(a.status,a.date,where).filter{it.isNotBlank()}.joinToString(" · ").takeIf{it.isNotEmpty()}?.let{head.addView(label(it,12f,ink).apply{alpha=.8f})}
            box.addView(head);box.addView(text,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
        }
        box.visibility=View.VISIBLE
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
    fun close(){request++;parse.shutdownNow();liveIo.shutdownNow()}
}
