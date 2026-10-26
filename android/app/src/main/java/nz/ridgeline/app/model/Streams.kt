package nz.ridgeline.app.model

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** API 26 compatible bounded read; never trusts a remote Content-Length. */
fun InputStream.readBounded(limit: Int): ByteArray {
    val output=ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(output.size()<=limit) {
        val count=read(buffer,0,minOf(buffer.size,limit+1-output.size()))
        if(count<0)break
        if(count==0)continue
        output.write(buffer,0,count)
    }
    return output.toByteArray()
}
