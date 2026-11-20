package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*

class BackdropTest {
 private fun id(z:Int,x:Int,y:Int)=GpuUploadQueue.Id(z,x,y)
 private fun contains(a:GpuUploadQueue.Id,b:GpuUploadQueue.Id)=a.z<=b.z&&(b.x shr(b.z-a.z))==a.x&&(b.y shr(b.z-a.z))==a.y
 @Test fun backdropCoversEveryPlannedTileAboutFourLevelsAboveTheFocus(){
  // A z15 view: focus leaves near (31600,20600) plus distant coarser tiles.
  val rows=LinkedHashMap<GpuUploadQueue.Id,Int>()
  rows[id(14,15800,10300)]=0
  for((i,x) in (31600..31603).withIndex())rows[id(15,x,20600)]=1+i
  for((i,x) in (31590..31612).withIndex())rows[id(15,x,20598)]=64+i
  rows[id(13,7900,5149)]=70
  val backdrop=SourceDemand.backdrop(rows)
  assertTrue(backdrop.isNotEmpty()&&backdrop.size<=6)
  // Four levels above z15 would be z11; the backdrop is capped at zoom 10.
  assertTrue(backdrop.all{it.z==10})
  // Every planned tile finer than the backdrop level has a backdrop ancestor.
  for(tile in rows.keys.filter{it.z>10})assertTrue("$tile covered",backdrop.any{contains(it,tile)})
  // Backdrop tiles are never already in the plan.
  assertTrue(backdrop.none{it in rows})
 }
 @Test fun wideViewsClimbUntilAtMostSixBackdropTiles(){
  val rows=LinkedHashMap<GpuUploadQueue.Id,Int>()
  rows[id(14,15800,10300)]=1
  for(i in 0 until 40)rows[id(12,3900+i*3,2575+i)]=64+i
  val backdrop=SourceDemand.backdrop(rows)
  assertTrue(backdrop.size in 1..6)
  for(tile in rows.keys.filter{it.z>backdrop.first().z})assertTrue(backdrop.any{contains(it,tile)})
 }
 @Test fun backdropLevelStaysWithinZoomFiveToTen(){
  assertEquals(listOf(id(10,500000 shr 9,330000 shr 9)),SourceDemand.backdrop(mapOf(id(19,500000,330000) to 1)))
  // A z7 view: four levels up is clamped to the zoom-5 floor.
  assertEquals(listOf(id(5,30,20)),SourceDemand.backdrop(mapOf(id(7,120,80) to 1)))
  // A plan already at or above the floor needs no extra backdrop.
  assertTrue(SourceDemand.backdrop(mapOf(id(5,30,20) to 1)).isEmpty())
  assertTrue(SourceDemand.backdrop(emptyMap()).isEmpty())
 }
 @Test fun progressiveTerrainCoversFocusAndNearestLeavesOnly(){
  for(rank in 0..4)assertTrue(ProgressiveTerrain.eligibleRank(rank))
  for(rank in 64 until 76)assertTrue(ProgressiveTerrain.eligibleRank(rank))
  for(rank in listOf(5,8,20,63,76,200,1000,10000))assertFalse(ProgressiveTerrain.eligibleRank(rank))
 }
}
