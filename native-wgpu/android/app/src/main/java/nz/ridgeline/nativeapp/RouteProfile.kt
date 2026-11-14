package nz.ridgeline.nativeapp

import kotlin.math.*

/** Route elevation profiles, following the Capacitor profile engine
 * (terrain-core.js): a quick z13 preview, then a detail pass sampled every 5 m
 * from z17 LINZ elevation (lowered only to stay within 64 tiles), plus 2 m
 * samples at z17 along the part of the route that is in view when the map is
 * at zoom 15 or closer. Heights are pixel-centre bilinear from original DEM
 * pixels, never from resident render meshes. */
object RouteProfile {
    data class Bounds(val west:Double,val south:Double,val east:Double,val north:Double){
        fun contains(p:Coordinate)=p.longitude in west..east&&p.latitude in south..north
        fun stamp()=listOf(west,south,east,north).joinToString(","){(it*1000).roundToInt().toString()}
    }
    data class Stage(val samples:List<NativeGeo.ProfilePoint>,val zooms:IntArray,val tiles:List<GpuUploadQueue.Id>,val zoom:Int,val focusZoom:Int)
    data class Result(val values:List<NativeGeo.ProfilePoint>,val zooms:IntArray)

    const val PREVIEW_LIMIT=320;const val PREVIEW_SPACING=40.0;const val PREVIEW_ZOOM=13;const val PREVIEW_BUDGET=16
    const val DETAIL_LIMIT=1600;const val DETAIL_SPACING=5.0;const val DETAIL_ZOOM=17;const val DETAIL_BUDGET=64
    const val FOCUS_LIMIT=400;const val FOCUS_SPACING=2.0;const val FOCUS_MIN_ZOOM=15.0

    private fun mercator(p:Coordinate)=(p.longitude+180)/360 to (1-asinh(tan(Math.toRadians(p.latitude)))/PI)/2
    fun tile(p:Coordinate,z:Int):GpuUploadQueue.Id{
        val (mx,my)=mercator(p);val n=1 shl z
        return GpuUploadQueue.Id(z,floor(mx*n).toInt().coerceIn(0,n-1),floor(my*n).toInt().coerceIn(0,n-1))
    }
    /** Tiles holding the four pixel centres a pixel-centre bilinear sample reads. */
    fun footprint(p:Coordinate,z:Int):Set<GpuUploadQueue.Id>{
        val (mx,my)=mercator(p);val size=(1 shl z)*256
        val px=mx*size-.5;val py=my*size-.5;val ix=floor(px).toInt();val iy=floor(py).toInt()
        val result=LinkedHashSet<GpuUploadQueue.Id>(4)
        for(y in iy..iy+1)for(x in ix..ix+1){val gx=Math.floorMod(x,size);val gy=y.coerceIn(0,size-1);result.add(GpuUploadQueue.Id(z,gx/256,gy/256))}
        return result
    }

    /** Capacitor geo.js focusSamples: evenly spaced samples along the clipped
     * parts of the route that fall inside [bounds]. */
    fun focusSamples(points:List<Coordinate>,bounds:Bounds,limit:Int=FOCUS_LIMIT,spacing:Double=FOCUS_SPACING):List<NativeGeo.ProfilePoint>{
        data class Segment(val a:Coordinate,val b:Coordinate,val lo:Double,val hi:Double,val length:Double,val distance:Double,val visible:Double)
        val segments=ArrayList<Segment>();var d=0.0;var total=0.0
        for(i in 1 until points.size){
            val a=points[i-1];val b=points[i];val length=NativeGeo.distance(a,b);val dx=b.longitude-a.longitude;val dy=b.latitude-a.latitude
            var lo=0.0;var hi=1.0;var valid=true
            for((p,q) in listOf(-dx to a.longitude-bounds.west,dx to bounds.east-a.longitude,-dy to a.latitude-bounds.south,dy to bounds.north-a.latitude)){
                if(p==0.0){if(q<0)valid=false;continue}
                val t=q/p;if(p<0)lo=max(lo,t)else hi=min(hi,t)
            }
            if(valid&&hi>=lo&&length>0){val visible=(hi-lo)*length;segments.add(Segment(a,b,lo,hi,length,d,visible));total+=visible}
            d+=length
        }
        if(total==0.0)return emptyList()
        val step=max(spacing,total/max(1,limit-segments.size*2));val result=ArrayList<NativeGeo.ProfilePoint>()
        for(s in segments){val n=max(1,ceil(s.visible/step).toInt())
            for(i in 0..n){val t=s.lo+(s.hi-s.lo)*i/n
                result.add(NativeGeo.ProfilePoint(Coordinate(s.a.longitude+(s.b.longitude-s.a.longitude)*t,s.a.latitude+(s.b.latitude-s.a.latitude)*t),s.distance+s.length*t))}
        }
        return result
    }

