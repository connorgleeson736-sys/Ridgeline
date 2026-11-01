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
        assertNull(NativeGeo.section(samples,50.0,50.0))
        val gap=samples.toMutableList();gap[1]=gap[1].copy(height=null)
        assertNull(NativeGeo.profileAt(gap,50.0)!!.height);assertFalse(NativeGeo.section(gap,0.0,200.0)!!.complete)
    }
}
