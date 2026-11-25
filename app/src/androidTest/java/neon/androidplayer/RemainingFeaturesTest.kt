package neon.androidplayer

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.*
import java.io.*

@RunWith(AndroidJUnit4::class)
class RemainingFeaturesTest {
    @Test fun networkIdentitySurvivesLibraryReloadWithoutSecrets(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val library=MediaLibrary(context,"qa-alpha6-library");library.clearHistory()
        val connection=Connection("SMB","nas.example",445,"shows","viewer","unique-secret-alpha6")
        val media=NetworkSource(connection,"Series\\Episode 02.mkv").media("Episode 02",bytes=10000)
        library.remember(media,540.0,1800.0)
        val restored=MediaLibrary(context,"qa-alpha6-library").history().single()
        assertEquals(540.0,restored.resume,0.0)
        assertEquals("Series\\Episode 02.mkv",NetworkSource.read(restored.media)!!.path)
        assertFalse(restored.media.json().toString().contains("unique-secret-alpha6"))
        assertFalse(restored.media.json().toString().contains("127.0.0.1"))
        val list=library.create("Network series");library.save(list.copy(items=listOf(media)))
        assertEquals(media.uri,MediaLibrary(context,"qa-alpha6-library").playlists().single().items.single().uri)
        library.delete(list.id);library.clearHistory()
    }
    @Test fun externalContentFileSupportsSeekAndNeverContainsPassword(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val data=ByteArray(131072){(it%251).toByte()}
        val uri=SharedNetworkFiles.publish(context,"Episode.mkv") {object:RemoteObject{
            override val name="Episode.mkv";override val size=data.size.toLong()
            override fun open(offset:Long)=ByteArrayInputStream(data,offset.toInt(),data.size-offset.toInt())
            override fun close(){}
        }}
        assertEquals("content",uri.scheme)
        context.contentResolver.openFileDescriptor(uri,"r")!!.use {pfd->
            FileInputStream(pfd.fileDescriptor).use{input->
                input.channel.position(75000);val actual=ByteArray(5000);assertEquals(5000,input.read(actual));assertArrayEquals(data.copyOfRange(75000,80000),actual)
                input.channel.position(17);assertEquals(data[17].toInt()and 255,input.read())
            }
        }
        assertTrue(runCatching{context.contentResolver.openFileDescriptor(uri,"w")}.isFailure)
    }
    @Test fun settingsMigrationKeepsDisplayProfilesAndBoundsNewSettings(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("qa-migration-alpha6",0)
        prefs.edit().clear().putInt("scale.inner.2.10.20",125).putInt("scale.inner.2.10.20.yalign",-1).putString("scale.inner.2.10.20.fit","fill").putInt("uiScale",900).commit()
        migrateSettings(prefs)
        assertEquals(125,prefs.getInt("scale.inner.2.10.20",0));assertEquals(-1,prefs.getInt("scale.inner.2.10.20.yalign",0));assertEquals("fill",prefs.getString("scale.inner.2.10.20.fit",""));assertEquals(140,prefs.getInt("uiScale",0));assertEquals(2,prefs.getInt("settingsSchema",0));prefs.edit().clear().commit()
    }
    @Test fun aniskipRejectsWrongCutsInsteadOfSkippingUnrelatedScenes(){
        val raw="""{"found":true,"results":[{"episodeLength":1400,"skipType":"op","interval":{"startTime":30,"endTime":120}},{"episodeLength":1430,"skipType":"ed","interval":{"startTime":1300,"endTime":1390}}]}"""
        val rows=AniSkip.parse(raw,1400.0);assertEquals(1,rows.size);assertEquals("intro",rows.single().kind)
        assertTrue(AniSkip.parse(raw,1800.0).isEmpty())
    }
    @Test fun nativeAudioSettingsAndLayoutBackKeepPlayback():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("neon",0)
        prefs.edit().putBoolean("audioNormalize",true).putBoolean("audioNight",true).putBoolean("audioLimiter",true).putInt("audioGain",3).putString("orientationPolicy","system").putInt("uiScale",110).commit()
        val file=File(context.cacheDir,"alpha6-demo.mp4")
        instrumentation.context.assets.open("demo.mp4").use{input->file.outputStream().use{input.copyTo(it)}}
        val intent=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use{scenario->
            var engine:PlayerEngine?=null
            withTimeout(25000){while(engine?.state?.value?.loaded!=true){scenario.onActivity{a->engine=MainActivity::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(a)as PlayerEngine};delay(150)}}
            delay(500)
            var filters="";var error:String?=null
            scenario.onActivity{a->
                engine!!.pause();filters=engine!!.property("af").orEmpty();error=engine!!.state.value.error
                MainActivity::class.java.getDeclaredMethod("settings").apply{isAccessible=true}.invoke(a)
                MainActivity::class.java.getDeclaredMethod("placementMenu",String::class.java).apply{isAccessible=true}.invoke(a,"vertical")
                val nav=MainActivity::class.java.getDeclaredField("settingsNav").apply{isAccessible=true}.get(a)as SettingsNavigator
                assertEquals("По вертикали",nav.screen.heading.text.toString());nav.back();assertEquals("Настройки",nav.screen.heading.text.toString());nav.close()
            }
            assertTrue(filters,filters.contains("neon-audio"));assertNull(error)
            withTimeout(5000){while(!engine!!.state.value.paused)delay(100)}
            scenario.onActivity{a->MainActivity::class.java.getDeclaredMethod("leavePlayback").apply{isAccessible=true}.invoke(a)}
            val history=MediaLibrary(context).history().firstOrNull{it.media.title=="alpha6-demo.mp4"}
            assertNotNull(history)
        }
        prefs.edit().putBoolean("audioNormalize",false).putBoolean("audioNight",false).putBoolean("audioLimiter",false).putInt("audioGain",0).putInt("uiScale",100).commit()
    }
}
