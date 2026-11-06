package nz.ridgeline.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.pow

/** Data-only port of the reference source chain. No JavaScript at runtime. */
internal class NativeImagery(
    private val context: Context,
    private val fetch: (String) -> ByteArray,
    private val linz: (Int, Int, Int) -> String,
    private val attachments:OverviewAttachments?=null,
    private val checkPreparation:()->Unit={},
    private val readLocal:(String,()->ByteArray?)->ByteArray?={_,read->read()},
    private val settings:()->MapSettings.State={MapSettings.State()},
) {
    private val catalog by lazy { json("overview-index.json") }
    private val atlas by lazy { json("gap-atlas.json") }
    private data class Meta(val data: JSONObject, val outer: List<List<SourceGeometry.Point>>,
                            val inner: List<List<SourceGeometry.Point>>, val seams: List<List<SourceGeometry.Point>>)
    private val metadata = ConcurrentHashMap<String, Meta>()
    private val payloadPattern=Regex("[a-f0-9]{64}")
    private fun json(name: String) = context.assets.open(name).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    private fun assetBytes(name:String)=context.assets.open(name.removePrefix("/")).use{it.readBytes()}
    private fun asset(name:String)=requireNotNull(readLocal("asset:$name"){assetBytes(name)})
    internal fun initialize(){catalog;atlas}
    /** Existing, original coarse asset covering the focus: no provider request
     * is needed to establish imagery in a completely new region. */
    internal fun bootstrap(z:Int,x:Int,y:Int):GpuUploadQueue.Id? {
        val rows=catalog.getJSONObject("coarse")
        return SourceDemand.coarseAsset(GpuUploadQueue.Id(z,x,y),rows::has)
    }
    private fun bitmap(bytes: ByteArray): Bitmap {
        checkPreparation()
        val options = BitmapFactory.Options().apply { inScaled=false; inPreferredConfig=Bitmap.Config.ARGB_8888 }
        val result = BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Imagery decode failed")
        if(result.width!=256 || result.height!=256) { result.recycle(); error("Unexpected imagery size") }
        return result
    }
    private inline fun <T> Bitmap.using(block:(Bitmap)->T):T = try { block(this) } finally { recycle() }
    private fun pixels(bitmap:Bitmap):IntArray {checkPreparation();return IntArray(65536).also { bitmap.getPixels(it,0,256,0,0,256,256) }}
    private val srgb=ColorSpace.get(ColorSpace.Named.SRGB)
    /** Premultiplied little-endian RGBA, identical to PixelPacking.rgbaOwned(pixels()).
     * An opaque sRGB ARGB_8888 bitmap already stores exactly those bytes (Android
     * packs it as R | G<<8 | B<<16 | A<<24, alpha 255): one native copy replaces the
     * 256 KiB int array, getPixels conversion and per-pixel Kotlin packing. Anything
     * with alpha, another colour space or row padding keeps the original path. */
    private fun rgba(bitmap:Bitmap):ByteBuffer {
        checkPreparation()
        if(!bitmap.hasAlpha()&&bitmap.config==Bitmap.Config.ARGB_8888&&bitmap.width==256&&bitmap.height==256&&bitmap.rowBytes==1024&&bitmap.colorSpace==srgb){
            val out=ByteBuffer.allocateDirect(262144).order(ByteOrder.LITTLE_ENDIAN)
            bitmap.copyPixelsToBuffer(out);check(!out.hasRemaining());out.rewind()
            return out
        }
        return PixelPacking.rgbaOwned(pixels(bitmap),checkPreparation)
    }
    internal fun saved(z:Int,x:Int,y:Int):ByteArray? {
        val key="$z/$x/$y"
        val variant=if(settings().natural)"natural"else"raw"
        catalog.getJSONObject("coarse").optJSONObject(key)?.let { return asset(it.getString(variant)) }
        val entry=catalog.getJSONObject("tiles").optJSONObject(key) ?: return null
        val row=entry.optJSONObject(variant) ?: entry
        val file=row.getString("file"); val offset=row.getLong("offset"); val length=row.getInt("bytes")
        require(file.matches(payloadPattern) && offset>=0 && length in 20..1048576)
        attachments?.read(file,offset,length)?.let{return it}
        val archive=catalog.getJSONObject("archives").optJSONObject(file) ?: return null
        val base=archive.getLong("offset");require(base>=0&&offset+length<=archive.getLong("bytes"))
        val descriptor=try { context.assets.openFd(archive.getString("asset")) } catch(_:FileNotFoundException) { return null }
        return descriptor.use { asset ->
            require(base+offset+length<=asset.length)
            asset.createInputStream().use { input ->
                val bytes=ByteArray(length); val target=ByteBuffer.wrap(bytes)
                while(target.hasRemaining()) {
                    val count=input.channel.read(target,asset.startOffset+base+offset+target.position())
                    check(count>0) { "Incomplete overview image" }
                }
                bytes
            }
        }
    }
    private fun base(z:Int,x:Int,y:Int):Bitmap {
        return bitmap((if(hasSaved(z,x,y)) readLocal("saved:$z/$x/$y"){saved(z,x,y)} else null) ?: fetch(linz(z,x,y)))
    }
    private fun rings(rows:JSONArray?):List<List<SourceGeometry.Point>> = if(rows==null) emptyList() else List(rows.length()) { i ->
        val ring=rows.getJSONArray(i)
        List(ring.length()) { j -> ring.getJSONArray(j).let { SourceGeometry.Point(it.getDouble(0),it.getDouble(1)) } }
    }
    private fun meta(key:String):Meta? {
        metadata[key]?.let { return it }
        val data=atlas.getJSONObject("entries").optJSONObject(key) ?: return null
        val parsed=Meta(data,rings(data.getJSONArray("outer")),rings(data.optJSONArray("inner")),rings(data.optJSONArray("seamRings")))
        return metadata.putIfAbsent(key,parsed) ?: parsed
    }
    private data class Plan(val factor:Double,val coarse:JSONObject?,val entry:Meta?,val mode:String)
    private val plans=LinkedHashMap<String,Plan>(16,.75f,true)
    private fun plan(z:Int,x:Int,y:Int):Plan {
        val key="$z/$x/$y";synchronized(plans){plans[key]?.let{return it}}
        val az=atlas.getInt("z")
        val coarse=if(z<az) atlas.getJSONObject("coarse").optJSONObject("$z/$x/$y") else null
        val factor=2.0.pow(z-az)
        val entry=if(z>=az) meta("${floor(x/factor).toInt()}/${floor(y/factor).toInt()}") else null
        val mode=when {
            coarse!=null -> if(coarse.optBoolean("full")) "esri" else "boundary"
            entry==null -> "linz"
            else -> entry.data.let { m -> SourceGeometry.mode(z,x,y,m.getInt("z"),m.getInt("x"),m.getInt("y"),m.getInt("width"),m.getInt("height"),entry.outer,entry.inner,entry.seams,m.optBoolean("vector")) }
        }
        return Plan(factor,coarse,entry,mode).also{synchronized(plans){plans[key]=it;while(plans.size>512)plans.remove(plans.keys.first())}}
    }
    fun composed(z:Int,x:Int,y:Int):Boolean=settings().base==MapSettings.Base.LINZ&&plan(z,x,y).let{it.mode=="boundary"||(z>18&&it.mode!="linz")}
    private val bundled=ConcurrentHashMap<String,Boolean>()
    private fun hasSaved(z:Int,x:Int,y:Int):Boolean{
        if(z>12)return false
        val key="$z/$x/$y";if(catalog.getJSONObject("coarse").has(key))return true
        val entry=catalog.getJSONObject("tiles").optJSONObject(key)?:return false
        val row=entry.optJSONObject("natural")?:entry;val file=row.getString("file")
        if(attachments?.contains(file)==true)return true
        val archive=catalog.getJSONObject("archives").optJSONObject(file)?:return false
        return bundled.getOrPut(file){try{context.assets.openFd(archive.getString("asset")).use{true}}catch(_:FileNotFoundException){false}}
    }
    /** Discover all independent dependencies before suspending a CPU task. */
    internal data class LocalInput(val key:String,val read:()->ByteArray?)
    internal fun localInputs(z:Int,x:Int,y:Int):List<LocalInput>{
        if(settings().base!=MapSettings.Base.LINZ)return emptyList()
        val p=plan(z,x,y);val inputs=ArrayList<LocalInput>(2)
        if((p.mode=="linz"||p.mode=="boundary")&&hasSaved(z,x,y))inputs+=LocalInput("saved:$z/$x/$y"){saved(z,x,y)}
        p.coarse?.let{val name=it.getString("url");inputs+=LocalInput("asset:$name"){assetBytes(name)}}
        return inputs
    }
    fun inputs(z:Int,x:Int,y:Int):List<String>{
        alternate(z,x,y)?.let{return listOf(it)}
        val p=plan(z,x,y);val urls=ArrayList<String>()
        if(p.mode=="linz"||p.mode=="boundary")if(!hasSaved(z,x,y))urls+=linz(z,x,y)
        if(p.mode!="linz"&&p.coarse==null){val ez=minOf(z,18);val f=1 shl(z-ez)
            urls+="https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$ez/${y/f}/${x/f}"
            val entry=p.entry
            if(p.mode=="boundary"&&entry!=null){val m=entry.data;val size=256/p.factor;val px=x*size-m.getInt("x")*256;val py=y*size-m.getInt("y")*256
                if(entry.seams.isNotEmpty()&&SourceGeometry.relation(entry.seams,doubleArrayOf(px-2,py-2,px+size+2,py+size+2))!=-1){
                    val fine=m.optJSONArray("fineSources")
                    if(fine!=null)for(i in 0 until fine.length()){val row=fine.getJSONObject(i);val fz=minOf(z,row.optInt("maxZoom",19),19);val factor=1 shl(z-fz)
                        urls+=SurveyMasks.url(linz(fz,x/factor,y/factor),row.getString("name"))}
                }
            }
        }
        return urls.distinct()
    }
    fun load(z:Int,x:Int,y:Int):IntArray=produce(z,x,y,::pixels){it}
    /** The prepared upload payload. Same source chain and pixels as load(). */
    fun loadRgba(z:Int,x:Int,y:Int):ByteBuffer=produce(z,x,y,::rgba){PixelPacking.rgbaOwned(it,checkPreparation)}
    private fun <T> produce(z:Int,x:Int,y:Int,fromBitmap:(Bitmap)->T,fromPixels:(IntArray)->T):T {
        require(z in 0..19 && x in 0 until (1 shl z) && y in 0 until (1 shl z))
        checkPreparation()
        alternate(z,x,y)?.let{url->return bitmap(fetch(url)).using{source->if(z<=18)fromBitmap(source)else crop(source,x,y,1 shl(z-18)).using(fromBitmap)}}
        val selected=plan(z,x,y);val factor=selected.factor;val coarse=selected.coarse;val entry=selected.entry;val mode=selected.mode
        if(mode=="linz") return base(z,x,y).using(fromBitmap)
        val ez=minOf(z,18); val f=1 shl(z-ez)
        val source=if(coarse!=null) asset(coarse.getString("url")) else fetch("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$ez/${y/f}/${x/f}")
        return bitmap(source).using esriScope@{ esri ->
            if(mode=="esri" && (coarse!=null || z==ez)) return@esriScope fromBitmap(esri)
            crop(esri,x,y,if(coarse!=null)1 else f).using cropScope@{ cropped ->
                if(mode!="boundary") return@cropScope fromBitmap(cropped)
                base(z,x,y).using originalScope@{ original ->
                    if(coarse!=null) {
                        original.copy(Bitmap.Config.ARGB_8888,true).using { result ->
                            Canvas(result).drawBitmap(cropped,0f,0f,null);fromBitmap(result)
                        }
                    } else {
                        val m=entry!!.data
                        checkPreparation()
                        val mask=SourceGeometry.mask(entry.outer,z,x,y,m.getInt("z"),m.getInt("x"),m.getInt("y"))
                        val size=256/factor; val px=x*size-m.getInt("x")*256; val py=y*size-m.getInt("y")*256
                        val onSeam=entry.seams.isNotEmpty() && SourceGeometry.relation(entry.seams,doubleArrayOf(px-2,py-2,px+size+2,py+size+2))!=-1
                        val fine=m.optJSONArray("fineSources")
                        // Survey alpha is part of the final source decision. A
                        // failed donor must keep the retained parent visible and
                        // retry, rather than certify/cache LINZ-only pixels as a
                        // complete border tile with no dependency on that donor.
                        if(onSeam && fine!=null) vetoFine(mask,fine,z,x,y)
                        val a=pixels(original); val b=pixels(cropped)
                        fromPixels(IntArray(a.size) { if(mask[it]) b[it] else a[it] })
                    }
                }
            }
        }
    }
    private fun alternate(z:Int,x:Int,y:Int):String?{
        val mode=settings().base;if(mode==MapSettings.Base.LINZ)return null
        val ez=minOf(z,18);val factor=1 shl(z-ez)
        return if(mode==MapSettings.Base.TOPO)linz(ez,x/factor,y/factor).replace("/aerial/","/topo-raster/")else"https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$ez/${y/factor}/${x/factor}"
    }
    private fun vetoFine(mask:BooleanArray,fine:JSONArray,z:Int,x:Int,y:Int) {
        Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).using { survey ->
            val canvas=Canvas(survey)
            for(i in 0 until fine.length()) {
                checkPreparation()
                val row=fine.getJSONObject(i); val name=row.getString("name")
                val fz=minOf(z,row.optInt("maxZoom",19),19); val f=1 shl(z-fz)
                val bytes=SurveyMasks.fetch(linz(fz,x/f,y/f),name,fetch,checkPreparation)
                bitmap(bytes).using { drawCrop(canvas,it,x,y,f) }
            }
            val valid=pixels(survey)
            for(i in mask.indices) if(Color.alpha(valid[i])>0) mask[i]=false
        }
    }
    private fun crop(bitmap:Bitmap,x:Int,y:Int,factor:Int):Bitmap {
        checkPreparation()
        val result=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        try { drawCrop(Canvas(result),bitmap,x,y,factor); return result }
        catch(e:Exception) { result.recycle();throw e }
    }
    private fun drawCrop(canvas:Canvas,bitmap:Bitmap,x:Int,y:Int,factor:Int) {
        val src=RectF((x%factor)*256f/factor,(y%factor)*256f/factor,(x%factor+1)*256f/factor,(y%factor+1)*256f/factor)
        val matrix=Matrix().apply { setRectToRect(src,RectF(0f,0f,256f,256f),Matrix.ScaleToFit.FILL) }
        canvas.drawBitmap(bitmap,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
