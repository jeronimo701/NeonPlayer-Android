package neon.androidplayer

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppearanceTest {
    private fun get(a:MainActivity,key:String)=MainActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.get(a)
    private fun call(a:MainActivity,key:String){MainActivity::class.java.getDeclaredMethod(key).apply{isAccessible=true}.invoke(a)}
    private fun snapshot(a:MainActivity,view:View,name:String){
        assertTrue(view.width>0&&view.height>0)
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b))
        File(a.filesDir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    @Test fun renderFamiliesAndAdaptiveIcons(){
        val ins=InstrumentationRegistry.getInstrumentation();val c=ins.targetContext
        ins.runOnMainSync{
            val b=Bitmap.createBitmap(1400,1050,Bitmap.Config.ARGB_8888);val canvas=Canvas(b);canvas.drawColor(0xFF080808.toInt())
            AppAppearance.amber=true
            val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=30f;typeface=android.graphics.Typeface.DEFAULT_BOLD}
            canvas.drawText("ORANGE · native controls",40f,50f,p)
            val v=IconFamilyPreview(c,"orange");v.layout(0,0,1400,320);canvas.save();canvas.translate(0f,70f);v.draw(canvas);canvas.restore()
            for((i,icon)in LauncherIcons.options.withIndex()){
                val x=35+(i%6)*230;val y=440+(i/6)*270
                c.getDrawable(icon.drawable)!!.apply{setBounds(x,y,x+180,y+180);draw(canvas)}
                canvas.drawText(icon.id.uppercase(),x.toFloat()+30,y+220f,p)
            }
            File(c.filesDir,"alpha9-native-specimen.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            AppAppearance.amber=false
        }
    }
    @Test fun themeAndLauncherChoicesStayIndependent():Unit=runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val c=ins.targetContext
        MediaLibrary(c).clearHistory();val prefs=c.getSharedPreferences("neon",0)
        prefs.edit().clear().putString("appTheme","deck").putString("controlsAnimation","none").commit()
        LauncherIcons.select(c,"original")
        try {
            ActivityScenario.launch<MainActivity>(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
                withTimeout(30000){var ready=false;while(!ready){scenario.onActivity{ready=get(it,"initialized")as Boolean};delay(100)}}
                scenario.onActivity{a->
                    assertFalse(AppAppearance.amber);assertEquals(0xFF55E9E2.toInt(),ACCENT)
                    call(a,"settings");call(a,"appThemeMenu")
                    val nav=get(a,"settingsNav")as SettingsNavigator
                    nav.screen.list.performItemClick(null,1,1)
                    assertTrue(AppAppearance.amber);assertEquals(0xFFFF9900.toInt(),ACCENT)
                    assertEquals("amber",prefs.getString("appTheme",""))
                    assertTrue((nav.screen.list.adapter.getItem(1)as DeckRow).selected)
                    assertEquals(DECK_BG,(nav.screen.column.background as ColorDrawable).color)
                    nav.close();call(a,"settings");call(a,"launcherIconMenu")
                    val icons=get(a,"settingsNav")as SettingsNavigator
                    for((i,id)in LauncherIcons.options.map{it.id}.withIndex()){
                        icons.screen.list.performItemClick(null,i,i.toLong())
                        assertEquals(id,LauncherIcons.current(a));assertTrue(AppAppearance.amber)
                        val rows=(0 until icons.screen.list.adapter.count).map{icons.screen.list.adapter.getItem(it)as DeckRow}
                        assertEquals(1,rows.count{it.selected});assertNotNull(rows[i].iconDrawable)
                        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(a.packageName)
                        assertEquals(1,a.packageManager.queryIntentActivities(intent,0).size)
                        val tv=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(a.packageName)
                        assertEquals(1,a.packageManager.queryIntentActivities(tv,0).size)
                        assertTrue(a.packageManager.getActivityInfo(android.content.ComponentName(a,MainActivity::class.java),0).exported)
                    }
                    LauncherIcons.select(a,"original");icons.close()
                }
                delay(300)
                scenario.onActivity{a->snapshot(a,get(a,"home")as LinearLayout,"alpha9-orange-home.png");call(a,"settings");call(a,"launcherIconMenu")}
                delay(300)
                scenario.onActivity{a->val nav=get(a,"settingsNav")as SettingsNavigator;snapshot(a,nav.screen.column,"alpha9-icons.png");nav.close()}
            }
            // No saved instance state: verify persisted theme is actually loaded on the next launch.
            ActivityScenario.launch<MainActivity>(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
                scenario.onActivity{a->assertTrue(AppAppearance.amber);assertEquals("original",LauncherIcons.current(a));call(a,"settings");call(a,"appThemeMenu");val nav=get(a,"settingsNav")as SettingsNavigator;nav.screen.list.performItemClick(null,0,0);assertFalse(AppAppearance.amber);assertEquals(0xFF55E9E2.toInt(),ACCENT);nav.close()}
            }
        } finally {LauncherIcons.select(c,"original");prefs.edit().putString("appTheme","deck").commit();AppAppearance.amber=false}
    }
}
