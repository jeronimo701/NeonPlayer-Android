package neon.androidplayer

import android.content.Intent
import android.graphics.*
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

@RunWith(AndroidJUnit4::class)
class Release036Test {
    private fun get(a:MainActivity,key:String)=MainActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.get(a)
    private fun call(a:MainActivity,key:String){MainActivity::class.java.getDeclaredMethod(key).apply{isAccessible=true}.invoke(a)}
    private fun shot(a:MainActivity,v:View,name:String){val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(a.filesDir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun defaultsGroupedSettingsAndFooter():Unit=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("neon",0).edit().clear().putString("controlsAnimation","none").commit();MediaLibrary(c).clearHistory()
        ActivityScenario.launch<MainActivity>(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
            withTimeout(30000){var ready=false;while(!ready){scenario.onActivity{ready=get(it,"initialized")as Boolean};delay(100)}}
            scenario.onActivity{a->
                assertFalse(BuildConfig.VERSION_NAME.contains("alpha"));assertTrue(AppAppearance.amber);assertEquals("orange",UiSizing.iconStyle)
                shot(a,get(a,"home")as View,"release036-home.png");call(a,"settings")
                val nav=get(a,"settingsNav")as SettingsNavigator
                val rows=(0 until nav.screen.list.adapter.count).map{nav.screen.list.adapter.getItem(it)as DeckRow}
                assertEquals("О приложении",rows.last().title);assertEquals(6,rows.count{it.section})
                rows.forEachIndexed{i,r->if(r.section)assertFalse(nav.screen.list.adapter.isEnabled(i))}
                val i=rows.indexOfFirst{it.title=="TorrServer и JacRed"};nav.screen.list.performItemClick(null,i,i.toLong())
                assertEquals("TorrServer и JacRed",nav.screen.heading.text.toString());assertTrue((nav.screen.list.adapter.getItem(1)as DeckRow).title.contains("JacRed"));nav.close();call(a,"fileExplorer")
            }
            var browser:DeckScreen?=null
            withTimeout(15000){while(browser==null){scenario.onActivity{a->browser=(get(a,"boundDialogs")as Map<*,*>).keys.filterIsInstance<DeckScreen>().firstOrNull{it.heading.text=="Файлы"}};delay(100)}}
            delay(200)
            scenario.onActivity{a->
                val col=browser!!.column;val footer=col.getChildAt(col.childCount-1)as LinearLayout;assertEquals(3,footer.childCount)
                for(i in 0..2){val v=footer.getChildAt(i);assertTrue(v.width>0);assertTrue(v.height>=a.dp(48));if(i>0)assertTrue(v.top>footer.getChildAt(i-1).bottom)}
                shot(a,col,"release036-files.png");browser!!.dismiss()
                val b=Bitmap.createBitmap(1100,200,Bitmap.Config.ARGB_8888);val canvas=Canvas(b);canvas.drawColor(Color.BLACK);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE}
                listOf("1×","1.25×","0.75×","2.25×","10×").forEachIndexed{i,s->OrangeControlIcons.draw(canvas,p,s,i*210f+105,100f,130f)}
                File(a.filesDir,"release036-speed.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
                assertEquals(20,duckMessages.distinct().size)
            }
        }
    }
    @Test fun youtubeRuntimeAndSeparateAudioMetadata(){
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeLinks.prepare(c);val dl=YoutubeDL.getInstance()
        val version=dl.execute(YoutubeDLRequest("https://www.youtube.com/watch?v=jNQXAC9IVRw").apply{addOption("--version")},"neon-runtime-smoke",null).out.trim()
        assertEquals("2026.11.23",version);android.util.Log.i("NeonQa","yt-dlp runtime: $version")
        val source=JSONObject().put("title","Sample").put("requested_formats",org.json.JSONArray().put(JSONObject().put("vcodec","avc1").put("acodec","none").put("url","https://example.test/video")).put(JSONObject().put("vcodec","none").put("acodec","aac").put("url","https://example.test/audio")))
        val parsed=YoutubeLinks.parse(source);assertEquals("https://example.test/video",parsed.video);assertEquals("https://example.test/audio",parsed.audio)
    }
}
