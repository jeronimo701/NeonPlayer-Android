package neon.androidplayer

import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ControlsUiTest {
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun set(a:MainActivity,name:String,value:Any?){MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.set(a,value)}
    private fun call(a:MainActivity,name:String){MainActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(a)}
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun x(v:View):Int {val p=IntArray(2);v.getLocationInWindow(p);return p[0]}
    @Test fun layoutsScrubbingSideLibraryFlexAndDiscoveryCache():Unit=runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        context.getSharedPreferences("neon",0).edit().clear().putString("appTheme","deck").putString("orientationPolicy","landscape").putString("controlsAnimation","none").commit()
        val file=File(context.cacheDir,"alpha7-demo.mp4");ins.context.assets.open("demo.mp4").use{i->file.outputStream().use{i.copyTo(it)}}
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
            var engine:PlayerEngine?=null
            withTimeout(25000){while(engine?.state?.value?.loaded!=true){scenario.onActivity{a->engine=field(a,"engine")as PlayerEngine};delay(150)}}
            scenario.onActivity{engine!!.pause()};delay(300)
            for(side in listOf("right","left","both")){
                scenario.onActivity{a->set(a,"layout",side);set(a,"hidden",false);val profile=field(a,"profile")as String;a.getSharedPreferences("neon",0).edit().putString("$profile.axis","horizontal").putInt("$profile.yalign",0).apply();call(a,"picture");call(a,"rebuildControls")}
                delay(300)
                scenario.onActivity{a->
                    val play=field(a,"playButton")as View;val canvas=field(a,"canvas")as View;val playPoint=IntArray(2);val canvasPoint=IntArray(2);play.getLocationInWindow(playPoint);canvas.getLocationInWindow(canvasPoint)
                    assertEquals("Play must remain vertically centred",(canvasPoint[1]+canvas.height/2).toDouble(),(playPoint[1]+play.height/2).toDouble(),a.dp(2).toDouble())
                    val nodes=views(field(a,"controls")as View);val tape=nodes.filterIsInstance<SeekTape>().single();val settings=nodes.filterIsInstance<ControlButton>().single{it.symbol=="settings"}
                    assertFalse(nodes.filterIsInstance<ControlButton>().any{it.symbol=="tracks"})
                    if(side=="left")assertTrue(x(settings)<x(tape))else if(side=="right")assertTrue(x(settings)>x(tape))
                    val down=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),MotionEvent.ACTION_DOWN,tape.width/2f,tape.height/2f,0);tape.dispatchTouchEvent(down);down.recycle()
                    MainActivity::class.java.getDeclaredMethod("setControlsHidden",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,true)
                    (field(a,"hide")as Runnable).run();assertEquals(false,field(a,"hidden"));assertEquals(false,field(a,"controlsHiding"))
                    call(a,"rebuildControls");assertSame(tape,field(a,"tape"))
                    val up=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,tape.width/2f,tape.height/2f,0);tape.dispatchTouchEvent(up);up.recycle()
                    assertEquals(false,field(a,"controlGesture"))
                }
                delay(150)
            }
            scenario.onActivity{a->
                assertTrue(android.provider.Settings.System.canWrite(a))
                val old=android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)
                val oldMode=android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE)
                val audio=a.getSystemService(android.media.AudioManager::class.java);val oldVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                call(a,"toggleLevels")
                val sliders=views(field(a,"controls")as View).filterIsInstance<LevelSlider>();assertEquals(2,sliders.size)
                sliders.forEach{v->val y=a.dp(60)+(v.height-a.dp(100))*.3f;for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,v.width/2f,if(action==MotionEvent.ACTION_DOWN)v.height*.85f else v.height*.15f,0);v.dispatchTouchEvent(e);e.recycle()}}
                assertTrue(android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)>150)
                assertTrue(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)>=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)*.6)
                call(a,"toggleLevels");audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,oldVolume,0)
                android.provider.Settings.System.putInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,old);android.provider.Settings.System.putInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,oldMode)
            }
            scenario.onActivity{a->call(a,"queueMenu");val nav=field(a,"libraryNav")as SettingsNavigator;assertTrue(nav.screen.window!!.attributes.width<a.resources.displayMetrics.widthPixels/2);call(a,"playlistsMenu");assertEquals(true,field(a,"viewing"));assertEquals("Плейлисты",nav.screen.heading.text.toString());nav.close();a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            withTimeout(10000){while(context.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)delay(100)}
            delay(700)
            scenario.onActivity{a->
                (field(a,"foldTask")as Job).cancel()
                val canvas=field(a,"canvas")as View;set(a,"flexPlacementOverride",false);set(a,"foldHalf",true);set(a,"foldBounds",Rect(0,canvas.height/2,canvas.width,canvas.height/2+10));call(a,"picture");call(a,"rebuildControls")
            };delay(600)
            scenario.onActivity{a->
                val flex=MainActivity::class.java.getDeclaredMethod("activeFlexBounds").apply{isAccessible=true}.invoke(a)as Rect?
                assertNotNull("Synthetic half-open posture must remain active",flex)
                assertEquals(flex!!.top,(field(a,"surface")as View).height)
                assertFalse(views(field(a,"controls")as View).any{it is SeekTape})
            }
            scenario.onActivity{a->
                val play=field(a,"playButton")as View;val canvas=field(a,"canvas")as View;val pos=IntArray(2);play.getLocationInWindow(pos)
                assertTrue("Flex transport must sit near bottom",pos[1]+play.height>canvas.height-a.dp(100))
                set(a,"foldHalf",false);set(a,"foldBounds",null as Any?)
            }
            scenario.onActivity{a->call(a,"leavePlayback");set(a,"discovered",listOf(Discovery.Result(false,"192.0.2.1","test cache")));set(a,"discoveryTime",(SystemClock.elapsedRealtime()-120000).coerceAtLeast(1))
                (field(a,"discoveryTask")as? Deferred<*>)?.cancel();set(a,"discoveryTask",null)
                val net=MainActivity::class.java.getDeclaredMethod("localNetwork").apply{isAccessible=true}.invoke(a);set(a,"discoveryNetwork",net?.toString()as Any?)
                val previous=field(a,"discoveryTask");val deferred=MainActivity::class.java.getDeclaredMethod("startDiscovery",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,false)as Deferred<*>
                assertTrue(deferred.isCompleted);assertEquals(previous,field(a,"discoveryTask"))
            }
        }
    }
}
