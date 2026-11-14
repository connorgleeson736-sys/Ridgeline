package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** The offline region frame has the paper's shape: the PDF map fills the page. */
class RegionFrameTest {
    @Test fun a4PortraitAndLandscapeHaveThePaperShape(){
        val ratio=210.0/297.0
        val f=RegionFrame.pageFrame("A4",false,400.0,800.0)
        val frameW=minOf(400*.84,800*.84*ratio)
        assertEquals(.5-frameW/400/2,f[0],1e-9);assertEquals(.5+frameW/400/2,f[2],1e-9)
        assertEquals((f[2]-f[0])*400/((f[3]-f[1])*800),ratio,1e-9)
        val l=RegionFrame.pageFrame("A4",true,400.0,800.0)
        assertEquals(1/ratio,(l[2]-l[0])*400/((l[3]-l[1])*800),1e-9)
        assertEquals(5,RegionFrame.PAPER.size)
    }
}
