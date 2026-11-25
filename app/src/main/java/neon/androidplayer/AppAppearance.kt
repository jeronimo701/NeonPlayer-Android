package neon.androidplayer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** App palette and launcher identity are independent; neither restarts playback. */
internal object AppAppearance {
    var amber=true
    val accent get()=if(amber)0xFFFF9900.toInt()else 0xFF55E9E2.toInt()
    val background get()=if(amber)0xFF080808.toInt()else 0xFF101216.toInt()
    val surface get()=if(amber)0xFF191919.toInt()else 0xFF1B1F25.toInt()
    val selected get()=if(amber)0xFF3A260C.toInt()else 0xFF183337.toInt()
    val pressed get()=if(amber)0xFF49300F.toInt()else 0xFF25383B.toInt()
    val stroke get()=if(amber)0xFF39332B.toInt()else 0xFF333943.toInt()
    val muted get()=if(amber)0xFFA4A09A.toInt()else 0xFF9299A6.toInt()
    val dialogTheme get()=if(amber)R.style.AmberDialogTheme else R.style.NeonDialogTheme
    fun load(context:Context){amber=context.getSharedPreferences("neon",Context.MODE_PRIVATE).getString("appTheme","amber")=="amber"}
}
internal data class LauncherIcon(val id:String,val title:String,val component:String,val drawable:Int)
internal object LauncherIcons {
    val options=listOf(
        LauncherIcon("original","Neon · Оригинальная","LauncherOriginal",R.mipmap.ic_launcher),
        LauncherIcon("a1","A1 · Минимализм","LauncherA1",R.mipmap.ic_a1),
        LauncherIcon("a2","A2 · Стекло","LauncherA2",R.mipmap.ic_a2),
        LauncherIcon("a3","A3 · Неон","LauncherA3",R.mipmap.ic_a3),
        LauncherIcon("a4","A4 · Мягкая","LauncherA4",R.mipmap.ic_a4),
        LauncherIcon("a5","A5 · Золото","LauncherA5",R.mipmap.ic_a5),
        LauncherIcon("b1","B1 · Play + Duck · по умолчанию","LauncherB1",R.mipmap.ic_b1),
        LauncherIcon("b2","B2 · Neon Hub","LauncherB2",R.mipmap.ic_b2),
        LauncherIcon("b3","B3 · Оранжевая плашка","LauncherB3",R.mipmap.ic_b3),
        LauncherIcon("b4","B4 · Стекло Orange","LauncherB4",R.mipmap.ic_b4),
        LauncherIcon("b5","B5 · Контур Orange","LauncherB5",R.mipmap.ic_b5)
    )
    private fun component(context:Context,icon:LauncherIcon)=ComponentName(context,"neon.androidplayer."+icon.component)
    fun current(context:Context):String=options.firstOrNull {
        val state=context.packageManager.getComponentEnabledSetting(component(context,it))
        state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (it.id=="b1"&&state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }?.id?:"b1"
    fun select(context:Context,id:String){
        val chosen=options.first{it.id==id};val pm=context.packageManager
        if(Build.VERSION.SDK_INT>=33){
            pm.setComponentEnabledSettings(options.map{PackageManager.ComponentEnabledSetting(component(context,it),if(it==chosen)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)})
        }else{
            // Enable the new entry first: there is always a working launcher entry.
            pm.setComponentEnabledSetting(component(context,chosen),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
            options.filter{it!=chosen}.forEach{pm.setComponentEnabledSetting(component(context,it),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)}
        }
    }
}
