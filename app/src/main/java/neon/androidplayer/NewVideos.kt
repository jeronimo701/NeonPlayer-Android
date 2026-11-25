package neon.androidplayer

import android.content.Context
import java.security.MessageDigest

/** DATE_ADDED is the platform index time, not EXIF creation or the file's modification time. */
internal class NewVideos(context:Context,name:String="new-videos") {
    private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    fun observe(items:List<MediaRef>,scope:String,now:Long=System.currentTimeMillis()) {
        val source="source.${hash(scope)}"
        val initial=!prefs.getBoolean(source,false)
        val edit=prefs.edit();var count=prefs.all.size
        items.filterNot{it.folder}.filter{it.added<=0}.forEach{item->
            val key="seen.${hash(item.uri)}"
            if(!prefs.contains(key)&&count<20000){edit.putLong(key,if(initial)0 else now);count++}
        }
        edit.putBoolean(source,true).apply()
    }
    fun isNew(item:MediaRef,days:Int,now:Long=System.currentTimeMillis()):Boolean {
        if(days<=0)return false
        val time=if(item.added>0)item.added else prefs.getLong("seen.${hash(item.uri)}",0)
        return time>0&&time<=now&&now-time<days.coerceAtMost(365)*86400000L
    }
}
