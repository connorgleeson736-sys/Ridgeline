package nz.ridgeline.nativeapp

import java.io.*
import java.util.concurrent.ConcurrentHashMap

/** Imagery tiles in the automatic disk cache, recorded as they arrive and
 * saved to one small file. "Show stored map detail" reads this instead of
 * opening every cache entry (thousands of files: about 10 s on slow storage). */
internal class StoredTileIndex(private val file:File) {
    private val zooms=Array(CacheCoverage.MAX_ZOOM+1){ConcurrentHashMap.newKeySet<Long>()}
    @Volatile private var dirty=false
    private fun pack(x:Int,y:Int)=(x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    fun add(url:String){val (z,x,y)=CacheCoverage.parse(url)?:return;if(z in zooms.indices&&zooms[z].add(pack(x,y)))dirty=true}
    val size get()=zooms.sumOf{it.size}
    fun copyInto(coverage:CacheCoverage){for(z in zooms.indices)for(k in zooms[z])coverage.addTile(z,(k ushr 32).toInt(),(k and 0xffffffffL).toInt())}
    /** Replace everything with a full scan of the cache's URLs. */
    fun replace(urls:Iterator<String>){
        val fresh=Array(zooms.size){HashSet<Long>()}
        while(urls.hasNext()){val (z,x,y)=CacheCoverage.parse(urls.next())?:continue;if(z in fresh.indices)fresh[z].add(pack(x,y))}
        for(z in zooms.indices){zooms[z].retainAll(fresh[z]);zooms[z].addAll(fresh[z])}
        dirty=true
    }
    fun load(){
        runCatching{DataInputStream(BufferedInputStream(FileInputStream(file))).use{input->
            if(input.readInt()!=MAGIC)return
            for(z in zooms.indices){val n=input.readInt();require(n in 0..MAX_ENTRIES);repeat(n){zooms[z].add(input.readLong())}}
        }}
    }
    fun save(){
        if(!dirty)return;dirty=false
        runCatching{
            val tmp=File(file.path+".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use{out->
                out.writeInt(MAGIC);for(set in zooms){val rows=set.toList();out.writeInt(rows.size);for(k in rows)out.writeLong(k)}
            }
            if(!tmp.renameTo(file)){file.delete();tmp.renameTo(file)}
        }.onFailure{dirty=true}
    }
    private companion object{const val MAGIC=0x52534931;const val MAX_ENTRIES=4_000_000}
}
