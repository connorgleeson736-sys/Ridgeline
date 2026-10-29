package nz.ridgeline.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Exact Float64 coordinates; no decimal truncation, polyline quantization or
 * repeated JSON serialization of every saved route during a drag/commit. */
object RouteBlob {
    private const val MAGIC=0x31524752 // RGR1 in little-endian byte order
    fun encode(points:List<Coordinate>):ByteArray {
        require(points.size<=2000&&points.all{it.inNewZealand()})
        return ByteBuffer.allocate(8+points.size*16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(MAGIC);putInt(points.size);for(p in points){putDouble(p.longitude);putDouble(p.latitude)}
        }.array()
    }
    fun decode(bytes:ByteArray):List<Coordinate> {
        require(bytes.size>=8){"Saved route is incomplete"}
        val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.int==MAGIC){"Unknown saved route format"};val count=input.int
        require(count in 0..2000&&bytes.size==8+count*16){"Invalid saved route length"}
        return List(count){Coordinate(input.double,input.double).also{require(it.inNewZealand())}}
    }
}
data class RouteSummary(val id:String,val name:String,val pointCount:Int,val lengthMeters:Double,val updatedMillis:Long)
data class SavedRoute(val summary:RouteSummary,val points:List<Coordinate>)
class RouteLimitException:IllegalStateException("50 route limit. Delete a route or export GPX to keep this route.")

/** Call from the controller's I/O executor. Separate application-private database.
 * WAL transactions change only one route; summaries never decode unused points. */
class RouteDatabase(context:Context,name:String="routes-v1.sqlite"):SQLiteOpenHelper(context.applicationContext,name,null,1) {
    init {setWriteAheadLoggingEnabled(true)}
    override fun onConfigure(db:SQLiteDatabase){db.execSQL("PRAGMA synchronous=FULL")}
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE routes (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL,
            coords BLOB NOT NULL,
            updated INTEGER NOT NULL,
            point_count INTEGER NOT NULL CHECK(point_count BETWEEN 0 AND 2000),
            length_m REAL NOT NULL CHECK(length_m>=0)
        )""")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){error("Explicit route schema migration required")}
    private val summaryColumns=arrayOf("id","name","point_count","length_m","updated")
    private fun summary(cursor:android.database.Cursor)=RouteSummary(cursor.getString(0),cursor.getString(1),cursor.getInt(2),cursor.getDouble(3),cursor.getLong(4))
    fun summaries():List<RouteSummary> = readableDatabase.query("routes",summaryColumns,null,null,null,null,"seq DESC").use { c ->buildList{while(c.moveToNext())add(summary(c))} }
    fun load(id:String):SavedRoute? = readableDatabase.query("routes",summaryColumns+"coords","id=?",arrayOf(id),null,null,null).use { c ->
        if(!c.moveToFirst())null else {val metadata=summary(c);val points=RouteBlob.decode(c.getBlob(5));check(metadata.pointCount==points.size);SavedRoute(metadata,points)}
    }
    @Synchronized fun save(id:String?,name:String,points:List<Coordinate>,now:Long=System.currentTimeMillis()):RouteSummary {
        val data=RouteBlob.encode(points)
        val title=name.trim().ifEmpty{"Untitled route"}.take(80)
        val db=writableDatabase;db.beginTransaction()
        try {
            val existing=id?.let { key -> db.query("routes",arrayOf("id"),"id=?",arrayOf(key),null,null,null).use{it.moveToFirst()} }==true
            if(!existing)db.rawQuery("SELECT COUNT(*) FROM routes",null).use {c->c.moveToFirst();if(c.getInt(0)>=50)throw RouteLimitException()}
            val key=id?:UUID.randomUUID().toString()
            val length=NativeGeo.length(points)
            val values=ContentValues().apply{put("id",key);put("name",title);put("coords",data);put("point_count",points.size);put("length_m",length);put("updated",now)}
            if(existing)check(db.update("routes",values,"id=?",arrayOf(key))==1) else check(db.insertOrThrow("routes",null,values)>0)
            db.setTransactionSuccessful()
            return RouteSummary(key,title,points.size,length,now)
        }finally {db.endTransaction()}
    }
    @Synchronized fun delete(id:String):Boolean = writableDatabase.delete("routes","id=?",arrayOf(id))==1
    @Synchronized fun rename(id:String,name:String,now:Long=System.currentTimeMillis()):Boolean {
        val title=name.trim().take(80);require(title.isNotEmpty()){"Enter a route name"}
        return writableDatabase.update("routes",ContentValues().apply{put("name",title);put("updated",now)},"id=?",arrayOf(id))==1
    }
}
