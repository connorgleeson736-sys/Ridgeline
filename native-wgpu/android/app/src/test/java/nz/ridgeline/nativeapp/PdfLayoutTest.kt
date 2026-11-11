package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PdfLayoutTest {
    @Test fun pageMapAreaHasTheRegionFramesAspect(){
        for(paper in RegionFrame.PAPER.keys)for(landscape in listOf(false,true)){
            val (w,h)=PdfLayout.pageSize(paper,landscape)
            val area=(w-2*PdfLayout.SIDE)/(h-PdfLayout.TOP-PdfLayout.BOTTOM)
            val f=RegionFrame.pageFrame(paper,landscape,1000.0,1000.0)
            val frame=(f[2]-f[0])/(f[3]-f[1])
            assertEquals("$paper $landscape",frame,area.toDouble(),0.01)
        }
        assertEquals(595 to 842,PdfLayout.pageSize("A4",false));assertEquals(842 to 595,PdfLayout.pageSize("A4",true))
    }
    @Test fun scaleBarUsesRoundDistances(){
        assertEquals(2.0,PdfLayout.scaleKm(3.4),1e-9);assertEquals(5.0,PdfLayout.scaleKm(7.0),1e-9)
        assertEquals(0.5,PdfLayout.scaleKm(0.6),1e-9);assertEquals(10.0,PdfLayout.scaleKm(12.0),1e-9)
        assertEquals(0.0,PdfLayout.scaleKm(0.0),0.0)
    }
}
