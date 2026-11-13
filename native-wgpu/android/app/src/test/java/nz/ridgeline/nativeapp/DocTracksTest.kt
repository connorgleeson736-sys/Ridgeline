package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** DOC track summary data: response parsing (shapes as DOC's ArcGIS services
 * return them), line chaining and huts along a track. */
class DocTracksTest {
    @Test fun walkPageLinksKeyTheirLine(){
        assertEquals("c073dc44-e9aa-4c36-9b8a-75cb8365f047",DocTracks.guidOf("https://www.doc.govt.nz/link/c073dc44e9aa4c369b8a75cb8365f047.aspx"))
        assertNull(DocTracks.guidOf("https://www.doc.govt.nz/parks-and-recreation/"))
    }
    @Test fun queriesEscapeNamesAndStayOnDocsOrg(){
        val url=DocTracks.walkUrl("Mt O'Leary Track",169.1,-44.7)
        assertTrue(DocTracks.isUrl(url));assertTrue(url.contains("O%27%27LEARY"));assertTrue(url.endsWith("f=json"))
    }
    @Test fun matchesDocSpellingsOfMountNames(){
        assertEquals(listOf("Mt Iron Track","Mount Iron Track","Mt Iron Tracks","Mount Iron Tracks"),DocTracks.names("Mt Iron Track"))
        assertEquals(listOf("Mount Iron Track","Mt Iron Track","Mount Iron Tracks","Mt Iron Tracks"),DocTracks.names("Mount  Iron Track"))
        assertEquals(listOf("Roys Peak Track","Roys Peak Tracks"),DocTracks.names("Roys Peak Track"))
        assertEquals(listOf("Rabbit Pass Route"),DocTracks.names("Rabbit Pass Route"))
        assertTrue(java.net.URLDecoder.decode(DocTracks.walkUrl("Mt Iron Track",169.1,-44.7),"UTF-8").contains("UPPER(name) IN ('MT IRON TRACK','MOUNT IRON TRACK','MT IRON TRACKS','MOUNT IRON TRACKS')"))
    }
    @Test fun parsesWalks(){
        val json="""{"features":[{"attributes":{"name":"Roys Peak Track","introduction":"Views over the lake.","difficulty":"Easy","completionTime":"5-6 hr return","introductionThumbnail":"https://www.doc.govt.nz/thumbs/large/link/07e89eec10bb41ccb52bd0efe8e86659.aspx","walkingAndTrampingWebPage":"https://www.doc.govt.nz/link/c073dc44e9aa4c369b8a75cb8365f047.aspx"},"geometry":{"x":169.0594,"y":-44.6829}},
            {"attributes":{"name":"Roys Peak Track","introduction":null,"difficulty":"Easy","completionTime":"1 hr","introductionThumbnail":null,"walkingAndTrampingWebPage":"https://www.doc.govt.nz/link/00000000000000000000000000000000.aspx"},"geometry":{"x":172.0,"y":-41.0}}]}"""
        val walks=DocTracks.parseWalks(json);assertEquals(2,walks.size);assertEquals("",walks[1].introduction)
        val w=DocTracks.nearestWalk(walks,Coordinate(169.07,-44.67))!!
        assertEquals("5-6 hr return",w.time);assertEquals("c073dc44-e9aa-4c36-9b8a-75cb8365f047",w.guid)
        assertTrue(DocTracks.parseWalks("{\"error\":{\"code\":400}}").isEmpty());assertTrue(DocTracks.parseWalks("not json").isEmpty())
    }
    @Test fun parsesTrackCharacteristics(){
        val json="""{"features":[{"attributes":{"TechObjectName":"Roys Peak Track","CharName2":"USER_STATUS","CharValue2":"OPEN","CharName3":"WEB_CATEGORY_TRACK","CharValue3":"Walking track","CharName6":"WEB_DOGS_ALLOWED","CharValue6":"No Dogs","CharName7":"WEB_TRACK_TYPE","CharValue7":"One way","CharName8":"WEB_MTB_DURATION","CharValue8":null,"Shape__Length":6928.4},
            "geometry":{"paths":[[[169.07,-44.67],[169.06,-44.68],[169.05,-44.69]]]}}]}"""
        val t=DocTracks.parseTracks(json).single()
        assertEquals("Walking track",t.category);assertEquals("No Dogs",t.dogs);assertEquals("One way",t.type);assertFalse(t.closed)
        assertEquals(3,t.paths.single().size);assertEquals("No dogs",DocTracks.tidy(t.dogs))
    }
    @Test fun parsesOpenHutsAndJoinsPages(){
        val json="""{"features":[
            {"attributes":{"TechObjectName":"Routeburn Flats Hut","SubObjectType":"Great Walk Hut","CharName2":"USER_STATUS","CharValue2":"OPEN","CharName3":"WEB_FACILITIES_HUT","CharValue3":"Cooking","CharName4":"WEB_HUT_BUNKS","CharValue4":"20 each"},"geometry":{"x":168.2,"y":-44.75}},
            {"attributes":{"TechObjectName":"Old Hut","SubObjectType":"Standard Hut","CharName2":"USER_STATUS","CharValue2":"CLOSED"},"geometry":{"x":168.21,"y":-44.75}}]}"""
        val huts=DocTracks.parseHuts(json);assertEquals(listOf("Routeburn Flats Hut"),huts.map{it.name});assertEquals("20 each",huts[0].bunks)
        DocTracks.joinHutPages(huts,"""{"features":[{"attributes":{"name":"routeburn flats hut","bookable":"Yes","staticLink":"https://www.doc.govt.nz/link/abc.aspx"}}]}""")
        assertTrue(huts[0].bookable);assertEquals("https://www.doc.govt.nz/link/abc.aspx",huts[0].page)
    }
    @Test fun chainsPiecesInOrderAndLeavesDistantOnesOut(){
        val a=listOf(Coordinate(170.0,-44.0),Coordinate(170.01,-44.0))
        val b=listOf(Coordinate(170.02,-44.0),Coordinate(170.01,-44.0)) // reversed, joins a's end
        val c=listOf(Coordinate(169.99,-44.0),Coordinate(170.0,-44.0))  // joins a's start
        val far=listOf(Coordinate(171.0,-44.0),Coordinate(171.01,-44.0))
        val line=DocTracks.chain(listOf(b,far,a,c),Coordinate(170.005,-44.0))
        assertEquals(listOf(169.99,170.0,170.01,170.02),line.map{it.longitude})
        assertTrue(DocTracks.chain(emptyList(),Coordinate(0.0,0.0)).isEmpty())
    }
    @Test fun hutsAlongTheTrackInOrder(){
        val line=(0..10).map{Coordinate(170.0+it*.01,-44.0)}
        fun hut(name:String,lon:Double,lat:Double)=DocTracks.Hut(name,"Standard Hut","8 each","",Coordinate(lon,lat))
        val stops=DocTracks.stops(listOf(hut("End",170.099,-44.001),hut("Start",170.001,-44.0005),hut("Far",170.05,-44.05)),line)
        assertEquals(listOf("Start","End"),stops.map{it.hut.name})
        assertEquals(DocTracks.length(line),stops[1].along,200.0)
        val b=DocTracks.bounds(line)!!;assertTrue(b[0]<170.0&&b[2]>170.1&&b[1]< -44.0&&b[3]> -44.0)
    }
    @Test fun hutNamesWithoutAssetCodes(){
        assertEquals("South Ohau Hut",DocTracks.hutName("T3: South Ohau Hut"))
        assertEquals("Mintaro Hut",DocTracks.hutName("Mintaro Hut (2021)"))
        assertEquals("Top Crooked Hut",DocTracks.hutName("Top Crooked Hut 3PM"))
        assertEquals("Iron Gate Hut",DocTracks.hutName("Iron Gate Hut - 2019"))
        assertEquals("Field Hut",DocTracks.hutName("Field Hut (H)"))
        assertEquals("Pouakai Hut",DocTracks.hutName("Pouakai Hut (404823) 2025"))
        assertEquals("Pinnacles Hut",DocTracks.hutName("Pinnacles Hut (Mt Somers Track)"))
        assertEquals("Julia Hut (Old 1958)",DocTracks.hutName("Julia Hut (Old 1958)"))
        assertEquals("Cedar Flat Hut (Historic)",DocTracks.hutName("Cedar Flat Hut (Historic)"))
    }
    /** DOC's Greenstone and Caples page names its huts; Slip Flat Hut (near the
     * Greenstone Track, on the page only as "Slip Flat") is left out. */
    @Test fun hutsTheWalksPageNames(){
        fun stop(n:String,at:Double)=DocTracks.Stop(DocTracks.Hut(n,"","","",Coordinate(168.0,-45.0)),at)
        val near=listOf(stop("Mid Caples Hut",8900.0),stop("Mckellar Hut",30200.0),stop("Greenstone Hut",48600.0),stop("Slip Flat Hut",52000.0))
        val page="There are 3 DOC huts on this track: Mid Caples Hut McKellar Hut Greenstone Hut. Slip Flat is a large open area about halfway down the gorge."
        assertEquals(listOf("Mid Caples Hut","Mckellar Hut","Greenstone Hut"),DocTracks.named(near,page).map{it.hut.name})
        // A page naming none of them: all.
        assertEquals(4,DocTracks.named(near,"A walk up the valley.").size)
    }
}
