package neon.androidplayer

import android.content.SharedPreferences

/** Additive schema changes: preserve existing per-display profiles and user choices. */
internal fun migrateSettings(prefs:SharedPreferences){
    if(prefs.getInt("settingsSchema",0)>=2)return
    val edit=prefs.edit()
    fun number(key:String,default:Int,min:Int,max:Int){if(prefs.contains(key)){val value=prefs.all[key]as?Int?:default;edit.putInt(key,value.coerceIn(min,max))}}
    number("uiScale",100,80,140);number("newDays",7,0,365);number("audioGain",0,-12,18)
    prefs.all.filterKeys{it.startsWith("scale.")}.forEach{(key,value)->
        when {
            key.endsWith(".yalign")->if(value is Int)edit.putInt(key,value.coerceIn(-1,1))
            key.endsWith(".fit")->if(value !in listOf("fit","fill","stretch"))edit.putString(key,"fit")
            key.endsWith(".axis")->if(value !in listOf("horizontal","vertical"))edit.putString(key,"horizontal")
            value is Int->edit.putInt(key,value.coerceIn(50,150))
        }
    }
    edit.putInt("settingsSchema",2).apply()
}
