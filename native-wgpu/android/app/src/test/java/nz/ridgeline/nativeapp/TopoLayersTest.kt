package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** NZ Topo's Topo250/Topo50 switch. */
class TopoLayersTest {
    @Test fun lINZsOwnSwitchIsTheDefault(){
        // Topo250 to its zoom-12 tiles, Topo50 from 13, whatever the screen.
        for(d in listOf(1.0,2.0,2.625,3.5)){
            assertEquals(TopoLayers.TOPO250,TopoLayers.layer(12,Float.NaN,d));assertEquals(TopoLayers.TOPO50,TopoLayers.layer(13,Float.NaN,d))}
        // Shown as a map zoom: 11.5 on an ordinary screen, earlier on dense ones (finer tiles).
        assertEquals(11.5f,TopoLayers.defaultSwitch(1.0),1e-6f)
        assertEquals(10.5f,TopoLayers.defaultSwitch(2.0),1e-6f)
    }
    @Test fun aChosenSwitchMovesItByMapZoom(){
        // Topo50 from map zoom 9 on a 2x screen: from its zoom-11 tiles (9 + 1 + 1, one map pixel per screen pixel).
        assertEquals(11,TopoLayers.firstTopo50(9f,2.0))
        assertEquals(TopoLayers.TOPO250,TopoLayers.layer(10,9f,2.0));assertEquals(TopoLayers.TOPO50,TopoLayers.layer(11,9f,2.0))
        // Later: Topo250 kept to its zoom-15 tiles at switch 14.5 on a 1x screen.
        assertEquals(TopoLayers.TOPO250,TopoLayers.layer(15,14.5f,1.0));assertEquals(TopoLayers.TOPO50,TopoLayers.layer(16,14.5f,1.0))
    }
}
