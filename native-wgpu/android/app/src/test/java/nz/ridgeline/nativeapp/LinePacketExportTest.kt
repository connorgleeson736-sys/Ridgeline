package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real LINZ vector tiles through the app's style, clipping and packing,
 * written for the renderer's parser test (renderer/tests/line_packets.rs).
 * Also checks every packed value against the renderer's validation, so a
 * value that would make the renderer reject a whole packet is found here. */
class LinePacketExportTest {
    @Test fun exportRealPackets(){
        val dir=System.getenv("LINE_TILES")?.let(::File)?:return
        val out=File(System.getenv("LINE_OUT")?:return).apply{mkdirs()}
        val style=TopographyStyle(File("src/main/assets/topography-style.json").readText())
        val problems=ArrayList<String>();var packets=0
        for(f in dir.listFiles()!!.filter{it.name.endsWith(".pbf")}.sorted()){
            val (z,x,y)=f.name.removeSuffix(".pbf").split("-").takeLast(3).map{it.toInt()}
            for(contours in listOf(false,true)){val state=MapSettings.State(contours=contours)
                val features=VectorTile.decode(f.readBytes(),z,x,y,style.sourceLayers(state,19.0)+style.sourceLayers(state,z.toDouble()))
                val groups=ArrayList<LineGroup>()
                var zoom=z.toDouble()
                while(zoom<=19.0){
                    val output=style.build(features,state,zoom,z.toDouble())
                    val n=1.0/(1 shl z);val b=doubleArrayOf(x*n,y*n,(x+1)*n,(y+1)*n)
                    val clipped=ArrayList<TopographyStyle.Line>()
                    for(line in output.lines)for(path in BoundaryIndex.clip(line.points,b))if(path.size>=2)clipped.add(line.copy(points=path))
                    for(l in clipped){
                        if(!l.width.isFinite()||l.width<=0f||l.width>40f)problems+="${f.name} z$zoom width ${l.width}"
                        if(!l.dash.isFinite()||l.dash<0f||l.dash>400f||!l.gap.isFinite()||l.gap<0f||l.gap>400f)problems+="${f.name} z$zoom dash ${l.dash}/${l.gap}"
                        if(l.points.any{!it.x.isFinite()||!it.y.isFinite()||it.x !in 0.0..1.0||it.y !in 0.0..1.0})problems+="${f.name} z$zoom point outside 0..1 or not finite"
                        if(l.points.size>262144)problems+="${f.name} z$zoom line of ${l.points.size} points"
                    }
                    groups.add(LineGroup.pack(z,x,y,b,clipped))
                    zoom+=.75
                }
                val p=LineGroup.packet(groups,emptySet());val bytes=ByteArray(p.remaining());p.get(bytes)
                File(out,"${f.name.removeSuffix(".pbf")}-${if(contours)"c" else "n"}.bin").writeBytes(bytes);packets++
            }
        }
        File(out,"meta.txt").writeText("$packets")
        assertTrue(problems.distinct().take(20).joinToString("\n"),problems.isEmpty())
    }
}
