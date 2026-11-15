package nz.ridgeline.nativeapp

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.*

data class Coordinate(val longitude:Double,val latitude:Double) {
    init {require(longitude.isFinite()&&latitude.isFinite()&&longitude in -180.0..180.0&&latitude in -90.0..90.0)}
    fun inNewZealand()=longitude in 165.0..180.0&&latitude in -49.0..-33.0
    // Match Number(value.toFixed(6)), including binary ties and negative latitude.
    fun rounded()=Coordinate(BigDecimal(longitude).setScale(6,RoundingMode.HALF_UP).toDouble(),BigDecimal(latitude).setScale(6,RoundingMode.HALF_UP).toDouble())
}
object NativeGeo {
    data class ProfilePoint(val point:Coordinate,val distance:Double,val height:Double?=null)
    data class Totals(val ascent:Double,val descent:Double,val high:Double?,val complete:Boolean)
    data class Section(val start:Double,val end:Double,val distance:Double,val ascent:Double,val descent:Double,val complete:Boolean)
    fun profileAt(samples:List<ProfilePoint>,distance:Double):ProfilePoint?{
        if(samples.isEmpty()||!distance.isFinite())return null
        val d=distance.coerceIn(samples.first().distance,samples.last().distance);var lo=0;var hi=samples.lastIndex
        while(lo<hi){val mid=(lo+hi) ushr 1;if(samples[mid].distance<d)lo=mid+1 else hi=mid}
        val b=samples[lo];if(lo==0||b.distance==d)return b
        val a=samples[lo-1];val t=(d-a.distance)/(b.distance-a.distance)
        return ProfilePoint(Coordinate(a.point.longitude+(b.point.longitude-a.point.longitude)*t,a.point.latitude+(b.point.latitude-a.point.latitude)*t),d,if(a.height!=null&&b.height!=null)a.height+(b.height-a.height)*t else null)
    }
    fun grade(samples:List<ProfilePoint>,distance:Double):Double?{
        if(samples.size<2||!distance.isFinite())return null
        val lo=max(samples.first().distance,distance-10);val hi=min(samples.last().distance,distance+10);if(hi<=lo)return null
        if(samples.any{it.distance>lo&&it.distance<hi&&it.height==null})return null
        val a=profileAt(samples,lo)?.height?:return null;val b=profileAt(samples,hi)?.height?:return null
        return (b-a)/(hi-lo)*100
    }
    /** Same endpoint interpolation and missing-gap accounting as the Capacitor chart. */
    fun section(samples:List<ProfilePoint>,a:Double,b:Double):Section?{
        if(samples.isEmpty()||!a.isFinite()||!b.isFinite())return null
        val start=max(samples.first().distance,min(a,b));val end=min(samples.last().distance,max(a,b));if(end<=start)return null
        val values=listOf(profileAt(samples,start)!!)+samples.filter{it.distance>start&&it.distance<end}+profileAt(samples,end)!!
        var ascent=0.0;var descent=0.0;var complete=true
        for(i in 1 until values.size){val l=values[i-1].height;val r=values[i].height;if(l==null||r==null){complete=false;continue};val delta=r-l;if(delta>0)ascent+=delta else descent-=delta}
        return Section(start,end,end-start,ascent,descent,complete)
    }
    fun distance(a:Coordinate,b:Coordinate):Double {
        val p=Math.toRadians(b.latitude-a.latitude);val q=Math.toRadians(b.longitude-a.longitude)
        val h=sin(p/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(q/2).pow(2)
        return 6371008.8*2*atan2(sqrt(h),sqrt(max(0.0,1-h)))
    }
    fun length(points:List<Coordinate>):Double {var sum=0.0;for(i in 1 until points.size)sum+=distance(points[i-1],points[i]);return sum}
    class Locator(points:List<Coordinate>) {
        private val route=points.toList()
        private val distances=DoubleArray(points.size).also{d->for(i in 1 until route.size)d[i]=d[i-1]+distance(route[i-1],route[i])}
        fun at(distance:Double):Coordinate? {
            if(route.isEmpty()||!distance.isFinite())return null
            if(route.size==1)return route[0]
            val d=distance.coerceIn(0.0,distances.last());var lo=1;var hi=route.lastIndex
            while(lo<hi){val mid=(lo+hi) ushr 1;if(distances[mid]<d)lo=mid+1 else hi=mid}
            val span=distances[lo]-distances[lo-1];val t=(d-distances[lo-1])/(if(span==0.0)1.0 else span)
            val a=route[lo-1];val b=route[lo]
            return Coordinate(a.longitude+t*(b.longitude-a.longitude),a.latitude+t*(b.latitude-a.latitude))
        }
    }
    fun samples(points:List<Coordinate>,limit:Int=240,spacing:Double=100.0):List<ProfilePoint> {
        require(limit>=2&&spacing>0)
        if(points.size<2)return points.map{ProfilePoint(it,0.0)}
        val ds=DoubleArray(points.size);for(i in 1 until points.size)ds[i]=ds[i-1]+distance(points[i-1],points[i])
        val total=ds.last();if(total==0.0)return listOf(ProfilePoint(points[0],0.0))
        val count=max(2,min(limit,ceil(total/spacing).toInt()+1));val result=ArrayList<ProfilePoint>(count+points.size)
        var segment=1
        for(i in 0 until count){val d=i*total/(count-1);while(segment<points.lastIndex&&ds[segment]<d)segment++
            val span=ds[segment]-ds[segment-1];val t=(d-ds[segment-1])/(if(span==0.0)1.0 else span)
            val a=points[segment-1];val b=points[segment]
            result.add(ProfilePoint(Coordinate(a.longitude+t*(b.longitude-a.longitude),a.latitude+t*(b.latitude-a.latitude)),d))
        }
        points.forEachIndexed{i,p->result.add(ProfilePoint(p,ds[i]))};result.sortBy{it.distance}
        return result.filterIndexed{i,p->i==0||p.distance-result[i-1].distance>.01}
    }
    fun totals(values:List<Double?>):Totals {
        require(values.all{it==null||it.isFinite()})
        val smooth=values.mapIndexed{i,h->if(h==null)null else values.subList(max(0,i-1),min(values.size,i+2)).filterNotNull().average()}
        var anchor=smooth.firstOrNull();var gain=0.0;var loss=0.0
        for(h in smooth.drop(1)){if(h==null){anchor=null;continue};val previous=anchor;if(previous==null){anchor=h;continue}
            val delta=h-previous;if(abs(delta)>=5){if(delta>0)gain+=delta else loss-=delta;anchor=h}}
        return Totals(gain,loss,values.filterNotNull().maxOrNull(),values.all{it!=null})
    }
    fun combine(routes:List<Pair<List<Coordinate>,Boolean>>):List<Coordinate> {
        require(routes.size>=2){"Select at least two routes to combine."}
        val result=ArrayList<Coordinate>()
        for((points,reverse)in routes)for(p in if(reverse)points.asReversed()else points){if(result.lastOrNull()!=p)result.add(p);require(result.size<=2000){"Combined routes exceed 2,000 points. Choose fewer routes."}}
        return result
    }
}

/** Rendering-independent route mutations. Preview never creates history or saves;
 * one completed drag creates one undo snapshot, cancellation restores the point. */
class RouteEditor {
    data class Snapshot(val points:List<Coordinate>,val selected:Int,val shape:List<Boolean> = emptyList())
    enum class Result {CHANGED,TOO_CLOSE,OUTSIDE_NZ,LIMIT,NO_CHANGE}
    var points:List<Coordinate> = emptyList();private set
    /** Per point: true for a point along a followed track (drawn as line, no
     * waypoint dot); empty while the route has none. */
    var shape:List<Boolean> = emptyList();private set
    var selected=-1;private set
    private val history=java.util.ArrayDeque<Snapshot>()
    private var drag:Snapshot?=null
    val canUndo get()=history.isNotEmpty()
    val isDragging get()=drag!=null
    private fun snapshot()=Snapshot(points.toList(),selected,shape.toList())
    private fun remember(value:Snapshot=snapshot()){history.addLast(value);while(history.size>50)history.removeFirst()}
    private fun flags()=if(shape.isEmpty())MutableList(points.size){false}else shape.toMutableList()
    private fun setFlags(value:List<Boolean>){shape=if(value.any{it})value.toList()else emptyList()}
    fun reset(value:List<Coordinate>,selection:Int=-1,shape:List<Boolean> = emptyList()){require(value.size<=2000&&value.all{it.inNewZealand()}&&(shape.isEmpty()||shape.size==value.size));points=value.toList();setFlags(shape);selected=selection.takeIf{it in value.indices}?:-1;history.clear();drag=null}
    fun clearSelection(){if(drag==null)selected=-1}
    fun select(index:Int):Boolean {if(drag!=null||index !in points.indices||shape.getOrElse(index){false})return false;selected=index;return true}
    fun append(value:Coordinate):Result {
        cancelDrag();val p=value.rounded();if(!p.inNewZealand())return Result.OUTSIDE_NZ
        if(points.size>=2000)return Result.LIMIT
        if(points.lastOrNull()?.let{NativeGeo.distance(it,p)<5}==true)return Result.TOO_CLOSE
        remember();val f=flags();points=points+p;f.add(false);setFlags(f);selected=points.lastIndex;return Result.CHANGED
    }
    /** A followed track from the last point: [path]'s points (along the track)
     * then its last (a waypoint), as one undo step. */
    fun appendPath(path:List<Coordinate>):Result {
        cancelDrag();val rounded=path.map{it.rounded()};if(rounded.isEmpty())return Result.NO_CHANGE
        if(!rounded.all{it.inNewZealand()})return Result.OUTSIDE_NZ
        val added=ArrayList<Coordinate>();var last=points.lastOrNull()
        for((i,p) in rounded.withIndex()){val end=i==rounded.lastIndex
            if(last!=null&&NativeGeo.distance(last,p)<(if(end)5.0 else 1.0)){if(end&&added.isNotEmpty())added[added.lastIndex]=p;continue}
            added.add(p);last=p}
        if(added.isEmpty())return Result.TOO_CLOSE
        if(points.size+added.size>2000)return Result.LIMIT
        remember();val f=flags();points=points+added;for(i in added.indices)f.add(i<added.lastIndex);setFlags(f);selected=points.lastIndex;return Result.CHANGED
    }
    fun insertAfter(index:Int,p:Coordinate):Result {
        cancelDrag();if(index !in 0 until points.lastIndex)return Result.NO_CHANGE
        if(!p.inNewZealand())return Result.OUTSIDE_NZ;if(points.size>=2000)return Result.LIMIT
        remember();val f=flags();points=points.toMutableList().apply{add(index+1,p)};f.add(index+1,false);setFlags(f);selected=index+1;return Result.CHANGED
    }
    fun deleteSelected():Boolean {cancelDrag();if(selected !in points.indices)return false;remember();val f=flags();points=points.toMutableList().apply{removeAt(selected)};f.removeAt(selected);setFlags(f);selected=-1;return true}
    fun beginDrag(index:Int):Boolean {cancelDrag();if(!select(index))return false;drag=snapshot();return true}
    fun previewDrag(value:Coordinate):Boolean {if(drag==null)return false;val p=value.rounded();if(!p.inNewZealand()||points[selected]==p)return false;points=points.toMutableList().apply{set(selected,p)};return true}
    /** While dragging: the route from the previous waypoint to the next (from the
     * drag's start) replaced by [before] (ending at the dragged point) and [after]
     * (up to the next waypoint): the pieces beside a point dragged along a track,
     * or straight ([before] the point alone, [after] empty) when it leaves it. */
    fun previewReroute(before:List<Coordinate>,after:List<Coordinate>):Boolean {
        val old=drag?:return false
        val b=before.map{it.rounded()};val a=after.map{it.rounded()}
        if(b.isEmpty()||!(b+a).all{it.inNewZealand()})return false
        val flags=if(old.shape.isEmpty())List(old.points.size){false}else old.shape
        val prevA=(old.selected-1 downTo 0).firstOrNull{!flags[it]}?:-1
        val nextA=(old.selected+1 until old.points.size).firstOrNull{!flags[it]}?:-1
        val head=if(prevA>=0)old.points.subList(0,prevA+1)else emptyList()
        val tail=if(nextA>=0)old.points.subList(nextA,old.points.size)else emptyList()
        val next=head+b+a+tail;if(next.size>2000)return false
        val f=(if(prevA>=0)flags.subList(0,prevA+1)else emptyList())+List(b.size){it<b.size-1}+List(a.size){true}+(if(nextA>=0)flags.subList(nextA,flags.size)else emptyList())
        if(next==points&&f==(if(shape.isEmpty())List(points.size){false}else shape))return false
        points=next;setFlags(f);selected=head.size+b.size-1;return true
    }
    /** The waypoints either side of the one being dragged (from the drag's start), or null at an end. */
    fun dragNeighbours():Pair<Coordinate?,Coordinate?>?{
        val old=drag?:return null
        val flags=if(old.shape.isEmpty())List(old.points.size){false}else old.shape
        val prevA=(old.selected-1 downTo 0).firstOrNull{!flags[it]};val nextA=(old.selected+1 until old.points.size).firstOrNull{!flags[it]}
        return prevA?.let{old.points[it]} to nextA?.let{old.points[it]}
    }
    fun commitDrag():Boolean {val old=drag?:return false;drag=null;if(old.points==points)return false;remember(old);return true}
    fun cancelDrag():Boolean {val old=drag?:return false;drag=null;val changed=points!=old.points;points=old.points;selected=old.selected;shape=old.shape;return changed}
    fun undo():Boolean {cancelDrag();val old=history.pollLast()?:return false;points=old.points;selected=old.selected;shape=old.shape;return true}
}
