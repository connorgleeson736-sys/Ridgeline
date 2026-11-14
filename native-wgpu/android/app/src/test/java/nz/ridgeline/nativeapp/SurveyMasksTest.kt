package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture

class SurveyMasksTest {
 private val aerial="https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/17/127471/83202.webp?api=k"
 private val webp="https://basemaps.linz.govt.nz/v1/tiles/canterbury-2015-2016-0.3m/WebMercatorQuad/17/127471/83202.webp?api=k"
 private val png="https://basemaps.linz.govt.nz/v1/tiles/canterbury-2015-2016-0.3m/WebMercatorQuad/17/127471/83202.png?api=k"
 @Test fun masksRequestWebpAndKeepTheOriginalPngAddressForFallback(){
  assertEquals(webp,SurveyMasks.url(aerial,"canterbury-2015-2016-0.3m"))
  assertEquals(png,SurveyMasks.url(aerial,"canterbury-2015-2016-0.3m",png=true))
 }
 @Test fun webpIsUsedDirectlyWhenAvailable(){
  val calls=ArrayList<String>()
  assertArrayEquals(byteArrayOf(7),SurveyMasks.fetch(aerial,"canterbury-2015-2016-0.3m",{calls+=it;byteArrayOf(7)}))
  assertEquals(listOf(webp),calls)
 }
 @Test fun providerOrOfflineFailureFallsBackToPng(){
  for(failure in listOf(TileHttpException(404),TileHttpException(503),java.io.IOException("offline miss"))){
   val calls=ArrayList<String>()
   val bytes=SurveyMasks.fetch(aerial,"canterbury-2015-2016-0.3m",{calls+=it;if(it==webp)throw failure;byteArrayOf(5)})
   assertArrayEquals(byteArrayOf(5),bytes);assertEquals(listOf(webp,png),calls)
  }
 }
 @Test fun pendingCancelledOrAbortedWorkNeverStartsAFallback(){
  for(failure in listOf<Throwable>(ResourcePending(CompletableFuture<Unit>()),PreparationAborted(),InterruptedIOException("cancelled"))){
   val calls=ArrayList<String>()
   val thrown=runCatching{SurveyMasks.fetch(aerial,"canterbury-2015-2016-0.3m",{calls+=it;throw failure})}.exceptionOrNull()
   assertSame(failure,thrown);assertEquals(listOf(webp),calls)
  }
 }
 @Test fun aCancelledPreparationStopsBeforeTheFallbackRequest(){
  val calls=ArrayList<String>()
  val thrown=runCatching{SurveyMasks.fetch(aerial,"canterbury-2015-2016-0.3m",{calls+=it;throw TileHttpException(503)},{throw PreparationAborted()})}.exceptionOrNull()
  assertTrue(thrown is PreparationAborted);assertEquals(listOf(webp),calls)
 }
}
