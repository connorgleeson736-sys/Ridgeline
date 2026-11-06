package nz.ridgeline.nativeapp
import kotlin.math.*

/** MapLibre-style plane coverage; no height envelope, terrain raycast or JNI selection. */
internal object FlatSelection {
    fun plan(width:Int,height:Int,pose:CameraPose,target:Double,budget:Int,maxZoom:Int):List<GpuUploadQueue.Id>?{
        if(width<=0||height<=0||pose.pitch>5f||target<=0)return null
        val corners=listOf(0.0 to 0.0,width.toDouble() to 0.0,0.0 to height.toDouble(),width.toDouble() to height.toDouble()).map{(x,y)->CameraMath.anchor(pose,width,height,1.0,x,y,0.0)?:return null}
        val box=doubleArrayOf(max((165.0+180)/360,corners.minOf{it.mx}),max(CameraMath.mercatorY(-33.0),corners.minOf{it.my}),min(1.0,corners.maxOf{it.mx}),min(CameraMath.mercatorY(-49.0),corners.maxOf{it.my}))
        if(box[0]>=box[2]||box[1]>=box[3])return emptyList()
        val cap=max(1,budget);val desired=floor(pose.zoom+log2(512.0/target)).toInt().coerceIn(0,maxZoom)
        fun cover(z:Int):List<GpuUploadQueue.Id>{val n=1 shl z;val x0=floor(box[0]*n).toInt().coerceIn(0,n-1);val x1=floor(box[2]*n).toInt().coerceIn(0,n-1);val y0=floor(box[1]*n).toInt().coerceIn(0,n-1);val y1=floor(box[3]*n).toInt().coerceIn(0,n-1);if((x1-x0+1).toLong()*(y1-y0+1)>cap)return emptyList();return buildList{for(y in y0..y1)for(x in x0..x1)add(GpuUploadQueue.Id(z,x,y))}}
        var z=desired;var rows=cover(z);while(rows.isEmpty()&&z>0){rows=cover(--z)}
        val mx=(pose.longitude+180)/360;val my=CameraMath.mercatorY(pose.latitude)
        fun distance(id:GpuUploadQueue.Id):Double{val n=(1 shl id.z).toDouble();val x=((id.x+.5)/n-mx);val y=((id.y+.5)/n-my);return x*x+y*y}
        val selected=rows.toMutableList();val blocked=HashSet<GpuUploadQueue.Id>()
        while(true){val parent=selected.filter{it.z<desired&&it !in blocked}.minByOrNull(::distance)?:break;val n=1 shl(parent.z+1)
            val children=buildList{for(y in parent.y*2..parent.y*2+1)for(x in parent.x*2..parent.x*2+1)if((x+1.0)/n>=box[0]&&x.toDouble()/n<=box[2]&&(y+1.0)/n>=box[1]&&y.toDouble()/n<=box[3])add(GpuUploadQueue.Id(parent.z+1,x,y))}
            if(children.isEmpty()||selected.size+children.size-1>cap){blocked.add(parent);continue};selected.remove(parent);selected.addAll(children)
        }
        return selected.sortedBy(::distance)
    }
}
