package nz.ridgeline.app.model

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.SAXParserFactory

object Gpx {
    const val MAX_BYTES = 4 * 1024 * 1024
    /** Import segments as separate routes; never join disconnected tracks with invented straight lines. */
    fun read(input: InputStream): List<Route> {
        val bytes = input.readBounded(MAX_BYTES)
        require(bytes.size <= MAX_BYTES) { "GPX file is larger than 4 MiB" }
        // Android SAX implementations vary in feature support. Reject declarations before parsing,
        // then also disable external entities on the parser. GPX never needs a DTD.
        val header = bytes.toString(Charsets.UTF_8)
        require(!header.contains('\u0000') && !header.contains("<!DOCTYPE", true) && !header.contains("<!ENTITY", true)) { "GPX declarations are not supported" }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val result = mutableListOf<Route>()
        val handler = object : DefaultHandler() {
            var points = mutableListOf<Coordinate>()
            var name = "Imported route"
            var group: String? = null
            var nameText: StringBuilder? = null
            var total = 0
            var rootSeen = false
            val elements = ArrayDeque<String>()
            override fun startElement(uri: String?, localName: String, qName: String?, attrs: Attributes) {
                if (!rootSeen) { require(localName == "gpx") { "Not a GPX document" }; rootSeen = true }
                when (localName) {
                    "trk", "rte" -> { name = "Imported route" }
                }
                if (localName == "trkseg" || localName == "rte") {
                    require(group == null) { "Nested GPX segments are invalid" }
                    group = localName; points = mutableListOf()
                }
                if (localName == "name" && elements.lastOrNull() in listOf("trk","rte")) nameText = StringBuilder()
                elements.addLast(localName)
                if ((localName == "trkpt" && group == "trkseg") || (localName == "rtept" && group == "rte")) {
                    require(++total <= Route.MAX_POINTS) { "GPX supports up to ${Route.MAX_POINTS} points per import" }
                    points.add(Coordinate(attrs.getValue("lon").toDouble(), attrs.getValue("lat").toDouble()))
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                nameText?.let { if (it.length < 200) it.append(ch, start, minOf(length, 200 - it.length)) }
            }
            override fun endElement(uri: String?, localName: String, qName: String?) {
                if (localName == "name") {
                    nameText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { name = it.take(80) }
                    nameText = null
                }
                elements.removeLast()
                if (localName == group) {
                    if (points.isNotEmpty()) result.add(Route(name = name, points = points.toList()))
                    group = null
                }
            }
        }
        factory.newSAXParser().parse(bytes.inputStream(), handler)
        require(result.isNotEmpty()) { "No track or route points found in GPX" }
        return result
    }

    fun write(route: Route, output: OutputStream) {
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Ridgeline Android\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>")
            append(escape(route.name)); append("</name><trkseg>")
            route.points.forEach { append("<trkpt lat=\"${it.latitude}\" lon=\"${it.longitude}\"/>") }
            append("</trkseg></trk></gpx>\n")
        }
        output.write(xml.toByteArray(Charsets.UTF_8))
    }
}
