package nz.ridgeline.nativeapp
import org.junit.Assert.*
import org.junit.Test
class RouteBlobTest {
 @Test fun exactCoordinatesAndFullLimitRoundTrip(){val points=List(2000){Coordinate(170.14219572+it*1e-9,-43.5955671-it*1e-9)};val bytes=RouteBlob.encode(points);assertEquals(32008,bytes.size);assertEquals(points,RouteBlob.decode(bytes));assertEquals(emptyList<Coordinate>(),RouteBlob.decode(RouteBlob.encode(emptyList())))}
 @Test fun rejectsCorruptionAndNeverProducesInventedPoints(){val data=RouteBlob.encode(listOf(Coordinate(170.1,-43.1)));assertThrows(IllegalArgumentException::class.java){RouteBlob.decode(data.copyOf(data.size-1))};data[0]=0;assertThrows(IllegalArgumentException::class.java){RouteBlob.decode(data)};assertThrows(IllegalArgumentException::class.java){RouteBlob.encode(listOf(Coordinate(1.0,2.0)))}}
}
