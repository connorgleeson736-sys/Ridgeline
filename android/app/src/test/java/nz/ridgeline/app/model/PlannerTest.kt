package nz.ridgeline.app.model

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PlannerTest {
    private val cook=Coordinate(170.141,-43.595)
    @Test fun distanceUsesMetresAndIsSymmetric() {
        val a=Coordinate(0.0,0.0); val b=Coordinate(1.0,0.0)
        assertEquals(111195.08,Geography.distance(a,b),.1)
        assertEquals(Geography.distance(a,b),Geography.distance(b,a),1e-6)
        assertEquals(0.0,Geography.distance(a,a),0.0)
    }
    @Test fun antipodesStayFinite() { assertTrue(Geography.distance(Coordinate(0.0,0.0),Coordinate(180.0,0.0)).isFinite()) }
    @Test fun invalidCoordinatesRejected() {
        listOf(Double.NaN,Double.POSITIVE_INFINITY,181.0).forEach { lon -> assertThrows(IllegalArgumentException::class.java) { Coordinate(lon,0.0) } }
        assertThrows(IllegalArgumentException::class.java) { Coordinate(0.0,-90.0) }
    }
    @Test fun samplingPreservesSwitchbackCorners() {
        val points=listOf(cook,Coordinate(170.142,-43.595),Coordinate(170.142,-43.594),cook)
        val samples=Geography.samples(points)
        assertEquals(cook,samples.first().second)
        assertEquals(cook,samples.last().second)
        points.forEach { assertTrue(samples.any { sample -> sample.second==it }) }
        assertEquals(Geography.length(points),samples.last().first,1e-5)
        assertTrue(samples.zipWithNext().all { it.second.first>it.first.first })
    }
    @Test fun zeroLengthRouteHasSingleSample() { assertEquals(1,Geography.samples(listOf(cook,cook,cook)).size) }
    @Test fun emptyRouteAndSinglePoint() { assertTrue(Geography.samples(emptyList()).isEmpty());assertEquals(1,Geography.samples(listOf(cook)).size) }
    @Test fun interpolationCrossesAntimeridianByShortArc() {
        val samples=Geography.samples(listOf(Coordinate(179.9,0.0),Coordinate(-179.9,0.0)))
        assertTrue(samples.all { kotlin.math.abs(it.second.longitude)>179.8 })
    }
    @Test fun missingElevationsDoNotInventAscentAcrossGap() {
        val p=listOf(100.0,100.0,null,1000.0,1000.0).mapIndexed { i,h -> ElevationSample(i*100.0,h) }
        val stats=Geography.elevationStats(p)
        assertFalse(stats.complete);assertEquals(0.0,stats.ascent,0.0);assertEquals(1000.0,stats.highest!!,0.0)
    }
    @Test fun elevationTotalsUseHysteresis() {
        val stats=Geography.elevationStats(listOf(0.0,30.0,60.0,30.0,0.0).mapIndexed { i,h -> ElevationSample(i.toDouble(),h) })
        assertTrue(stats.complete); assertEquals(25.0,stats.ascent,1e-6);assertEquals(25.0,stats.descent,1e-6)
    }
    @Test fun gpxRoundTripEscapesNamesAndPreservesCoordinates() {
        val route=Route(name="Cook & Tasman <ridge> \"route\"",points=listOf(cook,Coordinate(170.2,-43.6)))
        val output=ByteArrayOutputStream(); Gpx.write(route,output)
        val loaded=Gpx.read(output.toByteArray().inputStream()).single()
        assertEquals(route.name,loaded.name);assertEquals(route.points,loaded.points)
    }
    @Test fun disconnectedGpxSegmentsStaySeparate() {
        val xml="""<gpx><trk><name>Traverse</name><trkseg><trkpt lat="-43" lon="170"/></trkseg><trkseg><trkpt lat="-44" lon="171"/></trkseg></trk></gpx>"""
        val routes=Gpx.read(xml.byteInputStream())
        assertEquals(2,routes.size);assertTrue(routes.all { it.points.size==1 && it.name=="Traverse" })
    }
    @Test fun waypointNamesDoNotRenameTheTrack() {
        val xml="""<gpx><trk><name>Track name</name><trkseg><trkpt lat="-43" lon="170"><name>Waypoint name</name></trkpt></trkseg></trk></gpx>"""
        assertEquals("Track name",Gpx.read(xml.byteInputStream()).single().name)
    }
    @Test fun utf16DeclarationsCannotBypassGpxProtection() {
        val xml="<!DOCTYPE gpx><gpx/>".toByteArray(Charsets.UTF_16)
        assertThrows(IllegalArgumentException::class.java) { Gpx.read(xml.inputStream()) }
    }
    @Test fun gpxRouteElementsSupported() {
        val routes=Gpx.read("""<gpx><rte><name>Route</name><rtept lat="-43" lon="170"/></rte></gpx>""".byteInputStream())
        assertEquals("Route",routes.single().name)
    }
    @Test fun externalEntitiesRejected() {
        assertThrows(IllegalArgumentException::class.java) { Gpx.read("""<!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx><trk><name>&x;</name></trk></gpx>""".byteInputStream()) }
    }
    @Test fun emptyAndUnrelatedXmlRejected() {
        listOf("<gpx/>","<html/>").forEach { xml -> assertThrows(IllegalArgumentException::class.java) { Gpx.read(xml.byteInputStream()) } }
    }
    @Test fun gpxPointLimitsAreEnforced() {
        val xml="<gpx><trk><trkseg>"+"<trkpt lat=\"-43\" lon=\"170\"/>".repeat(Route.MAX_POINTS+1)+"</trkseg></trk></gpx>"
        assertThrows(IllegalArgumentException::class.java) { Gpx.read(xml.byteInputStream()) }
    }
    @Test fun oversizedGpxIsRejectedBeforeParsing() { assertThrows(IllegalArgumentException::class.java) { Gpx.read(ByteArray(Gpx.MAX_BYTES+1).inputStream()) } }
    @Test fun tileMathMatchesKnownMercatorCoordinates() {
        assertEquals(TileId(TileKind.DEM,1,1,1),TileId.at(Coordinate(0.0,0.0),1,TileKind.DEM))
        val t=TileId.at(cook,14,TileKind.DEM)
        assertTrue(t.x in 15930..15940);assertTrue(t.y in 10390..10410)
    }
    @Test fun regionIncludesParentsAndDemHalo() {
        val region=OfflineRegion("test","Cook",Bounds(170.13,-43.60,170.15,-43.59),14)
        val tiles=region.tiles().toSet()
        assertTrue(TileKind.entries.all { TileId(it,0,0,0) in tiles })
        val center=TileId.at(cook,14,TileKind.DEM)
        assertTrue(center in tiles); assertTrue(center.copy(x=center.x+1) in tiles)
        assertEquals(tiles.size,region.tileCount())
    }
    @Test fun hugeRegionCountingStopsAtLimit() {
        val region=OfflineRegion("large","NZ",Bounds(165.0,-49.0,180.0,-33.0),16)
        assertEquals(OfflineRegion.MAX_TILES+1,region.tileCount())
    }
    @Test fun invalidBoundsAndZoomRejected() {
        assertThrows(IllegalArgumentException::class.java) { Bounds(171.0,-44.0,170.0,-43.0) }
        assertThrows(IllegalArgumentException::class.java) { OfflineRegion("a","a",Bounds(170.0,-44.0,171.0,-43.0),20) }
    }
    @Test fun boundedReadReadsNoMoreThanOneExtraByte() {
        assertEquals(101,ByteArray(1000).inputStream().readBounded(100).size)
        assertEquals(50,ByteArray(50).inputStream().readBounded(100).size)
    }
}
