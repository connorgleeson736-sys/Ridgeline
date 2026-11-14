package nz.ridgeline.nativeapp

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Current Capacitor GPX semantics: 2MiB, one continuous track/route, 2..2000 NZ points. */
object GpxCodec {
    data class Imported(val name:String,val points:List<Coordinate>)
    fun decode(stream:java.io.InputStream,fileName:String):Imported {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=stream.read(buffer);if(count<0)break;require(out.size()+count<=2*1024*1024){"Choose a GPX file smaller than 2 MB."};out.write(buffer,0,count)}
        return decode(out.toByteArray(),fileName)
    }
    fun decode(bytes:ByteArray,fileName:String):Imported {
        require(bytes.size<=2*1024*1024){"Choose a GPX file smaller than 2 MB."}
        // GPX does not require DTDs/entities; block declarations before parsing,
        // including UTF16 input, and forbid external resolution on every platform.
        val declarationProbe=bytes.toString(StandardCharsets.ISO_8859_1).replace("\u0000","")
        require(!declarationProbe.contains("<!DOCTYPE")&&!declarationProbe.contains("<!ENTITY")){"GPX document declarations are not supported."}
        val factory=DocumentBuilderFactory.newInstance().apply{
            isNamespaceAware=true;isExpandEntityReferences=false
            runCatching{setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true)}
        }
        val builder=factory.newDocumentBuilder().apply{
            setEntityResolver{_,_->InputSource(java.io.StringReader(""))}
            setErrorHandler(object:DefaultHandler(){override fun error(e:SAXParseException){throw e};override fun fatalError(e:SAXParseException){throw e}})
        }
        val xml=try{builder.parse(ByteArrayInputStream(bytes))}catch(_:Exception){throw IllegalArgumentException("This GPX file could not be read.")}
        fun find(tag:String)=xml.getElementsByTagNameNS("*",tag)
        val segments=find("trkseg");require(segments.length<=1){"Import one continuous track segment to avoid connecting separate routes."}
        require(segments.length>0||find("rte").length<=1){"Import one route at a time."}
        var elements=find("trkpt");if(elements.length==0)elements=find("rtept");if(elements.length==0)elements=find("wpt")
        require(elements.length>=2){"The GPX needs at least two points."};require(elements.length<=2000){"This prototype accepts up to 2,000 GPX points."}
        val points=try{List(elements.length){i->val e=elements.item(i)as Element;require(e.hasAttribute("lon")&&e.hasAttribute("lat"));Coordinate(e.getAttribute("lon").trim().toDouble(),e.getAttribute("lat").trim().toDouble())}}
            catch(_:Exception){throw IllegalArgumentException("The GPX contains invalid coordinates.")}
        require(points.all{it.inNewZealand()}){"Choose a route on New Zealand’s main islands."}
        val name=(find("name").item(0)?.textContent?.takeIf{it.isNotEmpty()}?:fileName.replace(Regex("\\.gpx$",RegexOption.IGNORE_CASE),"")).take(80)
        return Imported(name,points)
    }
    private fun escape(value:String)=buildString{for(c in value)append(when(c){'<'->"&lt;";'>'->"&gt;";'&'->"&amp;";'"'->"&quot;";'\''->"&apos;";else->c.toString()})}
    fun encode(name:String,points:List<Coordinate>):ByteArray {
        require(points.size in 2..2000)
        val title=escape(name.trim().ifEmpty{"Mountain route"})
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Ridgeline Prototype\" xmlns=\"http://www.topografix.com/GPX/1/1\"><metadata><name>")
            append(title);append("</name><desc>User-drawn sketch. Unverified direct connections between waypoints.</desc></metadata><trk><name>");append(title);append("</name><trkseg>")
            for(p in points)append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\"/>")
            append("</trkseg></trk></gpx>")
        }.toByteArray(StandardCharsets.UTF_8)
    }
    fun exportName(name:String)=(name.trim().ifEmpty{"Mountain route"}.replace(Regex("[^a-zA-Z0-9_-]+"),"-").take(60).ifEmpty{"route"})+".gpx"
}
