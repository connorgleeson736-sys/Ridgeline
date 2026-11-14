package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class NativeUiPortTest {
    private val fixtures get()=File(requireNotNull(System.getProperty("ridgeline.fixtures")))
    @Test fun realLinzTileRetainsEnabledNamesWhenDisabledLayersAreSkipped(){
        val bytes=File(fixtures,"topography/linz-12-3983-2600.pbf").readBytes()
        val style=TopographyStyle(File(fixtures,"../../main/assets/topography-style.json").readText())
        val names=MapSettings.State(roads=false,tracks=false,huts=false,contours=false,borders=false)
        val all=setOf("contours","streets","pois","place_labels","water_polygons","water_lines")
        val enabled=style.sourceLayers(names,12.0);assertEquals(setOf("place_labels"),enabled)
        assertTrue(style.sourceLayers(names,6.0).isEmpty())
        val full=VectorTile.decode(bytes,12,3983,2600,all)
        val filtered=VectorTile.decode(bytes,12,3983,2600,enabled)
        assertTrue(full.size>filtered.size);assertTrue(filtered.isNotEmpty())
        val a=style.build(full,names,12.0);val b=style.build(filtered,names,12.0)
        assertEquals(a,b);assertTrue(b.labels.isNotEmpty());assertTrue(b.lines.isEmpty())
        val visible=style.build(full,MapSettings.State(contours=true),15.0)
        assertTrue(visible.lines.isNotEmpty());assertTrue(visible.labels.all{it.point.x.isFinite()&&it.point.y.isFinite()})
        fun run(layers:Set<String>):Double{val start=System.nanoTime();repeat(10){VectorTile.decode(bytes,12,3983,2600,layers)};return (System.nanoTime()-start)/10e6}
        val before=ArrayList<Double>();val after=ArrayList<Double>();repeat(5){if(it%2==0){before+=run(all);after+=run(enabled)}else{after+=run(enabled);before+=run(all)}}
        val report="""{"scope":"Host JVM real LINZ MVT; identical enabled names, skipped disabled source-layer geometry. Not phone FPS.","sourceTileBytes":${bytes.size},"fullFeatures":${full.size},"enabledFeatures":${filtered.size},"beforeMs":$before,"afterMs":$after,"beforeMedianMs":${before.sorted()[2]},"afterMedianMs":${after.sorted()[2]},"visibleLabels":${b.labels.size},"allLayersLines":${visible.lines.size}}"""
        println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"disabled-vector-layers.json").writeText(report)}
    }
    @Test fun decoderSkipsUnknownLengthFieldsAndRejectsTruncatedWireData(){
        // Unknown length-delimited tile field followed by an empty supported layer.
        val bytes=byteArrayOf(10,3,1,2,3,26,5,10,3,112,111,105)
        assertEquals(emptyList<VectorTile.Feature>(),VectorTile.decode(bytes,0,0,0,setOf("poi")))
        for(input in listOf(byteArrayOf(26,-1),byteArrayOf(26,3,10,9,1))){try{VectorTile.decode(input,0,0,0,setOf("poi"));fail("Truncated protobuf must fail")}catch(_:IOException){}}
    }
    @Test fun chartSectionMatchesCapacitorEndpointInterpolationAndMissingGaps(){
        val a=Coordinate(170.0,-43.0);val b=Coordinate(170.01,-43.01)
        val samples=listOf(NativeGeo.ProfilePoint(a,0.0,100.0),NativeGeo.ProfilePoint(a,100.0,200.0),NativeGeo.ProfilePoint(b,200.0,150.0))
        val section=NativeGeo.section(samples,175.0,50.0)!!
        assertEquals(125.0,section.distance,0.0);assertEquals(50.0,section.ascent,0.0);assertEquals(37.5,section.descent,0.0);assertTrue(section.complete)
        assertNull(NativeGeo.section(samples,50.0,50.0));assertEquals(100.0,NativeGeo.grade(samples,50.0)!!,0.0)
        val gap=samples.toMutableList();gap[1]=gap[1].copy(height=null)
        assertNull(NativeGeo.profileAt(gap,50.0)!!.height);assertNull(NativeGeo.grade(gap,50.0));assertFalse(NativeGeo.section(gap,0.0,200.0)!!.complete)
    }
    @Test fun flatCoverContainsRotatedViewportAndKeepsNearestDetailWithinBudget(){
        for(bearing in listOf(0f,37f,-90f,180f))for(budget in listOf(16,64)){
            val pose=CameraPose(zoom=12.0,bearing=bearing,pitch=0f,elevation=0f)
            val ids=FlatSelection.plan(360,640,pose,128.0,budget,18)!!;assertTrue(ids.size<=budget);assertTrue(ids.isNotEmpty())
            for((x,y)in listOf(0.0 to 0.0,360.0 to 0.0,0.0 to 640.0,360.0 to 640.0,180.0 to 320.0)){
                val p=CameraMath.anchor(pose,360,640,1.0,x,y,0.0)!!
                assertTrue("Uncovered flat viewport at $bearing/$budget",ids.any{val n=1 shl it.z;p.mx>=it.x.toDouble()/n-1e-12&&p.mx<=(it.x+1.0)/n+1e-12&&p.my>=it.y.toDouble()/n-1e-12&&p.my<=(it.y+1.0)/n+1e-12})
            }
            for(a in ids)for(b in ids)if(a!=b&&a.z<b.z){val delta=b.z-a.z;assertFalse("No overlapping parent/child ownership",a.x==b.x.shr(delta)&&a.y==b.y.shr(delta))}
        }
        assertNull(FlatSelection.plan(360,640,CameraPose(),256.0,24,18))
        assertFalse(MapSettings.State(threeD=false).needsElevation());assertFalse(MapSettings.State(base=MapSettings.Base.TOPO,threeD=false,hillshade=true).needsElevation());assertTrue(MapSettings.State(threeD=false,hillshade=true).needsElevation())
    }

    @Test fun visibleVectorParentBridgesOnlyMissingChildrenAndRetiresWhenCovered(){
        val parent=GpuUploadQueue.Id(11,1991,1300);val a=GpuUploadQueue.Id(12,3982,2600);val b=GpuUploadQueue.Id(12,3983,2600)
        assertEquals(listOf(LayerCover.Patch(a,a),LayerCover.Patch(parent,b)),LayerCover.display(listOf(a,b),setOf(parent,a)))
        assertEquals(listOf(LayerCover.Patch(a,a),LayerCover.Patch(b,b)),LayerCover.display(listOf(a,b),setOf(parent,a,b)))
        assertEquals(listOf(LayerCover.Patch(a,a),LayerCover.Patch(b,b)),LayerCover.display(listOf(parent),setOf(a,b)))
        assertTrue(LayerCover.display(listOf(GpuUploadQueue.Id(12,4000,2600)),setOf(parent,a,b)).isEmpty())
    }

    @Test fun visibleBackgroundLayerEscapesHeldCacheWithoutPromotingItsNetworkClass(){
        val held=java.util.concurrent.CountDownLatch(1);val entered=java.util.concurrent.CountDownLatch(2);val calls=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val backend=object:TileBackend{
            override fun local(key:ResourceKey):TileBody?{entered.countDown();held.await(3,java.util.concurrent.TimeUnit.SECONDS);return null}
            override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{calls.add(key.url);done(Result.success(TileBody(byteArrayOf(1),200,Long.MAX_VALUE)));return{}}
        }
        val transport=TileTransport(backend,0,initialLimits=DownloadLimits(1,1,1))
        val warm=transport.consumer(1000,true);val visible=transport.consumer(800,true,cacheDeadline=true);val foreground=transport.consumer(0,false,true)
        try{
            val speculative=warm.acquire(ResourceKey("warm",false));val layer=visible.acquire(ResourceKey("names",false));assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1),layer.get(1,java.util.concurrent.TimeUnit.SECONDS).bytes);assertEquals(1L,held.count)
            val image=foreground.acquire(ResourceKey("image",false));assertArrayEquals(byteArrayOf(1),image.get(1,java.util.concurrent.TimeUnit.SECONDS).bytes)
            assertFalse(speculative.isDone);assertEquals(setOf("names","image"),calls.toSet())
        }finally{held.countDown();warm.release(true);visible.release(true);foreground.release(true);transport.close();transport.awaitClosed()}
    }

}
