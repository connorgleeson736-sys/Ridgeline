package nz.ridgeline.nativeapp

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One large file downloaded to [file] through [file].part, resuming a partial
 * download where the server allows it, and kept only when it is exactly [size]
 * bytes (an original overview ZIP; its SHA-256 is checked when attached). */
internal object OverviewDownload {
    fun fetch(client:OkHttpClient,url:String,file:File,size:Long,cancelled:()->Boolean={false},progress:(Long)->Unit={}){
        if(file.exists()&&file.length()==size)return
        val part=File(file.parentFile,file.name+".part")
        var have=if(part.exists())part.length() else 0L
        if(have>size){part.delete();have=0}
        if(have<size){
            val request=Request.Builder().url(url).apply{if(have>0)header("Range","bytes=$have-")}.build()
            client.newCall(request).execute().use{response->
                if(!response.isSuccessful)throw IOException("Download failed (HTTP ${response.code})")
                // A server that ignores the range sends the whole file again: start over.
                val append=have>0&&response.code==206
                if(!append)have=0
                FileOutputStream(part,append).use{out->response.body.byteStream().use{input->
                    val buffer=ByteArray(256*1024)
                    while(true){
                        if(cancelled())throw IOException("Download cancelled")
                        val n=input.read(buffer);if(n<0)break
                        have+=n;if(have>size)throw IOException("Download is larger than expected")
                        out.write(buffer,0,n);progress(have)
                    }
                }}
            }
        }
        if(part.length()!=size)throw IOException("Download incomplete")
        if(!part.renameTo(file))throw IOException("Could not save the download")
    }
}
