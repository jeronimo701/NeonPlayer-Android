package neon.androidplayer

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackInteractionTest {
    private fun get(a:MainActivity,key:String)=MainActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.get(a)
    private fun set(a:MainActivity,key:String,value:Any?){MainActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.set(a,value)}
    private fun call(a:MainActivity,key:String){MainActivity::class.java.getDeclaredMethod(key).apply{isAccessible=true}.invoke(a)}
    @Test fun liveSpeedAutoLockHintAndDoubleTapUnlock():Unit=runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        MediaLibrary(context).clearHistory();context.getSharedPreferences("neon",0).edit().clear().putString("appTheme","deck").putString("orientationPolicy","landscape").putString("controlsAnimation","none").putBoolean("autoLock",true).commit()
        val file=File(context.cacheDir,"interaction-demo.mp4");ins.context.assets.open("demo.mp4").use{i->file.outputStream().use{i.copyTo(it)}}
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
            var engine:PlayerEngine?=null
            withTimeout(25000){while(engine?.state?.value?.loaded!=true){scenario.onActivity{a->engine=get(a,"engine")as PlayerEngine};delay(100)}}
            var button:ControlButton?=null
            scenario.onActivity{a->engine!!.pause();set(a,"hidden",false);call(a,"rebuildControls");button=(get(a,"speedButtons")as List<ControlButton>).first();engine!!.speed(1.5)}
            withTimeout(5000){while(button?.symbol!="1.5×")delay(50)}
            scenario.onActivity{a->
                assertTrue((get(a,"speedButtons")as List<ControlButton>).contains(button))
                MainActivity::class.java.getDeclaredMethod("setControlsHidden",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,true)
                val deadline=get(a,"autoLockAt")as Long;assertTrue(deadline-SystemClock.uptimeMillis()in 4800..5000)
                (get(a,"autoLock")as Runnable).run();assertEquals(false,get(a,"locked"))
                set(a,"autoLockAt",SystemClock.uptimeMillis()-1);(get(a,"autoLock")as Runnable).run();assertEquals(true,get(a,"locked"));call(a,"showLockHint")
            }
            delay(200)
            scenario.onActivity{a->
                val hint=get(a,"lockHint")as View;val region=get(a,"lastControlArea")as Rect;val p=IntArray(2);hint.getLocationInWindow(p)
                assertTrue(region.contains(p[0]+hint.width/2,p[1]+hint.height/2))
                val layer=get(a,"lockLayer")as View;val time=SystemClock.uptimeMillis();val x=region.centerX().toFloat();val y=region.centerY().toFloat()
                for((offset,action)in listOf(0L to MotionEvent.ACTION_DOWN,20L to MotionEvent.ACTION_UP,140L to MotionEvent.ACTION_DOWN,160L to MotionEvent.ACTION_UP)){
                    val event=MotionEvent.obtain(time+(if(offset<100)0 else 140),time+offset,action,x,y,0);layer.dispatchTouchEvent(event);event.recycle()
                }
                assertEquals(false,get(a,"locked"));assertEquals(false,get(a,"hidden"));assertTrue(engine!!.state.value.paused)
                a.getSharedPreferences("neon",0).edit().putBoolean("autoLock",false).apply()
                MainActivity::class.java.getDeclaredMethod("setControlsHidden",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,true);assertEquals(0L,get(a,"autoLockAt"))
            }
        }
    }
}
