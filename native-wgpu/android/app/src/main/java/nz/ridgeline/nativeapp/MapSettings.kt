package nz.ridgeline.nativeapp
import android.content.Context

/** Immutable render/layer policy; readers never query preferences in frame loops. */
internal class MapSettings(context:Context){
    enum class Base{LINZ,SATELLITE,TOPO}
    data class State(val base:Base=Base.LINZ,val natural:Boolean=true,val names:Boolean=true,val peaks:Boolean=true,val roads:Boolean=true,val tracks:Boolean=true,val huts:Boolean=true,val contours:Boolean=false,val borders:Boolean=true,val hillshade:Boolean=false,val relief:Float=1f,val sky:Int=0xffbdcbd0.toInt()){
        fun overlays()=base!=Base.TOPO&&(names||peaks||roads||tracks||huts||contours)
        fun enabled(group:String)=when(group){"names"->names;"peaks"->peaks;"roads"->roads;"tracks"->tracks;"huts"->huts;"contours"->contours;else->false}
    }
    private val prefs=context.getSharedPreferences("map-layers",0)
    @Volatile var state=State(
        base=runCatching{Base.valueOf(prefs.getString("base","LINZ")!!)}.getOrDefault(Base.LINZ),natural=prefs.getBoolean("natural",true),
        names=prefs.getBoolean("names",true),peaks=prefs.getBoolean("peaks",true),roads=prefs.getBoolean("roads",true),tracks=prefs.getBoolean("tracks",true),huts=prefs.getBoolean("huts",true),contours=prefs.getBoolean("contours",false),borders=prefs.getBoolean("borders",true),hillshade=prefs.getBoolean("hillshade",false),relief=prefs.getFloat("relief",1f).takeIf{it.isFinite()}?.coerceIn(1f,2f)?:1f,sky=prefs.getInt("sky",0xffbdcbd0.toInt())
    );private set
    var onChange:(State,State)->Unit={_,_->}
    fun change(update:(State)->State){val before=state;val after=update(before);if(after==before)return;state=after
        prefs.edit().putString("base",after.base.name).putBoolean("natural",after.natural).putBoolean("names",after.names).putBoolean("peaks",after.peaks).putBoolean("roads",after.roads).putBoolean("tracks",after.tracks).putBoolean("huts",after.huts).putBoolean("contours",after.contours).putBoolean("borders",after.borders).putBoolean("hillshade",after.hillshade).putFloat("relief",after.relief).putInt("sky",after.sky).apply()
        onChange(before,after)
    }
}
