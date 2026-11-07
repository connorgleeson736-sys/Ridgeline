package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlaceSearchTest {
    private val search by lazy{PlaceSearch(File("src/main/assets/search/places.tsv").bufferedReader().lineSequence())}
    private val queenstown=Coordinate(168.66,-45.03)
    private fun top(q:String,near:Coordinate?=queenstown)=search.search(q,near,5).map{it.place.kind to it.place.name}
    @Test fun findsTracksHutsPeaksAndPasses(){
        assertTrue(search.size>40000)
        assertEquals("walk" to "Gillespie Pass Circuit",top("gillespie pass c")[0])
        assertTrue(top("gillespie pass").contains("pass" to "Gillespie Pass"))
        assertEquals("walk" to "Greenstone and Caples tracks",top("greenstone caples")[0])
        assertEquals("hut" to "Mueller Hut",top("mueller hut")[0])
        assertTrue(top("mt castor").contains("peak" to "Mount Castor"))
    }
    @Test fun ignoresMacronsCaseAndOrderAndToleratesATypo(){
        assertTrue(top("aoraki").any{it.second=="Aoraki/Mount Cook"})
        assertTrue(top("mount cook").any{it.second=="Aoraki/Mount Cook"})
        assertEquals(PlaceSearch.normalize("Tītītea"),"tititea")
        assertEquals("walk" to "Gillespie Pass Circuit",top("circuit gillespie")[0])
        assertTrue(top("gilespie pass").any{it.second.startsWith("Gillespie Pass")})
    }
    @Test fun isFastEnoughToRunPerKeystroke(){
        search.search("warm",queenstown)
        val start=System.nanoTime();repeat(10){search.search("gillespie p",queenstown)}
        val ms=(System.nanoTime()-start)/10/1_000_000.0
        assertTrue("$ms ms per search",ms<60)
    }
}
