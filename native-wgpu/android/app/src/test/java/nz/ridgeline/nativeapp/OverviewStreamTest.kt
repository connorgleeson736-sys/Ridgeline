package nz.ridgeline.nativeapp

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files

class OverviewStreamTest{
    private val payload="7241afa52abbc95e0226d1f1964832e1646076857c9b489f52443ecc9c2b8b03"
    @Test fun tilesAreNamedByPayloadAndOffset(){
        assertEquals("https://packs.example.nz/tiles/$payload/57197784.webp",OverviewStream.tileUrl("https://packs.example.nz/",payload,57197784))
        assertNull(OverviewStream.tileUrl("",payload,0));assertNull(OverviewStream.tileUrl("https://p",payload.take(10),0));assertNull(OverviewStream.tileUrl("https://p",payload,-1))
        assertEquals("https://p/packs/Ridgeline-Overviews-0.3.0-1-of-6.zip",OverviewStream.packUrl("https://p","Ridgeline-Overviews-0.3.0-1-of-6.zip"))
        assertNull(OverviewStream.packUrl("https://p","../secret.zip"))
        assertTrue(OverviewStream.complete(ByteArray(4270),4270));assertFalse(OverviewStream.complete(ByteArray(4000),4270))
    }
    /** Streaming is off unless map-defaults.json names where the imagery is served. */
    @Test fun shippedSettingsKeepStreamingOffUntilConfigured(){
        val defaults=JSONObject(File("src/main/assets/map-defaults.json").readText())
        assertTrue(defaults.has("overviewUrl"))
        val base=defaults.getString("overviewUrl")
        assertTrue(base.isEmpty()||base.startsWith("https://"))
    }
    /** Every overview tile has a place online, one file per packed image. */
    @Test fun everyIndexedTileMapsToItsOwnFile(){
        val index=JSONObject(File("src/main/assets/overview-index.json").readText());val tiles=index.getJSONObject("tiles")
        val urls=HashMap<String,Int>()
        for(key in tiles.keys()){val e=tiles.getJSONObject(key)
            for(v in listOf("natural","raw")){val r=e.optJSONObject(v)?:e
                val u=OverviewStream.tileUrl("https://p",r.getString("file"),r.getLong("offset"))!!
                val prior=urls.put(u,r.getInt("bytes"));assertTrue(prior==null||prior==r.getInt("bytes"))}}
        assertEquals(5255,tiles.length());assertTrue(urls.size in 5255..10510)
    }
    private fun server(body:ByteArray,honourRange:Boolean,seen:MutableList<String?>):MockWebServer{
        val s=MockWebServer()
        s.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            val range=request.headers["Range"];seen.add(range)
            val from=range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            return if(honourRange&&from!=null)MockResponse.Builder().code(206).body(okio.Buffer().write(body,from,body.size-from)).build()
                else MockResponse.Builder().code(200).body(okio.Buffer().write(body)).build()}}
        s.start(InetAddress.getByName("127.0.0.1"),0);return s
    }
    private val client=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val body=ByteArray(700_000){(it*31%251).toByte()}
    @Test fun downloadsAFileWhole(){
        val dir=Files.createTempDirectory("ov").toFile();val seen=ArrayList<String?>()
        server(body,true,seen).use{s->OverviewDownload.fetch(client,s.url("/packs/a.zip").toString(),File(dir,"a.zip"),body.size.toLong())}
        assertArrayEquals(body,File(dir,"a.zip").readBytes());assertEquals(listOf<String?>(null),seen);assertFalse(File(dir,"a.zip.part").exists())
    }
    @Test fun resumesAPartialDownload(){
        val dir=Files.createTempDirectory("ov").toFile();val seen=ArrayList<String?>()
        File(dir,"a.zip.part").writeBytes(body.copyOf(300_000))
        server(body,true,seen).use{s->OverviewDownload.fetch(client,s.url("/packs/a.zip").toString(),File(dir,"a.zip"),body.size.toLong())}
        assertArrayEquals(body,File(dir,"a.zip").readBytes());assertEquals(listOf<String?>("bytes=300000-"),seen)
    }
    @Test fun startsOverWhenTheServerIgnoresTheRange(){
        val dir=Files.createTempDirectory("ov").toFile();val seen=ArrayList<String?>()
        File(dir,"a.zip.part").writeBytes(body.copyOf(300_000))
        server(body,false,seen).use{s->OverviewDownload.fetch(client,s.url("/packs/a.zip").toString(),File(dir,"a.zip"),body.size.toLong())}
        assertArrayEquals(body,File(dir,"a.zip").readBytes())
    }
    @Test fun refusesAFileOfTheWrongSize(){
        val dir=Files.createTempDirectory("ov").toFile();val seen=ArrayList<String?>()
        server(body,true,seen).use{s->
            try{OverviewDownload.fetch(client,s.url("/packs/a.zip").toString(),File(dir,"a.zip"),body.size-1L);fail()}catch(_:IOException){}
            try{OverviewDownload.fetch(client,s.url("/packs/b.zip").toString(),File(dir,"b.zip"),body.size+1L);fail()}catch(_:IOException){}
        }
        assertFalse(File(dir,"a.zip").exists());assertFalse(File(dir,"b.zip").exists())
    }
}