    /** Capacitor terrain-core.js stage(): choose the finest zoom whose tile set
     * fits the budget, with in-view samples at a finer focus zoom when it fits. */
    fun plan(points:List<Coordinate>,detail:Boolean,focus:Bounds?):Stage{
        val samples=ArrayList(if(detail)NativeGeo.samples(points,DETAIL_LIMIT,DETAIL_SPACING)else NativeGeo.samples(points,PREVIEW_LIMIT,PREVIEW_SPACING))
        if(detail&&focus!=null)samples.addAll(focusSamples(points,focus))
        samples.sortBy{it.distance}
        val unique=samples.filterIndexed{i,s->i==0||s.distance-samples[i-1].distance>.01}
        val budget=if(detail)DETAIL_BUDGET else PREVIEW_BUDGET
        var z=if(detail)DETAIL_ZOOM else PREVIEW_ZOOM
        while(z>11&&unique.mapTo(HashSet()){tile(it.point,z)}.size>budget)z--
        val inside=BooleanArray(unique.size){detail&&focus!=null&&focus.contains(unique[it].point)}
        var focusZoom=DETAIL_ZOOM
        fun zooms()=IntArray(unique.size){if(inside[it])max(z,focusZoom)else z}
        fun keys(levels:IntArray)=unique.indices.mapTo(HashSet()){tile(unique[it].point,levels[it])}
        while(focusZoom>z&&keys(zooms()).size>budget)focusZoom--
        var levels=zooms()
        while(z>0&&keys(levels).size>budget){z--;focusZoom=z;levels=zooms()}
        val tiles=LinkedHashSet<GpuUploadQueue.Id>()
        for(i in unique.indices)tiles.add(tile(unique[i].point,levels[i]))
        // Pixel-centre neighbours across a tile edge; rare, so outside the budget.
        for(i in unique.indices)tiles.addAll(footprint(unique[i].point,levels[i]))
        return Stage(unique,levels,tiles.toList(),z,focusZoom)
    }

    /** Pixel-centre bilinear height at [z]. A missing neighbour tile falls back
     * to the clamped edge pixel of the containing tile, as Capacitor does. */
    fun height(p:Coordinate,z:Int,rasters:(GpuUploadQueue.Id)->DemSampling.Raster?):Double?{
        val (mx,my)=mercator(p)
        DemSampling.point(z,mx,my,rasters)?.toDouble()?.takeIf{it.isFinite()}?.let{return it}
        val record=rasters(tile(p,z))?:return null
        val size=(1 shl z)*256.0
        return DemSampling.sample(record,mx*size-.5,my*size-.5,z).toDouble().takeIf{it.isFinite()}
    }

    /** Samples a planned stage. [fetch] returns null when a tile is unavailable;
     * those samples fall back to up to four coarser LINZ ancestors. */
    fun sample(stage:Stage,fetched:Map<GpuUploadQueue.Id,DemSampling.Raster?>,fetch:(GpuUploadQueue.Id)->DemSampling.Raster?,cancelled:()->Boolean={false}):Result{
        val extra=HashMap<GpuUploadQueue.Id,DemSampling.Raster?>()
        fun raster(id:GpuUploadQueue.Id):DemSampling.Raster?=if(fetched.containsKey(id))fetched[id]else extra.getOrPut(id){if(cancelled())null else fetch(id)}
        val zooms=IntArray(stage.samples.size){-1}
        val values=stage.samples.mapIndexed{i,s->
            var h:Double?=null;var z=stage.zooms[i];val floor=max(0,z-4)
            while(h==null&&z>=floor&&!cancelled()){h=height(s.point,z,::raster);if(h==null)z--}
            if(h!=null)zooms[i]=z
            s.copy(height=h)
        }
        return Result(values,zooms)
    }

    /** Visible ground bounds for in-view focus sampling (Capacitor uses
     * map.getBounds() only at zoom 15 or closer). Rows above the horizon are skipped. */
    fun focusBounds(pose:CameraPose,width:Int,height:Int):Bounds?{
        if(pose.zoom<FOCUS_MIN_ZOOM||width<=0||height<=0)return null
        val anchors=ArrayList<CameraMath.Anchor>()
        for(row in 0..8){val y=height*row/8.0
            val line=listOf(0.0,width*.5,width.toDouble()).map{x->CameraMath.anchor(pose,width,height,1.0,x,y)}
            if(line.all{it!=null}){anchors.addAll(line.filterNotNull())}
        }
        if(anchors.size<6)return null
        val west=anchors.minOf{it.mx}*360-180;val east=anchors.maxOf{it.mx}*360-180
        val north=CameraMath.latitude(anchors.minOf{it.my});val south=CameraMath.latitude(anchors.maxOf{it.my})
        return Bounds(west,south,east,north)
    }

    /** Capacitor geo.js chartSamples: at most four samples (first, last, low,
     * high) per chart column; missing gaps are kept. Analysis uses all samples. */
    fun chartSamples(profile:List<NativeGeo.ProfilePoint>,width:Int=650):List<NativeGeo.ProfilePoint>{
        if(profile.size<=width*2)return profile
        val total=profile.last().distance.takeIf{it!=0.0}?:1.0;val result=ArrayList<NativeGeo.ProfilePoint>()
        var bucket=ArrayList<NativeGeo.ProfilePoint>();var column=-1
        fun flush(){if(bucket.isEmpty())return
            val selected=listOf(bucket.first(),bucket.last(),bucket.minBy{it.height!!},bucket.maxBy{it.height!!}).sortedBy{it.distance}
            selected.forEachIndexed{i,p->if(i==0||p!==selected[i-1])result.add(p)};bucket=ArrayList()}
        for(p in profile){
            if(p.height==null){flush();result.add(p);column=-1;continue}
            val next=floor(p.distance/total*width).toInt();if(next!=column){flush();column=next}
            bucket.add(p)
        }
        flush();return result
    }
}
