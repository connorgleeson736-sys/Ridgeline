package nz.ridgeline.nativeapp
import android.content.Context
import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
/** Data-only port of the reference geographic routing; no JavaScript in runtime. */
class NativeImagery(private val context:Context,private val fetch:(String)->ByteArray,private val linz:(Int,Int,Int)->String){
 private val catalog by lazy{context.assets.open("overview-index.json").use{JSONObject(it.readBytes().toString(Charsets.UTF_8))}}
 private val atlas by lazy{context.assets.open("gap-atlas.json").use{JSONObject(it.readBytes().toString(Charsets.UTF_8))}}
 private fun bitmap(data:ByteArray):Bitmap=BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply{inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888})?.also{check(it.width==256&&it.height==256){"Unexpected imagery size"}}?:error("Imagery decode failed")
 private fun pixels(b:Bitmap)=IntArray(65536).also{b.getPixels(it,0,256,0,0,256,256)}
 private fun saved(z:Int,x:Int,y:Int):ByteArray?{
  val key="$z/$x/$y";val coarse=catalog.getJSONObject("coarse").optJSONObject(key)
  if(coarse!=null)return context.assets.open(coarse.getString("natural")).use{it.readBytes()}
  val entry=catalog.getJSONObject("tiles").optJSONObject(key)?:return null
  val r=entry.optJSONObject("natural")?:entry
  val file=r.getString("file");val offset=r.getLong("offset");val length=r.getInt("bytes");require(file.matches(Regex("[a-f0-9]{64}"))&&offset>=0&&length in 20..1048576)
  val asset=try{context.assets.openFd("overview-packs/$file.bin")}catch(_:java.io.FileNotFoundException){return null}
  return asset.use{asset->require(offset+length<=asset.length);asset.createInputStream().use{input->val bytes=ByteArray(length);val target=ByteBuffer.wrap(bytes);while(target.hasRemaining()){val n=input.channel.read(target,asset.startOffset+offset+target.position());check(n>0){"Incomplete overview image"}};bytes}}
 }
 private fun base(z:Int,x:Int,y:Int):Bitmap {
  val reference=catalog.getJSONObject("reference").opt("$z/$x/$y")
  if(reference!=null){val name=if(reference is JSONObject)reference.getString("natural")else reference.toString();return bitmap(context.assets.open(name.removePrefix("/")).use{it.readBytes()})}
  return bitmap(if(z<=12)saved(z,x,y)?:fetch(linz(z,x,y)) else fetch(linz(z,x,y)))
 }
 private fun ringPoints(rings:JSONArray)=List(rings.length()){i->rings.getJSONArray(i).let{ring->List(ring.length()){j->ring.getJSONArray(j).let{doubleArrayOf(it.getDouble(0),it.getDouble(1))}}}}
 private fun relation(rings:JSONArray,box:DoubleArray)=SourceGeometry.relation(points(rings),box)
 private fun points(rings:JSONArray)=ringPoints(rings).map{ring->ring.map{SourceGeometry.Point(it[0],it[1])}}
 fun load(z:Int,x:Int,y:Int):IntArray{
  val az=atlas.getInt("z");val coarse=if(z<az)atlas.getJSONObject("coarse").optJSONObject("$z/$x/$y") else null
  val factor=Math.pow(2.0,(z-az).toDouble());val entry=if(z>=az)atlas.getJSONObject("entries").optJSONObject("${kotlin.math.floor(x/factor).toInt()}/${kotlin.math.floor(y/factor).toInt()}") else null
  val box=if(entry!=null){val size=256/factor;val px=x*size-entry.getInt("x")*256;val py=y*size-entry.getInt("y")*256;doubleArrayOf(px-2,py-2,px+size+2,py+size+2)}else null
  val mode=if(coarse!=null){if(coarse.optBoolean("full"))"esri" else "boundary"}else if(entry==null)"linz" else SourceGeometry.mode(z,x,y,entry.getInt("z"),entry.getInt("x"),entry.getInt("y"),entry.getInt("width"),entry.getInt("height"),points(entry.getJSONArray("outer")),entry.optJSONArray("inner")?.let{points(it)}?:emptyList(),entry.optJSONArray("seamRings")?.let{points(it)}?:emptyList(),entry.optBoolean("vector"))
  if(mode=="linz"){val b=base(z,x,y);try{return pixels(b)}finally{b.recycle()}}
  val ez=minOf(z,18);val ff=1 shl(z-ez)
  val esri=if(coarse!=null)bitmap(context.assets.open(coarse.getString("url").removePrefix("/")).use{it.readBytes()}) else bitmap(fetch("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$ez/${y/ff}/${x/ff}"))
  if(mode=="esri"&&(coarse!=null||z==ez)){try{return pixels(esri)}finally{esri.recycle()}}
  val crop=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);drawCrop(Canvas(crop),esri,x,y,ff);esri.recycle()
  val original=if(mode=="boundary")base(z,x,y)else null
  try{if(original==null)return pixels(crop)
   if(coarse!=null){val output=original.copy(Bitmap.Config.ARGB_8888,true);try{Canvas(output).drawBitmap(crop,0f,0f,null);return pixels(output)}finally{output.recycle()}}
   val mask=vectorMask(entry!!,z,x,y);val fine=entry.optJSONArray("fineSources")
   // The reference lets valid fine-survey alpha veto Esri along seam rings.
   if(fine!=null&&entry.optJSONArray("seamRings")?.let{relation(it,box!!)!=-1}==true){val survey=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);try{val canvas=Canvas(survey);for(i in 0 until fine.length()){val row=fine.getJSONObject(i);val name=row.getString("name");val fz=minOf(z,row.optInt("maxZoom",19),19);val sf=1 shl(z-fz);val u=linz(fz,x/sf,y/sf).replace("/aerial/","/$name/").replace(".webp?",".png?");val b=bitmap(fetch(u));try{drawCrop(canvas,b,x,y,sf)}finally{b.recycle()}};val valid=pixels(survey);for(i in mask.indices)if(Color.alpha(valid[i])>0)mask[i]=false}catch(e:Exception){if(Thread.currentThread().isInterrupted)throw e;return pixels(original)}finally{survey.recycle()}}
   val a=pixels(original);val b=pixels(crop);return IntArray(a.size){if(mask[it])b[it]else a[it]}
  }finally{crop.recycle();original?.recycle()}
 }
 private fun drawCrop(canvas:Canvas,bitmap:Bitmap,x:Int,y:Int,factor:Int){
  val src=RectF((x%factor)*256f/factor,(y%factor)*256f/factor,(x%factor+1)*256f/factor,(y%factor+1)*256f/factor)
  val matrix=Matrix().apply{setRectToRect(src,RectF(0f,0f,256f,256f),Matrix.ScaleToFit.FILL)}
  canvas.drawBitmap(bitmap,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
 }
 private fun vectorMask(meta:JSONObject,z:Int,x:Int,y:Int):BooleanArray = SourceGeometry.mask(points(meta.getJSONArray("outer")),z,x,y,meta.getInt("z"),meta.getInt("x"),meta.getInt("y"))
}
