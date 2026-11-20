package nz.ridgeline.nativeapp

import kotlin.math.*

/** A measured terrain hit, independent of the navigation pivot. Range is in
 * normalized Mercator units, matching the reference surface-focus policy. */
data class SurfaceFocus(val longitude:Double,val latitude:Double,val elevation:Double,val range:Double)

internal class SurfaceFocusTracker {
    private data class Sample(val pose:CameraPose,val width:Int,val height:Int,val scale:Double,val at:Long,val focus:SurfaceFocus?)
    @Volatile private var sample:Sample?=null
    fun clear(){sample=null}
    fun pending(pose:CameraPose,width:Int,height:Int,scale:Double,coverChanged:Boolean):Boolean {
        val old=sample?:return true
        return coverChanged||old.pose!=pose||old.width!=width||old.height!=height||old.scale!=scale
    }
    fun needsSample(pose:CameraPose,width:Int,height:Int,scale:Double,now:Long,coverChanged:Boolean,gesturing:Boolean):Boolean {
        val old=sample?:return true
        val changed=pending(pose,width,height,scale,coverChanged)
        return changed&&(now-old.at>=240||(!gesturing&&old.pose!=pose)||old.width!=width||old.height!=height||old.scale!=scale)
    }
    fun update(pose:CameraPose,width:Int,height:Int,scale:Double,now:Long,point:DoubleArray?):Boolean {
        val previous=current(pose,width,height,scale,now)
        val next=point?.takeIf{it.size==3&&it.all(Double::isFinite)&&it[0] in -180.0..180.0&&it[1] in -85.0..85.0}?.let{
            val range=range(pose,height,scale,it[0],it[1],it[2])
            if(range.isFinite()&&range>1e-12)SurfaceFocus(it[0],it[1],it[2],range)else null
        }
        // Ignore tiny hit changes within the reference's 3.5% range / 6% range
        // bands. This avoids rebuilding plans for immutable-neighbour arrivals.
        val retained=if(previous!=null&&next!=null){
            val dx=(next.longitude-previous.longitude)/360
            val dy=CameraMath.mercatorY(next.latitude)-CameraMath.mercatorY(previous.latitude)
            if(hypot(dx,dy)<=next.range*.035&&abs(next.range-previous.range)<=next.range*.06)previous else next
        }else next
        sample=Sample(pose,width,height,scale,now,retained)
        return retained!=previous
    }
    fun current(pose:CameraPose,width:Int,height:Int,scale:Double,now:Long):SurfaceFocus? {
        val old=sample?:return null;val hit=old.focus?:return null
        if(old.width!=width||old.height!=height||old.scale!=scale)return null
        if(old.pose==pose)return hit
        // A recent real hit may remain useful during a small movement. Never
        // carry it to another view or manufacture a new point on a height plane.
        if(now-old.at>500||!visible(pose,width,height,scale,hit))return null
        return hit.copy(range=range(pose,height,scale,hit.longitude,hit.latitude,hit.elevation))
    }
    companion object {
        private fun range(pose:CameraPose,height:Int,scale:Double,lon:Double,lat:Double,elevation:Double):Double {
            val world=512*2.0.pow(pose.zoom)*scale;val d=height*1.5/world
            val p=Math.toRadians(pose.pitch.toDouble());val b=Math.toRadians(pose.bearing.toDouble())
            val dx=(lon-pose.longitude)/360+sin(b)*sin(p)*d
            val dy=CameraMath.mercatorY(lat)-CameraMath.mercatorY(pose.latitude)-cos(b)*sin(p)*d
            val dz=elevation/(40030228.88407185*cos(Math.toRadians(lat)))-pose.elevation/(40030228.88407185*cos(Math.toRadians(pose.latitude)))-cos(p)*d
            return sqrt(dx*dx+dy*dy+dz*dz)
        }
        private fun visible(pose:CameraPose,width:Int,height:Int,scale:Double,hit:SurfaceFocus):Boolean {
            if(width<=0||height<=0)return false
            val world=512*2.0.pow(pose.zoom)*scale
            val x=(hit.longitude-pose.longitude)/360*world
            val y=(CameraMath.mercatorY(pose.latitude)-CameraMath.mercatorY(hit.latitude))*world
            val z=(hit.elevation-pose.elevation)*CameraMath.pixelsPerMeter(pose,scale)
            val p=Math.toRadians(pose.pitch.toDouble());val b=Math.toRadians(pose.bearing.toDouble())
            val depth=height*1.5+sin(b)*sin(p)*x+cos(b)*sin(p)*y-cos(p)*z
            return depth>height/50.0&&abs(3*(cos(b)*x-sin(b)*y)/depth*height/width)<=.8&&
                abs(3*(sin(b)*cos(p)*x+cos(b)*cos(p)*y+sin(p)*z)/depth)<=.8
        }
    }
}
