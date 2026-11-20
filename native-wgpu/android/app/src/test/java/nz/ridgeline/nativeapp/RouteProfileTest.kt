package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Route profiles follow Capacitor's terrain-core.js engine. */
class RouteProfileTest {
    private val start=Coordinate(170.1400,-43.5900)
    private fun east(metres:Double)=Coordinate(start.longitude+metres/(111_320*cos(Math.toRadians(start.latitude))),start.latitude)
    private fun mercator(p:Coordinate)=(p.longitude+180)/360 to (1-asinh(tan(Math.toRadians(p.latitude)))/PI)/2
    /** A sloping plane in global z17 pixel space: bilinear interpolation of
     * pixel centres reproduces it exactly, so any sampling error shows. */
    private fun plane(z:Int,gx:Double,gy:Double):Double{val (mx,my)=mercator(start);val size=(1 shl z)*256.0
        return 1000.0+(gx-floor(mx*size))*0.05-(gy-floor(my*size))*0.03}
    private fun raster(id:GpuUploadQueue.Id)=DemSampling.Raster(id,FloatArray(256*256){i->plane(id.z,id.x*256.0+i%256,id.y*256.0+i/256).toFloat()})
    private fun exact(p:Coordinate,z:Int):Double{val (mx,my)=mercator(p);val size=(1 shl z)*256.0;return plane(z,mx*size-.5,my*size-.5)}

    @Test fun aShortRouteIsSampledEveryFiveMetresFromZ17(){
        val route=listOf(start,east(2000.0))
        val plan=RouteProfile.plan(route,true,null)
        assertEquals(17,plan.zoom)
        assertTrue(plan.zooms.all{it==17})
        assertEquals(401,plan.samples.size) // 2 km / 5 m + 1
        val previewPlan=RouteProfile.plan(route,false,null)
        assertEquals(13,previewPlan.zoom);assertEquals(51,previewPlan.samples.size) // 40 m
    }
    @Test fun aLongRouteLowersZoomToStayWithinTheTileBudget(){
        val route=listOf(start,east(60_000.0),Coordinate(east(60_000.0).longitude,start.latitude+.3))
        val plan=RouteProfile.plan(route,true,null)
        assertTrue("zoom ${plan.zoom}",plan.zoom in 11..16)
        assertTrue(plan.samples.size<=RouteProfile.DETAIL_LIMIT+route.size)
        assertTrue(plan.samples.mapTo(HashSet()){s->RouteProfile.tile(s.point,plan.zoom)}.size<=RouteProfile.DETAIL_BUDGET)
    }
    @Test fun inViewSamplesUseTwoMetreSpacingAtZ17(){
        val route=listOf(start,east(60_000.0))
        val view=RouteProfile.Bounds(east(20_000.0).longitude,start.latitude-.01,east(20_400.0).longitude,start.latitude+.01)
        val plan=RouteProfile.plan(route,true,view)
        assertTrue(plan.zoom<17)
        assertEquals(17,plan.focusZoom)
        val inside=plan.samples.indices.filter{view.contains(plan.samples[it].point)}
        assertTrue(inside.size>=150)
        assertTrue(inside.all{plan.zooms[it]==17})
        val gaps=inside.zipWithNext{a,b->plan.samples[b].distance-plan.samples[a].distance}
        assertTrue("max ${gaps.max()}",gaps.max()<=5.0+1e-6)
    }
    @Test fun heightsArePixelCentreBilinearFromTheFetchedTiles(){
        val route=listOf(start,east(1500.0))
        val plan=RouteProfile.plan(route,true,null)
        val fetched=plan.tiles.associateWith{raster(it)}
        val result=RouteProfile.sample(plan,fetched,{fail("no extra fetch");null})
        for(v in result.values)assertEquals(exact(v.point,17),v.height!!,1e-2)
        assertTrue(result.zooms.all{it==17})
    }
    @Test fun aSampleOnATileEdgeReadsTheNeighbouringTile(){
        val (mx,my)=mercator(start);val n=1 shl 17
        // Put a sample a quarter pixel from the west edge of its tile.
        val edge=Coordinate((floor(mx*n)/n+.25/256/n)*360-180,start.latitude)
        val plan=RouteProfile.plan(listOf(edge,east(10.0)),true,null)
        val west=RouteProfile.tile(edge,17).let{GpuUploadQueue.Id(17,it.x-1,it.y)}
        assertTrue(west in plan.tiles)
        val result=RouteProfile.sample(plan,plan.tiles.associateWith{raster(it)},{null})
        assertEquals(exact(edge,17),result.values.first().height!!,1e-2)
    }
    @Test fun anUnavailableTileFallsBackToACoarserAncestor(){
        val plan=RouteProfile.plan(listOf(start,east(300.0)),true,null)
        val fetched=plan.tiles.associateWith{null as DemSampling.Raster?}
        val requested=ArrayList<GpuUploadQueue.Id>()
        val result=RouteProfile.sample(plan,fetched,{id->requested.add(id);if(id.z==16)raster(id)else null})
        assertTrue(result.values.all{it.height!=null})
        assertTrue(result.zooms.all{it==16})
        assertTrue(requested.all{it.z==16})
        for(v in result.values)assertEquals(exact(v.point,16),v.height!!,1e-2)
    }
    @Test fun focusSamplesMatchCapacitorClipping(){
        val route=listOf(start,east(1000.0))
        val view=RouteProfile.Bounds(east(250.0).longitude,start.latitude-1,east(750.0).longitude,start.latitude+1)
        val samples=RouteProfile.focusSamples(route,view)
        assertEquals(250.0,samples.first().distance,2.0);assertEquals(750.0,samples.last().distance,2.0)
        assertTrue(samples.size in 249..252) // 500 m at 2 m
        assertTrue(samples.zipWithNext{a,b->b.distance-a.distance}.all{it in 1.9..2.1})
        assertTrue(RouteProfile.focusSamples(route,RouteProfile.Bounds(171.0,-43.0,171.1,-42.9)).isEmpty())
    }
    @Test fun focusBoundsNeedZoom15(){
        val pose=CameraPose(170.14,-43.59,14.9,0f,0f,1000f)
        assertNull(RouteProfile.focusBounds(pose,400,800))
        val b=RouteProfile.focusBounds(pose.copy(zoom=15.5,pitch=60f),400,800)!!
        assertTrue(b.west<170.14&&b.east>170.14&&b.south<-43.59&&b.north>-43.59)
    }
    @Test fun chartKeepsExtremesAndGapsWhenDecimating(){
        val values=(0 until 5000).map{NativeGeo.ProfilePoint(start,it.toDouble(),if(it==2500)null else if(it==1234)9000.0 else 100.0+(it%7))}
        val chart=RouteProfile.chartSamples(values)
        assertTrue(chart.size<=650*4+2)
        assertTrue(chart.any{it.height==9000.0});assertTrue(chart.any{it.height==null})
        assertEquals(values.first(),chart.first());assertEquals(values.last(),chart.last())
        val short=values.take(1300);assertSame(short,RouteProfile.chartSamples(short))
    }
}
