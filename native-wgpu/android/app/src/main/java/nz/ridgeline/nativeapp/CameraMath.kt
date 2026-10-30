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
    data class Eye(val mx:Double,val my:Double,val altitude:Double)
    fun eye(pose:CameraPose,height:Int,scale:Double):Eye{
        val distance=height*1.5;val world=world(pose,scale)
        val b=Math.toRadians(pose.bearing.toDouble());val p=Math.toRadians(pose.pitch.toDouble())
        return Eye((pose.longitude+180)/360-sin(b)*sin(p)*distance/world,
            mercatorY(pose.latitude)+cos(b)*sin(p)*distance/world,
            pose.elevation+cos(p)*distance/pixelsPerMeter(pose,scale))
    }
    /** MapLibre transform_helper.recalculateZoomAndCenter: move the pivot along
     * the view ray and change zoom, retaining eye position and pitch. */
    fun recenter(pose:CameraPose,height:Int,scale:Double,elevation:Double):CameraPose?{
        if(height<=0||!elevation.isFinite()||abs(elevation-pose.elevation)<.001)return null
        val eye=eye(pose,height,scale);val p=Math.toRadians(pose.pitch.toDouble())
        if(elevation>=eye.altitude||cos(p)<=.01)return null
        val distance=(eye.altitude-elevation)/cos(p)
        val b=Math.toRadians(pose.bearing.toDouble());var meters=40030228.88407185*cos(Math.toRadians(pose.latitude))
        var previousStep=Double.POSITIVE_INFINITY
        var d=distance/meters;var mx=eye.mx+sin(b)*sin(p)*d;var my=eye.my-cos(b)*sin(p)*d
        for(pass in 0 until 8){
            val next=40030228.88407185*cos(Math.toRadians(latitude(my)));val step=abs(next-meters)
            if(step<=1e-12*meters||step>=previousStep)break
            previousStep=step;meters=next;d=distance/meters
            mx=eye.mx+sin(b)*sin(p)*d;my=eye.my-cos(b)*sin(p)*d
        }
        val z=log2(height*1.5/(512*d*scale))
        // A nearer crest must not push an already-close view backwards at max zoom.
        if(z !in 4.0..19.0||mx*360-180 !in 165.0..180.0||latitude(my) !in -49.0..-33.0)return null
        return pose.copy(longitude=mx*360-180,latitude=latitude(my),zoom=z,elevation=elevation.toFloat())
    }
    /** Outside a held gesture, raise a buried eye by pitch/zoom, keeping the
     * terrain pivot fixed instead of moving the whole scene vertically. */
    fun clearTerrain(pose:CameraPose,height:Int,scale:Double,lift:Double):CameraPose{
        if(!lift.isFinite()||lift<=.01)return pose
        val distance=height*1.5/pixelsPerMeter(pose,scale)
        val p=Math.toRadians(pose.pitch.toDouble());val horizontal=distance*sin(p);val vertical=distance*cos(p)+lift
        val newDistance=hypot(horizontal,vertical)
        return pose.copy(zoom=(pose.zoom+log2(distance/newDistance)).coerceIn(4.0,19.0),pitch=Math.toDegrees(atan2(horizontal,vertical)).toFloat())
    }
    fun mercatorY(latitude:Double)=(1-asinh(tan(Math.toRadians(latitude)))/PI)/2
    fun latitude(y:Double)=Math.toDegrees(atan(sinh(PI*(1-2*y))))
}
