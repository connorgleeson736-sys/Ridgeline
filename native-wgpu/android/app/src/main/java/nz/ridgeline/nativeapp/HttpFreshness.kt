package nz.ridgeline.nativeapp
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
internal object HttpFreshness {
    private fun date(value:String?):Long?=try{value?.let{SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US).apply{timeZone=TimeZone.getTimeZone("GMT");isLenient=false}.parse(it)?.time}}catch(_:Exception){null}
    fun deadline(response:Response,now:Long=System.currentTimeMillis()):Long {
        val policy=response.cacheControl
        if(policy.noCache||policy.noStore)return 0
        val received=response.receivedResponseAtMillis;val sent=response.sentRequestAtMillis
        val serverDate=date(response.header("Date"))?:received
        val lifetime=if(policy.maxAgeSeconds>=0)policy.maxAgeSeconds*1000L else date(response.header("Expires"))?.minus(serverDate)?:return 0
        val apparent=(received-serverDate).coerceAtLeast(0)
        val age=response.header("Age")?.toLongOrNull()?.coerceIn(0,Int.MAX_VALUE.toLong())?.times(1000)?:0
        val corrected=maxOf(apparent,age+(received-sent).coerceAtLeast(0))
        val current=corrected+(now-received).coerceAtLeast(0)
        return now+(lifetime-current).coerceAtLeast(0)
    }
}
