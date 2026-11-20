package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** Overlay downloads must survive the transport's request guard: a refused
 * request releases its consumer instead of leaking an overlay slot. */
class LayerLeaseTest {
    private class Never:TileBackend{
        override fun local(key:ResourceKey):TileBody?=null
        override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit={}
    }
    @Test fun aRefusedRequestReleasesItsConsumer(){
        val transport=TileTransport(Never(),1024)
        try{
            // Fill the transport to its 512-request guard (as a busy area does).
            val holders=(0 until 512).map{i->transport.consumer().also{it.acquire(ResourceKey("https://example.invalid/$i",false))}}
            val owned=transport.consumer()
            // The guard throws IllegalArgumentException, not IllegalStateException.
            assertThrows(IllegalArgumentException::class.java){owned.acquire(ResourceKey("https://example.invalid/extra",false))}
            val consumer=transport.consumer()
            assertNull(MapLayers.startLeased(consumer){consumer.acquire(ResourceKey("https://example.invalid/vector",false))})
            // Released: it can no longer be used, and holds nothing.
            assertThrows(IllegalStateException::class.java){consumer.acquire(ResourceKey("https://example.invalid/other",false))}
            holders.forEach{it.release(true)}
            // With room again, the same request starts.
            val again=transport.consumer()
            assertNotNull(MapLayers.startLeased(again){again.acquire(ResourceKey("https://example.invalid/vector",false))})
            again.release(true)
        }finally{transport.close()}
    }
}
