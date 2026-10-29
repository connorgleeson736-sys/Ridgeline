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
        if(t<=0||planeZ>=cos(p)*distance*.9)return null
        return (-sin(b)*sin(p)*distance+t*(sin(b)*sin(p)+cos(b)*sx+sin(b)*cos(p)*sy)) to
            (-cos(b)*sin(p)*distance+t*(cos(b)*sin(p)-sin(b)*sx+cos(b)*cos(p)*sy))
    }
    data class Anchor(val mx:Double,val my:Double,val elevation:Double)
    private fun world(pose:CameraPose,scale:Double)=512*2.0.pow(pose.zoom)*scale
    fun pixelsPerMeter(pose:CameraPose,scale:Double)=world(pose,scale)/40030228.88407185/cos(Math.toRadians(pose.latitude))
    fun anchor(pose:CameraPose,width:Int,height:Int,scale:Double,x:Double,y:Double,elevation:Double=pose.elevation.toDouble()):Anchor? {
        val offset=offset(width,height,pose.bearing,pose.pitch,x,y,(elevation-pose.elevation)*pixelsPerMeter(pose,scale))?:return null
        val world=world(pose,scale)
        return Anchor((pose.longitude+180)/360+offset.first/world,mercatorY(pose.latitude)-offset.second/world,elevation)
    }
    /** Solve a fixed geographic terrain point under the current finger, including
     * latitude-dependent altitude scale. Two iterations converge well below a pixel. */
    fun anchored(pose:CameraPose,anchor:Anchor,width:Int,height:Int,scale:Double,x:Double,y:Double):CameraPose? {
        var result=pose
        repeat(4){
            val offset=offset(width,height,pose.bearing,pose.pitch,x,y,(anchor.elevation-pose.elevation)*pixelsPerMeter(result,scale))?:return null
            val world=world(pose,scale)
            result=pose.copy(longitude=((anchor.mx-offset.first/world)*360-180).coerceIn(165.0,180.0),latitude=latitude(anchor.my+offset.second/world).coerceIn(-49.0,-33.0))
        }
        return result
    }
    fun mercatorY(latitude:Double)=(1-asinh(tan(Math.toRadians(latitude)))/PI)/2
    fun latitude(y:Double)=Math.toDegrees(atan(sinh(PI*(1-2*y))))
}
