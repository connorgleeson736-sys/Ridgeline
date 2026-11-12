package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Against NOAA's published WMM2025 test values (declination to 0.01°). */
class MagneticModelTest {
    private val fixtures get()=File(requireNotNull(System.getProperty("ridgeline.fixtures")))
    private val model=MagneticModel(File(fixtures,"../../main/assets/wmm/WMM2025.COF").readText())
    @Test fun matchesNoaaTestValues(){
        val rows=File(fixtures,"WMM2025_TestValues.txt").readLines().filter{it.isNotBlank()&&!it.startsWith("#")}.map{it.trim().split(Regex("\\s+")).map(String::toDouble)}
        assertTrue(rows.size>=10)
        for(v in rows){
            val d=model.declination(v[2],v[3],v[0],v[1])
            assertEquals("year ${v[0]} alt ${v[1]} at ${v[2]},${v[3]}",v[4],d,0.011)
        }
    }
    @Test fun newZealandDeclinationIsAbout20To26DegreesEast(){
        val auckland=model.declination(-36.85,174.76,2026.9);val invercargill=model.declination(-46.41,168.35,2026.9)
        assertTrue("$auckland",auckland in 17.0..23.0);assertTrue("$invercargill",invercargill in 22.0..28.0);assertTrue(invercargill>auckland)
    }
}
