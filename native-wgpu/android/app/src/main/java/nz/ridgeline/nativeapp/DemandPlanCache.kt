package nz.ridgeline.nativeapp

data class DemandPlanKey(val width:Int,val height:Int,val pose:CameraPose)

/** One immutable plan, scoped to one loader/scheduler. Residency is deliberately not cached. */
class DemandPlanCache<K:Any,V:Any> {
    private var key:K?=null
    private var value:V?=null
    fun get(input:K,build:()->V):V {
        if(key==input)return value!!
        val next=build() // A failed build must never publish the new key with stale data.
        key=input;value=next
        return next
    }
}
