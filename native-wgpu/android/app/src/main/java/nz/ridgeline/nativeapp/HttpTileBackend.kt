package nz.ridgeline.nativeapp
import okhttp3.*
import java.io.IOException

/** The local path is explicitly cache-only. It never waits on a network permit. */
internal class HttpTileBackend(private val client:OkHttpClient,private val offlineRead:(String)->ByteArray?):TileBackend {
    override fun local(key:ResourceKey):TileBody?{
        offlineRead(key.url)?.let{return TileBody(it,200,Long.MAX_VALUE)}
        val policy=if(key.offline)CacheControl.FORCE_CACHE else CacheControl.Builder().onlyIfCached().build()
        client.newCall(Request.Builder().url(key.url).header("User-Agent","Ridgeline-Native/0.1.0").cacheControl(policy).build()).execute().use{response->
            if(response.code==504)return null
            return read(response,key.offline)
        }
    }
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{
        val call=client.newCall(Request.Builder().url(key.url).header("User-Agent","Ridgeline-Native/0.1.0").build())
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){done(Result.failure(e))}
            override fun onResponse(call:Call,response:Response){response.use{done(runCatching{read(it,false)})}}
        });return{call.cancel()}
    }
    private fun read(response:Response,cacheOnly:Boolean):TileBody{
        if(!response.isSuccessful&&response.code!=404)throw TileHttpException(response.code)
        val body=response.body?:throw IOException("Empty tile");require(body.contentLength()<=8*1024*1024)
        val source=body.source();val sink=okio.Buffer()
        while(true){val remaining=8L*1024*1024+1-sink.size;check(remaining>0){"Oversized tile"};if(source.read(sink,minOf(8192L,remaining))==-1L)break}
        require(sink.size<=8*1024*1024)
        return TileBody(sink.readByteArray(),response.code,if(cacheOnly)Long.MAX_VALUE else HttpFreshness.deadline(response))
    }
}
