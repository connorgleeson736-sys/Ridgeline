package nz.ridgeline.nativeapp
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DocPhotosTest{
    /** The photos in DOC's Brewster Track text, with captions and credits; they
     * are not the walk's words (directions, legs and starts read only text). */
    @Test fun brewsterTrackPhotos(){
        val sections=DocPages.parse(File("src/test/fixtures/docpages/brewster-track.html").readText())
        val photos=sections.flatMap{it.blocks}.filterIsInstance<DocPages.Image>()
        assertTrue(photos.size>=5)
        val steep=photos.first{it.url.endsWith("brewster-track-slippery-steep-alice-milne390.jpg")}
        assertTrue(steep.url.startsWith("https://www.doc.govt.nz/contentassets/"))
        assertTrue(steep.text.startsWith("Brewster Track is very steep and unformed"))
        assertTrue(steep.text.contains("Image: Alice Milne"))
        val d=sections.first{it.title=="Description"}
        assertTrue(d.blocks.any{it is DocPages.Image})
        assertTrue(DocPages.words(d.blocks).none{it is DocPages.Image})
        assertTrue(WalkDirections.sentences(sections).none{it.contains("very steep and unformed")})
        assertTrue(photos.none{it.text.contains("See larger")})
    }
    /** A photo linking to DOC's larger version ("See larger") uses that. */
    @Test fun largerVersionWhenLinked(){
        val html="""<doc-accordion-item header-text="Description"><div class="block textblock x"><p><a href="/contentassets/89f2/cave-stream-medium-1920.jpg"><img src="/contentassets/89f2/cave-stream-medium-flow-390.jpg" alt="" width="390" height="260"></a><br>Medium flow See larger (JPG, 500K)</p></div></doc-accordion-item>"""
        val photo=DocPages.parse(html).single().blocks.single() as DocPages.Image
        assertEquals("https://www.doc.govt.nz/contentassets/89f2/cave-stream-medium-1920.jpg",photo.url)
        assertEquals("Medium flow",photo.text)
    }
    /** The map's "Mt Iron Track" is DOC's "Mount Iron Track". */
    @Test fun trackFoundUnderDocsName(){
        val id=GpuUploadQueue.Id(14,16081,11753);val b=id.bounds
        val path=listOf(VectorTile.Point(b[0]+(b[2]-b[0])*.2,b[1]+(b[3]-b[1])*.5),VectorTile.Point(b[0]+(b[2]-b[0])*.6,b[1]+(b[3]-b[1])*.5))
        val f=VectorTile.Feature("streets",1,2,mapOf("kind" to "track","name" to "Mt Iron Track"),listOf(path))
        assertEquals(1,MapLayers.trackPieces(id,listOf(f),"Mount Iron Track").first.size)
        assertEquals(0,MapLayers.trackPieces(id,listOf(f),"Mount Iron Summit Track").first.size)
    }
}
