package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** MapLayers decodes only layers a zoom newly enables and appends them. The
 * styled result must match decoding every enabled layer at once. */
class IncrementalDecodeTest {
    private val fixtures get()=File(requireNotNull(System.getProperty("ridgeline.fixtures")))
    @Test fun addingLayersByZoomMatchesAFullDecodeAtEveryZoom(){
        val bytes=File(fixtures,"topography/linz-12-3983-2600.pbf").readBytes()
        val style=TopographyStyle(File(fixtures,"../../main/assets/topography-style.json").readText())
        for(state in listOf(MapSettings.State(),MapSettings.State(contours=true))){
            var mask=emptySet<String>();var features=emptyList<VectorTile.Feature>();var decodes=0
            for(quarter in 40..68){val zoom=quarter/4.0
                val enabled=style.sourceLayers(state,zoom);val missing=enabled-mask
                if(missing.isNotEmpty()){features=features+VectorTile.decode(bytes,12,3983,2600,missing);mask=mask+missing;decodes++}
                val full=VectorTile.decode(bytes,12,3983,2600,enabled)
                assertEquals("zoom $zoom",style.build(full,state,zoom,12.0),style.build(features,state,zoom,12.0))
            }
            assertTrue(decodes<=mask.size)
        }
    }
}
