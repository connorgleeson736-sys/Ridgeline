package nz.ridgeline.nativeapp

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceGeometryTest {
    private fun rings(rows: JSONArray?) = if (rows == null) emptyList() else List(rows.length()) { i ->
        val ring = rows.getJSONArray(i)
        List(ring.length()) { j -> ring.getJSONArray(j).let { SourceGeometry.Point(it.getDouble(0), it.getDouble(1)) } }
    }
    @Test fun authoritativeGeographicSelectionAndMasks() {
        val atlas = JSONObject(File(System.getProperty("ridgeline.assets"), "gap-atlas.json").readText()).getJSONObject("entries")
        val cases = JSONObject(File(System.getProperty("ridgeline.fixtures"), "source-goldens.json").readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i); val meta = atlas.getJSONObject(row.getString("key"))
            val z = row.getInt("z"); val x = row.getInt("x"); val y = row.getInt("y")
            val outer = rings(meta.getJSONArray("outer")); val key = "$z/$x/$y"
            assertEquals(key, row.getString("mode"), SourceGeometry.mode(z,x,y,meta.getInt("z"),meta.getInt("x"),meta.getInt("y"),meta.getInt("width"),meta.getInt("height"),outer,rings(meta.optJSONArray("inner")),rings(meta.optJSONArray("seamRings")),meta.optBoolean("vector")))
            if (row.has("mask")) {
                val mask = SourceGeometry.mask(outer,z,x,y,meta.getInt("z"),meta.getInt("x"),meta.getInt("y"))
                val digest = MessageDigest.getInstance("SHA-256").digest(ByteArray(mask.size) { if (mask[it]) 255.toByte() else 0 })
                assertEquals(key, row.getString("mask"), digest.joinToString("") { "%02x".format(it) })
            }
        }
    }
    @Test fun holesKeepOriginalImagery() {
        fun box(a: Double,b: Double) = listOf(SourceGeometry.Point(a,a),SourceGeometry.Point(b,a),SourceGeometry.Point(b,b),SourceGeometry.Point(a,b),SourceGeometry.Point(a,a))
        val mask = SourceGeometry.mask(listOf(box(0.0,256.0),box(64.0,192.0)),0,0,0,0,0,0)
        assertTrue(mask[10*256+10]); assertFalse(mask[128*256+128])
    }
}
