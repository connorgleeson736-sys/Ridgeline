package nz.ridgeline.nativeapp
import android.view.Surface
import java.nio.ByteBuffer
object NativeBridge {
 init {System.loadLibrary("ridgeline_renderer")}
 @JvmStatic external fun discardFrame(handle:Long)
 @JvmStatic external fun acquire(handle:Long,width:Int,height:Int):Int
 @JvmStatic external fun prepareFrame(handle:Long,remainingNanos:Long,idle:Boolean)
 @JvmStatic external fun frameStats(handle:Long,output:ByteBuffer)
 @JvmStatic external fun frameWake(handle:Long,callback:Runnable)
 @JvmStatic external fun create(surface:Surface,width:Int,height:Int):Long
 @JvmStatic external fun terrainProfile(data:ByteBuffer):Boolean
 @JvmStatic external fun uploadGeometry(handle:Long,z:Int,x:Int,y:Int,data:ByteBuffer):Boolean
 @JvmStatic external fun uploadImage(handle:Long,z:Int,x:Int,y:Int,data:ByteBuffer):Boolean
 @JvmStatic external fun uploadInterior(handle:Long,z:Int,x:Int,y:Int,token:Long,data:ByteBuffer):Boolean
 @JvmStatic external fun clearInterior(handle:Long,z:Int,x:Int,y:Int,token:Long)
 @JvmStatic external fun uploadPlaneResult(handle:Long,z:Int,x:Int,y:Int):Int
 @JvmStatic external fun uploadGeometryResult(handle:Long,z:Int,x:Int,y:Int,data:ByteBuffer):Int
 @JvmStatic external fun uploadImageResult(handle:Long,z:Int,x:Int,y:Int,data:ByteBuffer):Int
 @JvmStatic external fun uploadResult(handle:Long,z:Int,x:Int,y:Int,heights:ByteBuffer,rgba:ByteBuffer):Int
 @JvmStatic external fun demand(handle:Long,input:ByteBuffer):Boolean
 @JvmStatic external fun residency(handle:Long,output:ByteBuffer):Boolean
 @JvmStatic external fun cameraSafety(handle:Long,width:Int,height:Int,longitude:Double,latitude:Double,zoom:Double,bearing:Float,pitch:Float,elevation:Float,output:ByteBuffer):Boolean
 @JvmStatic external fun pickSnapshot(handle:Long,x:Double,y:Double,width:Int,height:Int,longitude:Double,latitude:Double,zoom:Double,bearing:Float,pitch:Float,elevation:Float,threeD:Boolean,relief:Float,output:ByteBuffer):Boolean
 @JvmStatic external fun pick(handle:Long,x:Double,y:Double,output:ByteBuffer):Boolean
 @JvmStatic external fun mipmapProof(handle:Long,output:ByteBuffer):Boolean
 @JvmStatic external fun routes(handle:Long,input:ByteBuffer):Boolean
 @JvmStatic external fun waypoints(handle:Long,output:ByteBuffer):Int
 @JvmStatic external fun location(handle:Long,longitude:Double,latitude:Double,enabled:Boolean):Boolean
 @JvmStatic external fun inspect(handle:Long,longitude:Double,latitude:Double,enabled:Boolean):Boolean
 @JvmStatic external fun cartography(handle:Long,input:ByteBuffer):Boolean
 @JvmStatic external fun project(handle:Long,input:ByteBuffer,output:ByteBuffer):Int
 @JvmStatic external fun labelMetadata(handle:Long,metadata:ByteBuffer):Boolean
 @JvmStatic external fun labelAtlas(handle:Long,width:Int,height:Int,rgba:ByteBuffer,metadata:ByteBuffer):Boolean
 @JvmStatic external fun placeLabels(handle:Long,input:ByteBuffer):Boolean
 @JvmStatic external fun configure(handle:Long,input:ByteBuffer):Boolean
 @JvmStatic external fun clearGeometry(handle:Long)
 @JvmStatic external fun clearImages(handle:Long)
 @JvmStatic external fun stats(output:ByteBuffer):Int
 @JvmStatic external fun plan(width:Int,height:Int,longitude:Double,latitude:Double,zoom:Double,bearing:Float,pitch:Float,elevation:Float,budget:Int,target:Double,maxZoom:Int,margin:Double,output:ByteBuffer):Int
 @JvmStatic external fun planFocused(width:Int,height:Int,longitude:Double,latitude:Double,zoom:Double,bearing:Float,pitch:Float,elevation:Float,budget:Int,target:Double,maxZoom:Int,margin:Double,focusLongitude:Double,focusLatitude:Double,focusRange:Double,output:ByteBuffer):Int
 @JvmStatic external fun releaseUnused(handle:Long)
 @JvmStatic external fun gpuBacklog(handle:Long):Int
 @JvmStatic external fun textureBackend(mode:Int)
 @JvmStatic external fun tileBudget(handle:Long,tiles:Int)
 @JvmStatic external fun destroy(handle:Long)
 @JvmStatic external fun render(handle:Long,width:Int,height:Int,longitude:Double,latitude:Double,zoom:Double,logicalZoom:Double,bearing:Float,pitch:Float,elevation:Float,cameraRevision:Long,cameraInputNanos:Long,cameraLatchNanos:Long):Int
 @JvmStatic external fun upload(handle:Long,z:Int,x:Int,y:Int,heights:ByteBuffer,rgba:ByteBuffer):Boolean
}
