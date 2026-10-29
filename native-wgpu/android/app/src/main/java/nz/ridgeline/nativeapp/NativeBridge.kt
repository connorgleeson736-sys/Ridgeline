package nz.ridgeline.nativeapp
import android.view.Surface
import java.nio.ByteBuffer
object NativeBridge {
 init {System.loadLibrary("ridgeline_renderer")}
 @JvmStatic external fun create(surface:Surface,width:Int,height:Int):Long
 @JvmStatic external fun plan(width:Int,height:Int,longitude:Double,latitude:Double,zoom:Double,bearing:Float,pitch:Float,elevation:Float,budget:Int,output:ByteBuffer):Int
 @JvmStatic external fun tileBudget(handle:Long,tiles:Int)
 @JvmStatic external fun destroy(handle:Long)
 @JvmStatic external fun render(handle:Long,width:Int,height:Int,longitude:Double,latitude:Double,zoom:Double,bearing:Float,pitch:Float,elevation:Float):Boolean
 @JvmStatic external fun upload(handle:Long,z:Int,x:Int,y:Int,heights:ByteBuffer,rgba:ByteBuffer):Boolean
}
