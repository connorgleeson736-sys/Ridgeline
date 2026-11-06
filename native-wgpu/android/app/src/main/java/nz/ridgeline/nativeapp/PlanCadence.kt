package nz.ridgeline.nativeapp

/** Leading-edge throttle: idle demand starts immediately, movement stays bounded. */
internal class PlanCadence(private val intervalMs:Long=50) {
 private var lastStarted:Long?=null
 fun delay(now:Long):Long=lastStarted?.let{(intervalMs-(now-it)).coerceIn(0,intervalMs)}?:0
 fun started(now:Long){lastStarted=now}
}
