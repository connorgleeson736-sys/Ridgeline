package nz.ridgeline.nativeapp

import kotlin.math.*

/** Immutable national geometry, indexed once. A rejected chunk terminates its fragment. */
internal class BoundaryIndex(paths:List<List<VectorTile.Point>>) {
    private data class Chunk(val first:Int,val end:Int,val bounds:DoubleArray)
    private data class Path(val points:List<VectorTile.Point>,val bounds:DoubleArray,val chunks:List<Chunk>)
    private val paths=paths.filter{it.size>1}.map{p->Path(p,bounds(p,0,p.lastIndex),(1 until p.size step 32).map{first->val end=min(first+32,p.size);Chunk(first,end,bounds(p,first-1,end-1))})}
    private val cached=LinkedHashMap<GpuUploadQueue.Id,List<List<VectorTile.Point>>>()
    var segmentTests=0L;private set
    fun fragments(id:GpuUploadQueue.Id):List<List<VectorTile.Point>> {
        cached[id]?.let{return it}
        val box=id.bounds;val result=ArrayList<List<VectorTile.Point>>()
        for(path in paths)if(overlaps(path.bounds,box))result.addAll(clip(path.points,box,path.chunks.filter{overlaps(it.bounds,box)}.map{it.first until it.end}){segmentTests++})
        val frozen=result.toList();cached[id]=frozen;if(cached.size>128)cached.remove(cached.keys.first());return frozen
    }
    companion object {
        private fun bounds(p:List<VectorTile.Point>,first:Int,last:Int):DoubleArray {
            val b=doubleArrayOf(Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY)
            for(i in first..last){b[0]=min(b[0],p[i].x);b[1]=min(b[1],p[i].y);b[2]=max(b[2],p[i].x);b[3]=max(b[3],p[i].y)};return b
        }
        private fun overlaps(a:DoubleArray,b:DoubleArray)=!(a[2]<b[0]||a[0]>b[2]||a[3]<b[1]||a[1]>b[3])
        fun clip(path:List<VectorTile.Point>,box:DoubleArray,ranges:List<IntRange> = listOf(1 until path.size),tested:()->Unit={}):List<List<VectorTile.Point>> {
            val result=ArrayList<List<VectorTile.Point>>();var part=ArrayList<VectorTile.Point>();var previous=0
            fun flush(){if(part.size>1)result.add(part);part=ArrayList()}
            for(range in ranges){if(range.first!=previous+1)flush()
                for(i in range){tested();val a=path[i-1];val b=path[i];val dx=b.x-a.x;val dy=b.y-a.y;var t0=0.0;var t1=1.0
                    fun edge(p:Double,q:Double):Boolean{if(p==0.0)return q>=0;val t=q/p;if(p<0){if(t>t1)return false;t0=max(t0,t)}else{if(t<t0)return false;t1=min(t1,t)};return true}
                    if(edge(-dx,a.x-box[0])&&edge(dx,box[2]-a.x)&&edge(-dy,a.y-box[1])&&edge(dy,box[3]-a.y)){
                        val p=VectorTile.Point(a.x+dx*t0,a.y+dy*t0);val q=VectorTile.Point(a.x+dx*t1,a.y+dy*t1)
                        if(part.isNotEmpty()&&part.last()!=p)flush();if(part.isEmpty())part.add(p);part.add(q)
                    }else if(part.isNotEmpty())flush()
                    previous=i
                }
            };flush();return result
        }
    }
}
