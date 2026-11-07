package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** The offline region frame follows Capacitor's pageFrame(). */
class RegionFrameTest {
    @Test fun a4PortraitAndLandscapeMatchCapacitor(){
        // Capacitor: A4 210 x 297 mm at 72 dpi, ratio (w-60)/(h-94), 84% of the space.
        val w=210*72/25.4;val h=297*72/25.4;val ratio=(w-60)/(h-94)
        val f=RegionFrame.pageFrame("A4",false,400.0,800.0)
        val frameW=minOf(400*.84,800*.84*ratio)
        assertEquals(.5-frameW/400/2,f[0],1e-9);assertEquals(.5+frameW/400/2,f[2],1e-9)
        assertEquals((f[2]-f[0])*400/((f[3]-f[1])*800),ratio,1e-9)
        val l=RegionFrame.pageFrame("A4",true,400.0,800.0)
        assertTrue((l[2]-l[0])*400>(l[3]-l[1])*800) // landscape is wider than tall
        assertEquals(5,RegionFrame.PAPER.size)
    }
}
