package neon.androidplayer

import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.net.Uri
import android.view.*
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FlexAndStylesTest {
    private fun get(a:MainActivity,key:String)=MainActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.get(a)
    private fun set(a:MainActivity,key:String,value:Any?){MainActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.set(a,value)}
    private fun call(a:MainActivity,key:String){MainActivity::class.java.getDeclaredMethod(key).apply{isAccessible=true}.invoke(a)}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Test fun flexHomeDialogBoundsAndFourLiveIconPreviews():Unit=runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        MediaLibrary(context).clearHistory()
        context.getSharedPreferences("neon",0).edit().clear().putString("appTheme","deck").putString("orientationPolicy","portrait").putString("controlsAnimation","none").commit()
        val file=File(context.cacheDir,"alpha8-demo.mp4");ins.context.assets.open("demo.mp4").use{i->file.outputStream().use{i.copyTo(it)}}
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
            var engine:PlayerEngine?=null
            withTimeout(25000){while(engine?.state?.value?.loaded!=true){scenario.onActivity{a->engine=get(a,"engine")as PlayerEngine;a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};delay(150)}}
            android.util.Log.i("NeonQa","ready");delay(500)
            var hinge:Rect?=null
            scenario.onActivity{a->
                engine!!.pause();(get(a,"foldTask")as Job).cancel()
                val canvas=get(a,"canvas")as View;hinge=Rect(0,canvas.height/2,canvas.width,canvas.height/2+12)
                set(a,"foldHalf",true);set(a,"foldBounds",hinge);set(a,"flexPlacementOverride",false);call(a,"picture");call(a,"rebuildControls")
                assertEquals("duo",UiSizing.iconStyle)
                assertFalse(all(get(a,"controls")as View).filterIsInstance<ControlButton>().any{it.symbol=="rotate"})
                android.util.Log.i("NeonQa","speed");call(a,"speedDialog")
                val nav=get(a,"settingsNav")as SettingsNavigator
                assertEquals(a.deckArea()!!.height(),nav.screen.window!!.attributes.height);assertEquals(a.deckArea()!!.top,nav.screen.window!!.attributes.y);assertEquals(0f,nav.screen.window!!.attributes.dimAmount)
                nav.screen.list.performItemClick(null,3,3)
                assertTrue((nav.screen.list.adapter.getItem(3)as DeckRow).selected)
            }
            withTimeout(5000){while(engine!!.state.value.speed!=1.25)delay(50)}
            scenario.onActivity{a->
                (get(a,"settingsNav")as SettingsNavigator).close()
                android.util.Log.i("NeonQa","modal");val modal=PlayerAlertBuilder(a).setTitle("Test form").setView(EditText(a)).setPositiveButton("OK",null).show()
                assertEquals(a.deckArea()!!.height(),modal.window!!.attributes.height);assertEquals(0f,modal.window!!.attributes.dimAmount);modal.dismiss()
                android.util.Log.i("NeonQa","styles");call(a,"iconStyleMenu")
                val nav=get(a,"settingsNav")as SettingsNavigator;val snapshots=mutableSetOf<Int>()
                assertEquals(iconFamilies.size,nav.screen.list.adapter.count)
                iconFamilies.forEachIndexed{i,id->
                    nav.screen.list.performItemClick(null,i,i.toLong());assertEquals(id,UiSizing.iconStyle)
                    val row=nav.screen.list.adapter.getItem(i)as DeckRow;assertTrue(row.selected);assertEquals(id,row.previewStyle)
                    assertEquals(1,(0 until nav.screen.list.adapter.count).count{(nav.screen.list.adapter.getItem(it)as DeckRow).selected})
                    val bitmap=Bitmap.createBitmap(700,250,Bitmap.Config.ARGB_8888);val view=IconFamilyPreview(a,id);view.layout(0,0,700,250);view.draw(Canvas(bitmap));val pixels=IntArray(700*250);bitmap.getPixels(pixels,0,700,0,0,700,250);assertTrue(pixels.any{Color.alpha(it)>0});snapshots+=pixels.contentHashCode();bitmap.recycle()
                }
                assertEquals(iconFamilies.size,snapshots.size)
                nav.screen.list.performItemClick(null,0,0);assertEquals("duo",a.getSharedPreferences("neon",0).getString("iconStyle",""));nav.close()
                android.util.Log.i("NeonQa","home");call(a,"leavePlayback")
            }
            delay(300)
            scenario.onActivity{a->
                val home=get(a,"home")as LinearLayout;val split=home.getChildAt(0)as FrameLayout
                assertTrue(split.getChildAt(0).height<=hinge!!.top);assertTrue(split.getChildAt(1).top>=hinge!!.bottom)
                assertNotNull(a.deckArea());call(a,"settings");val nav=get(a,"settingsNav")as SettingsNavigator;assertEquals(a.deckArea()!!.height(),nav.screen.window!!.attributes.height);nav.close()
                android.util.Log.i("NeonQa","normal");set(a,"foldHalf",false);set(a,"foldBounds",null);call(a,"buildHome")
                assertNull(a.deckArea());assertFalse(home.getChildAt(0)is FrameLayout)
            }
        }
    }
}
