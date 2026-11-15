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
    /** Where an original overview ZIP ([name] from overview-archives.json) is served, or null. */
    fun packUrl(base:String,name:String):String?{
        if(base.isBlank()||!name.matches(Regex("Ridgeline-Overviews-(NI-)?[0-9.]+-[0-9]+-of-[0-9]+\\.zip")))return null
        return "${base.trimEnd('/')}/packs/$name"
    }
    /** A served tile is used only when it is exactly the packed image's length. */
    fun complete(bytes:ByteArray,expected:Int)=bytes.size==expected
}
