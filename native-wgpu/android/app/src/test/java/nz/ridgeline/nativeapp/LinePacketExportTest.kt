package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real LINZ vector tiles through the app's style, clipping and packing,
 * written for the renderer's parser test (renderer/tests/line_packets.rs). */
class LinePacketExportTest {
    @Test fun exportRealPackets(){
        val dir=System.getenv("LINE_TILES")?.let(::File)?:return
        val out=File(System.getenv("LINE_OUT")?:return).apply{mkdirs()}
        val style=TopographyStyle(File("src/main/assets/topography-style.json").readText())
        val groups=ArrayList<LineGroup>()
        for(f in dir.listFiles()!!.filter{it.name.endsWith(".pbf")}.sorted()){
            val (z,x,y)=f.name.removeSuffix(".pbf").split("-").takeLast(3).map{it.toInt()}
            val layers=style.sourceLayers(MapSettings.State(contours=true),14.0)
            val features=VectorTile.decode(f.readBytes(),z,x,y,layers)
            for(zoom in listOf(12.0,13.25,14.5,16.0)){
                val output=style.build(features,MapSettings.State(contours=true),zoom,z.toDouble())
                val n=1.0/(1 shl z);val b=doubleArrayOf(x*n,y*n,(x+1)*n,(y+1)*n)
                val clipped=ArrayList<TopographyStyle.Line>()
                for(line in output.lines)for(path in BoundaryIndex.clip(line.points,b))if(path.size>=2)clipped.add(line.copy(points=path))
                groups.add(LineGroup.pack(z,x,y,b,clipped))
            }
        }
        assertTrue(groups.sumOf{it.lines}>0)
        fun write(name:String,held:Set<Long>){val p=LineGroup.packet(groups,held);val bytes=ByteArray(p.remaining());p.get(bytes);File(out,name).writeBytes(bytes)}
        write("full.bin",emptySet())
        write("refs.bin",groups.take(groups.size/2).mapTo(HashSet()){it.id})
        File(out,"meta.txt").writeText("${groups.size} ${groups.sumOf{it.lines}}")
    }
}
