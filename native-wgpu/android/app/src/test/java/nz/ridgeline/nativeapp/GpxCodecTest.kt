package nz.ridgeline.nativeapp
import org.junit.Assert.*
import org.junit.Test
class GpxCodecTest {
 private val points=listOf(Coordinate(170.14219572,-43.5955671),Coordinate(170.15,-43.59))
 @Test fun fullPrecisionUnicodeNameRoundTrip(){val name="Aoraki & <Tititea> ‘route’";val result=GpxCodec.decode(GpxCodec.encode(name,points),"ignored.gpx");assertEquals(name,result.name);assertEquals(points,result.points)}
 @Test fun rejectsSeparateSegmentsOversizeInvalidAndForeignCoordinates(){
  fun reject(body:String)=assertThrows(IllegalArgumentException::class.java){GpxCodec.decode(body.toByteArray(),"route.gpx")}
  reject("<gpx><trk><trkseg/><trkseg/></trk></gpx>");reject("<gpx><rte/><rte/></gpx>")
  reject("<gpx><trkpt lat='-43'/><trkpt lon='170' lat='-43'/></gpx>")
  reject("<gpx><trkpt lon='NaN' lat='-43'/><trkpt lon='170' lat='-43'/></gpx>")
  reject("<gpx><trkpt lon='160' lat='-43'/><trkpt lon='170' lat='-43'/></gpx>")
  reject("<!DOCTYPE gpx [<!ENTITY e SYSTEM 'file:///nonexistent'>]><gpx>&e;</gpx>")
  assertThrows(IllegalArgumentException::class.java){GpxCodec.decode(ByteArray(2*1024*1024+1),"big.gpx")}
 }
 @Test fun routeAndWaypointFallbackNamespaces(){
  for(tag in listOf("rtept","wpt")){
   val xml="<g:gpx xmlns:g='http://www.topografix.com/GPX/1/1'><g:$tag lon='170.14219572' lat='-43.5955671'/><g:$tag lon='170.15' lat='-43.59'/></g:gpx>"
   val result=GpxCodec.decode(xml.toByteArray(),"Trail.GPX");assertEquals("Trail",result.name);assertEquals(points,result.points)
  }
 }
}
