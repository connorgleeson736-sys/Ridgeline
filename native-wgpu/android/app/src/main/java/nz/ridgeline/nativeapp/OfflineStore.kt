package nz.ridgeline.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Explicit offline downloads are durable and separate from the evictable HTTP cache. */
class OfflineStore(context:Context,name:String="offline-v1.sqlite"):SQLiteOpenHelper(context.applicationContext,name,null,2){
    data class Metadata(val bounds:TileLoader.Bounds,val zoom:Int,val basemap:String)
    data class Region(val id:String,val name:String,val status:String,val bytes:Long,val resources:Int,val metadata:Metadata?=null)
    private fun encode(meta:Metadata)=org.json.JSONObject().put("west",meta.bounds.west).put("south",meta.bounds.south).put("east",meta.bounds.east).put("north",meta.bounds.north).put("zoom",meta.zoom).put("basemap",meta.basemap).toString()
    private fun decode(text:String?):Metadata?=runCatching{val j=org.json.JSONObject(text?:return null);val bounds=TileLoader.Bounds(j.getDouble("west"),j.getDouble("south"),j.getDouble("east"),j.getDouble("north"));require(bounds.west>=165&&bounds.east<=180&&bounds.south>=-49&&bounds.north<=-33&&bounds.west<bounds.east&&bounds.south<bounds.north);Metadata(bounds,j.getInt("zoom").coerceIn(8,19),j.optString("basemap","LINZ"))}.getOrNull()
    private val resourceState=AtomicInteger(-1)
    private val readHandle=PublishedReadHandle(this,{readableDatabase},{!it.isReadOnly})
    init{setWriteAheadLoggingEnabled(true)}
    private fun hasResources():Boolean {
        val known=resourceState.get()
        if(known>=0)return known==1 // WAL readers must not take the writer's application monitor.
        val found=readHandle.get().rawQuery("SELECT 1 FROM resources LIMIT 1",null).use{if(it.moveToFirst())1 else 0}
        // Never overwrite a writer's committed availability with an older WAL snapshot.
        resourceState.compareAndSet(-1,found)
        return resourceState.get()==1
    }
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE regions(id TEXT PRIMARY KEY,name TEXT NOT NULL,status TEXT NOT NULL,metadata TEXT)")
        db.execSQL("CREATE TABLE resources(key TEXT PRIMARY KEY,body BLOB NOT NULL)")
        db.execSQL("CREATE TABLE membership(region TEXT NOT NULL,key TEXT NOT NULL,PRIMARY KEY(region,key))")
        db.execSQL("CREATE INDEX membership_key ON membership(key)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){check(old==1&&new==2){"Unsupported offline schema"};db.execSQL("ALTER TABLE regions ADD COLUMN metadata TEXT")}
    private fun key(url:String):String {
        val digest=MessageDigest.getInstance("SHA-256").digest(url.toByteArray());val hex="0123456789abcdef"
        return buildString(64){for(byte in digest){val n=byte.toInt() and 255;append(hex[n ushr 4]);append(hex[n and 15])}}
    }
    override fun onOpen(db:SQLiteDatabase){super.onOpen(db);if(!db.isReadOnly)db.execSQL("UPDATE regions SET status='Partial · interrupted' WHERE status='Downloading'")}

    fun read(url:String):ByteArray?{
        if(resourceState.get()==0||!hasResources())return null
        return readHandle.get().query("resources",arrayOf("body"),"key=?",arrayOf(key(url)),null,null,null).use{if(it.moveToFirst())it.getBlob(0)else null}
    }
    @Synchronized fun begin(name:String,metadata:Metadata?=null):String {
        val id=UUID.randomUUID().toString();val db=writableDatabase;readHandle.publish(db);db.insertOrThrow("regions",null,ContentValues().apply{put("id",id);put("name",name);put("status","Downloading");if(metadata!=null)put("metadata",encode(metadata))});return id
    }
    @Synchronized fun keep(region:String,url:String,body:ByteArray){
        val key=key(url);val db=writableDatabase;readHandle.publish(db);db.beginTransaction()
        try{
            db.insertWithOnConflict("resources",null,ContentValues().apply{put("key",key);put("body",body)},SQLiteDatabase.CONFLICT_IGNORE)
            db.insertWithOnConflict("membership",null,ContentValues().apply{put("region",region);put("key",key)},SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        resourceState.set(1) // Publish only after the durable transaction has committed.
    }
    @Synchronized fun status(id:String,status:String){writableDatabase.update("regions",ContentValues().apply{put("status",status)},"id=?",arrayOf(id))}
    fun regions():List<Region> = readHandle.get().rawQuery("SELECT r.id,r.name,r.status,COALESCE(SUM(length(b.body)),0),COUNT(m.key),r.metadata FROM regions r LEFT JOIN membership m ON m.region=r.id LEFT JOIN resources b ON b.key=m.key GROUP BY r.id ORDER BY r.rowid DESC",null).use{c->buildList{while(c.moveToNext())add(Region(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4),decode(c.getString(5))))}}
    @Synchronized fun delete(id:String){val db=writableDatabase;db.beginTransaction();try{db.delete("membership","region=?",arrayOf(id));db.delete("regions","id=?",arrayOf(id));db.execSQL("DELETE FROM resources WHERE NOT EXISTS(SELECT 1 FROM membership m WHERE m.key=resources.key)");db.setTransactionSuccessful()}finally{db.endTransaction()}}
    @Synchronized override fun close(){readHandle.clear();super.close()}
}
