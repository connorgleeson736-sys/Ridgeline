package nz.ridgeline.nativeapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only, grant-only GPX sharing. No provider access to routes DB, tiles or arbitrary files. */
class RouteShareProvider:ContentProvider(){
    override fun onCreate()=true
    private fun file(uri:Uri):File{
        if(uri.pathSegments.size!=2||uri.pathSegments[0]!="gpx"||!uri.lastPathSegment.orEmpty().matches(Regex("[a-f0-9-]{36}\\.gpx")))throw FileNotFoundException("Unknown shared route")
        val folder=File(requireNotNull(context).cacheDir,"route-share");val file=File(folder,uri.lastPathSegment!!)
        if(file.canonicalFile.parentFile!=folder.canonicalFile||!file.isFile)throw FileNotFoundException("Shared route is unavailable")
        return file
    }
    override fun getType(uri:Uri):String{file(uri);return "application/gpx+xml"}
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{if(mode!="r")throw FileNotFoundException("Read-only shared route");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)}
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor{
        val file=file(uri);val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        val title=uri.getQueryParameter("name")?.take(100)?:"Route.gpx"
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){OpenableColumns.DISPLAY_NAME->title;OpenableColumns.SIZE->file.length();else->null}})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("Read only")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read only")
}
