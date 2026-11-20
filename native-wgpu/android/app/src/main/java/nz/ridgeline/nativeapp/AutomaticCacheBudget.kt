package nz.ridgeline.nativeapp

/** One total disposable disk allowance; saved regions and attached ZIPs are separate. */
internal object AutomaticCacheBudget {
    fun bytes(mib:Int,freeBytes:Long):Long = minOf(mib.coerceIn(0,1000)*1_048_576L,(freeBytes.coerceAtLeast(0)/8).coerceAtLeast(4_194_304L))
}
