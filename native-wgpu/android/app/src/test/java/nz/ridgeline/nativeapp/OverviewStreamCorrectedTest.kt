package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*

class OverviewStreamCorrectedTest {
 private val file="a".repeat(64)
 @Test fun correctedTilesHaveTheirOwnPath(){
  assertEquals("https://tiles.razorbackmaps.co.nz/ctiles-q95/$file/123.webp",OverviewStream.correctedUrl("https://tiles.razorbackmaps.co.nz/",file,123))
  assertNull(OverviewStream.correctedUrl("",file,1))
  assertNull(OverviewStream.correctedUrl("https://x",file.dropLast(1),1))
 }
 private fun webp(body:Int):ByteArray{val total=12+body;val b=ByteArray(total);"RIFF".toByteArray().copyInto(b,0);val n=total-8
  b[4]=(n and 0xff).toByte();b[5]=(n shr 8 and 0xff).toByte();b[6]=(n shr 16 and 0xff).toByte();b[7]=(n shr 24 and 0xff).toByte();"WEBP".toByteArray().copyInto(b,8);return b}
 @Test fun aWholeWebpIsComplete(){assertTrue(OverviewStream.completeWebp(webp(5000)))}
 @Test fun aCutOffOrForeignFileIsNot(){
  assertFalse(OverviewStream.completeWebp(webp(5000).copyOf(3000)))
  assertFalse(OverviewStream.completeWebp("<html>not found</html>".toByteArray()))
  assertFalse(OverviewStream.completeWebp(ByteArray(0)))
 }
}
