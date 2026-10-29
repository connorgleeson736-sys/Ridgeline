package nz.ridgeline.nativeapp
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class RouteDomainTest {
 private fun point(a:JSONArray)=Coordinate(a.getDouble(0),a.getDouble(1))
 @Test fun referenceLengthsLocationsSamplesAndTotals(){
  val data=JSONObject(File(System.getProperty("ridgeline.fixtures"),"route-goldens.json").readText())
  val cases=data.getJSONArray("cases")
  for(i in 0 until cases.length()){
   val c=cases.getJSONObject(i);val rows=c.getJSONArray("points");val points=List(rows.length()){point(rows.getJSONArray(it))}
   assertEquals(c.getDouble("length"),NativeGeo.length(points),1e-6)
   val locator=NativeGeo.Locator(points);val locations=c.getJSONArray("locations")
   for(j in 0 until locations.length()){val row=locations.getJSONObject(j);val expected=point(row.getJSONArray("point"));val actual=locator.at(row.getDouble("distance"))!!;assertEquals(expected.longitude,actual.longitude,1e-10);assertEquals(expected.latitude,actual.latitude,1e-10)}
   val heights=c.getJSONArray("heights");val actual=NativeGeo.totals(List(heights.length()){if(heights.isNull(it))null else heights.getDouble(it)});val expected=c.getJSONObject("totals")
   assertEquals(expected.getDouble("gain"),actual.ascent,1e-8);assertEquals(expected.getDouble("loss"),actual.descent,1e-8);assertEquals(expected.getBoolean("complete"),actual.complete)
   if(expected.isNull("high"))assertNull(actual.high)else assertEquals(expected.getDouble("high"),actual.high!!,1e-8)
   val samples=NativeGeo.samples(points,25,10000.0);val es=c.getJSONArray("samples");assertEquals(es.length(),samples.size)
   for(j in samples.indices){val e=es.getJSONObject(j);val p=point(e.getJSONArray("p"));assertEquals(e.getDouble("d"),samples[j].distance,1e-6);assertEquals(p.longitude,samples[j].point.longitude,1e-10);assertEquals(p.latitude,samples[j].point.latitude,1e-10)}
  }
  val rounded=data.getJSONArray("rounded")
  for(i in 0 until rounded.length()){val r=rounded.getJSONObject(i);assertEquals(r.getDouble("result"),Coordinate(r.getDouble("value"),-43.0).rounded().longitude,0.0)}
 }
 @Test fun dragHasSingleUndoAndCancellationRestoresExactState(){
  val a=Coordinate(170.0,-43.0);val b=Coordinate(170.01,-43.01);val c=Coordinate(170.02,-43.02)
  val editor=RouteEditor();editor.reset(listOf(a,b));assertTrue(editor.beginDrag(1));assertTrue(editor.previewDrag(c));assertFalse(editor.canUndo)
  assertTrue(editor.cancelDrag());assertEquals(listOf(a,b),editor.points);assertFalse(editor.canUndo)
  assertTrue(editor.beginDrag(1));repeat(20){editor.previewDrag(Coordinate(170.02+it*.000001,-43.02))};assertTrue(editor.commitDrag());assertTrue(editor.undo());assertEquals(listOf(a,b),editor.points);assertEquals(1,editor.selected);assertFalse(editor.canUndo)
 }
 @Test fun editLimitsAndWaypointPrecision(){
  val editor=RouteEditor();val p=Coordinate(170.0,-43.0)
  assertEquals(RouteEditor.Result.CHANGED,editor.append(p));assertEquals(RouteEditor.Result.TOO_CLOSE,editor.append(p));assertEquals(RouteEditor.Result.OUTSIDE_NZ,editor.append(Coordinate(164.0,-43.0)))
  assertEquals(RouteEditor.Result.CHANGED,editor.append(Coordinate(170.1,-43.1)));val inserted=Coordinate(170.050000123,-43.05)
  assertEquals(RouteEditor.Result.CHANGED,editor.insertAfter(0,inserted));assertEquals(inserted,editor.points[1]);assertTrue(editor.deleteSelected());assertTrue(editor.undo());assertEquals(1,editor.selected)
  editor.reset(List(2000){p});assertEquals(RouteEditor.Result.LIMIT,editor.append(p))
 }
 @Test fun combinesInOrderAndReversesWithoutDuplicatingJoin(){
  val a=Coordinate(170.0,-43.0);val b=Coordinate(170.1,-43.1);val c=Coordinate(170.2,-43.2)
  assertEquals(listOf(a,b,c),NativeGeo.combine(listOf(listOf(a,b)to false,listOf(c,b)to true)))
  assertThrows(IllegalArgumentException::class.java){NativeGeo.combine(listOf(List(1100){if(it%2==0)a else b}to false,List(1100){if(it%2==0)b else c}to false))}
 }
}
