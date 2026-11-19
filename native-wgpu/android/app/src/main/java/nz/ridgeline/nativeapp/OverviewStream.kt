package nz.ridgeline.nativeapp

/** The overview imagery served online (tools/overviews/publish.py), so it needs no
 * download or attaching: each overview tile as its own file, named by its pack's
 * payload and its byte offset in it (byte for byte the packed image), and the six
 * original ZIPs for keeping all of it offline. [base] is map-defaults.json's
 * "overviewUrl"; blank turns streaming off. Pure Kotlin, unit tested. */
internal object OverviewStream {
    private val payload=Regex("[a-f0-9]{64}")
    /** Where the packed image at [offset] in payload [file] is served, or null. */
    fun tileUrl(base:String,file:String,offset:Long):String?{
        if(base.isBlank()||!file.matches(payload)||offset<0)return null
        return "${base.trimEnd('/')}/tiles/$file/$offset.webp"
    }
    /** The same tile with LINZ's shading already taken out (the website's
     * tools/correct-overview.py, quality 95): drawn as it is, no correction on the phone. */
    fun correctedUrl(base:String,file:String,offset:Long):String?=tileUrl(base,file,offset)?.replace("/tiles/","/ctiles-q95/")
    /** A whole WebP file: its RIFF header's length is the length received. */
    fun completeWebp(bytes:ByteArray):Boolean{
        if(bytes.size<20||String(bytes,0,4,Charsets.US_ASCII)!="RIFF"||String(bytes,8,4,Charsets.US_ASCII)!="WEBP")return false
        val riff=(bytes[4].toLong() and 0xff) or ((bytes[5].toLong() and 0xff) shl 8) or ((bytes[6].toLong() and 0xff) shl 16) or ((bytes[7].toLong() and 0xff) shl 24)
        return riff+8==bytes.size.toLong()
    }
    /** Where an original overview ZIP ([name] from overview-archives.json) is served, or null. */
    fun packUrl(base:String,name:String):String?{
        if(base.isBlank()||!name.matches(Regex("Ridgeline-Overviews-(NI-)?[0-9.]+-[0-9]+-of-[0-9]+\\.zip")))return null
        return "${base.trimEnd('/')}/packs/$name"
    }
    /** A served tile is used only when it is exactly the packed image's length. */
    fun complete(bytes:ByteArray,expected:Int)=bytes.size==expected
}
