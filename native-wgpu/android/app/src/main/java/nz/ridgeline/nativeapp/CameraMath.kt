package nz.ridgeline.nativeapp
import kotlin.math.*
/** Camera-plane algebra matching the native perspective; no GPU/depth readback. */
object CameraMath {
    fun offset(width:Int,height:Int,bearing:Float,pitch:Float,x:Double,y:Double,planeZ:Double=0.0):Pair<Double,Double>? {
        if(width<=0||height<=0)return null
        val b=Math.toRadians(bearing.toDouble());val p=Math.toRadians(pitch.toDouble());val distance=height*1.5
        val sx=(x-width*.5)/distance;val sy=(height*.5-y)/distance
        val denominator=cos(p)-sin(p)*sy;if(denominator<=.001)return null
        val t=(cos(p)*distance-planeZ)/denominator
        return (-sin(b)*sin(p)*distance+t*(sin(b)*sin(p)+cos(b)*sx+sin(b)*cos(p)*sy)) to
            (-cos(b)*sin(p)*distance+t*(cos(b)*sin(p)-sin(b)*sx+cos(b)*cos(p)*sy))
    }
    fun mercatorY(latitude:Double)=(1-asinh(tan(Math.toRadians(latitude)))/PI)/2
    fun latitude(y:Double)=Math.toDegrees(atan(sinh(PI*(1-2*y))))
}
