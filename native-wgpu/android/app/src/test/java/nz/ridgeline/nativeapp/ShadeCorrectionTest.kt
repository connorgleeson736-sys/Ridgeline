package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture

class ShadeCorrectionTest {
 private fun argb(r:Int,g:Int,b:Int)=(0xff shl 24) or (r shl 16) or (g shl 8) or b
 @Test fun dividesByKeepAtTheTilesPlaceInTheBlock(){
  val values=ByteArray(SHADE_BLOCK*SHADE_BLOCK){255.toByte()}
  // Tile (1,2) of the block: its first pixel at block (256,512).
  values[512*SHADE_BLOCK+256]=191.toByte() // keep 0.75
  values[512*SHADE_BLOCK+257]=128.toByte() // keep ~0.5: 200 caps at 255
  val px=IntArray(65536){argb(90,120,150)};px[1]=argb(200,100,0)
  assertTrue(ShadeCorrection.apply(px,values,256,512))
  assertEquals(argb(120,160,200),px[0]) // 90*255/191=120.2, 120→160.2, 150→200.3
  assertEquals(argb(255,199,0),px[1])
  assertEquals(argb(90,120,150),px[2]) // unshaded pixels untouched
 }
 @Test fun aBlockWithoutShadingChangesNothing(){
  val px=IntArray(65536){argb(1,2,3)}
  assertFalse(ShadeCorrection.apply(px,ByteArray(SHADE_BLOCK*SHADE_BLOCK){255.toByte()},768,768))
  assertTrue(px.all{it==argb(1,2,3)})
 }
 @Test fun alphaIsKept(){
  val values=ByteArray(SHADE_BLOCK*SHADE_BLOCK){128.toByte()}
  val px=IntArray(65536){(0x80 shl 24) or (10 shl 16)}
  ShadeCorrection.apply(px,values,0,0)
  assertEquals(0x80,px[0] ushr 24)
 }
 @Test fun anUnavailableCorrectionLeavesThePhoto(){
  for(failure in listOf(TileHttpException(404),TileHttpException(503),java.io.IOException("offline miss")))
   assertNull(ShadeCorrection.orNone<ByteArray>({throw failure}))
 }
 @Test fun pendingCancelledOrAbortedWorkIsNeverTheFallback(){
  for(failure in listOf<Throwable>(ResourcePending(CompletableFuture<Unit>()),PreparationAborted(),InterruptedIOException("cancelled"))){
   val thrown=runCatching{ShadeCorrection.orNone<ByteArray>({throw failure})}.exceptionOrNull()
   assertSame(failure,thrown)
  }
 }
}
