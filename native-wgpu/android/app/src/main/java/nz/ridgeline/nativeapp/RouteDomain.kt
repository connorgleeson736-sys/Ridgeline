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
    data class Snapshot(val points:List<Coordinate>,val selected:Int)
    enum class Result {CHANGED,TOO_CLOSE,OUTSIDE_NZ,LIMIT,NO_CHANGE}
    var points:List<Coordinate> = emptyList();private set
    var selected=-1;private set
    private val history=java.util.ArrayDeque<Snapshot>()
    private var drag:Snapshot?=null
    val canUndo get()=history.isNotEmpty()
    val isDragging get()=drag!=null
    private fun snapshot()=Snapshot(points.toList(),selected)
    private fun remember(value:Snapshot=snapshot()){history.addLast(value);while(history.size>50)history.removeFirst()}
    fun reset(value:List<Coordinate>,selection:Int=-1){require(value.size<=2000&&value.all{it.inNewZealand()});points=value.toList();selected=selection.takeIf{it in value.indices}?:-1;history.clear();drag=null}
    fun select(index:Int):Boolean {if(drag!=null||index !in points.indices)return false;selected=index;return true}
    fun append(value:Coordinate):Result {
        cancelDrag();val p=value.rounded();if(!p.inNewZealand())return Result.OUTSIDE_NZ
        if(points.size>=2000)return Result.LIMIT
        if(points.lastOrNull()?.let{NativeGeo.distance(it,p)<5}==true)return Result.TOO_CLOSE
        remember();points=points+p;selected=points.lastIndex;return Result.CHANGED
    }
    fun insertAfter(index:Int,p:Coordinate):Result {
        cancelDrag();if(index !in 0 until points.lastIndex)return Result.NO_CHANGE
        if(!p.inNewZealand())return Result.OUTSIDE_NZ;if(points.size>=2000)return Result.LIMIT
        remember();points=points.toMutableList().apply{add(index+1,p)};selected=index+1;return Result.CHANGED
    }
    fun deleteSelected():Boolean {cancelDrag();if(selected !in points.indices)return false;remember();points=points.toMutableList().apply{removeAt(selected)};selected=-1;return true}
    fun beginDrag(index:Int):Boolean {cancelDrag();if(!select(index))return false;drag=snapshot();return true}
    fun previewDrag(value:Coordinate):Boolean {if(drag==null)return false;val p=value.rounded();if(!p.inNewZealand()||points[selected]==p)return false;points=points.toMutableList().apply{set(selected,p)};return true}
    fun commitDrag():Boolean {val old=drag?:return false;drag=null;if(old.points==points)return false;remember(old);return true}
    fun cancelDrag():Boolean {val old=drag?:return false;drag=null;val changed=points!=old.points;points=old.points;selected=old.selected;return changed}
    fun undo():Boolean {cancelDrag();val old=history.pollLast()?:return false;points=old.points;selected=old.selected;return true}
}
