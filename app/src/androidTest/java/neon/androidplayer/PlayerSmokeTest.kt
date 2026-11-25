package neon.androidplayer

import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class PlayerSmokeTest {
    @Test fun nativeVideoPlaybackSeekAndScreenSettings():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        MediaLibrary(context).clearHistory()
        context.getSharedPreferences("neon",0).edit().clear().commit()
        val file=File(context.cacheDir,"neon-demo.mp4")
        instrumentation.context.assets.open("demo.mp4").use{input->file.outputStream().use{input.copyTo(it)}}
        val intent=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var engine:PlayerEngine?=null
            withTimeout(25000){while(engine?.state?.value?.loaded!=true || (engine?.state?.value?.duration?:0.0)<=0){scenario.onActivity {activity->
                activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                engine=MainActivity::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(activity) as? PlayerEngine
            };delay(200)}}
            assertTrue(engine!!.state.value.duration>1)
            withTimeout(10000){while(engine!!.state.value.position<.1)delay(100)}
            scenario.onActivity { engine!!.seek(.8);engine!!.pause() }
            withTimeout(10000){while(engine!!.state.value.position<.6)delay(100)}
            var version=""
            scenario.onActivity {version=engine!!.property("mpv-version").orEmpty()}
            assertTrue(version,version.contains("mpv"))
            val frozen=android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888)
            val copied=java.util.concurrent.atomic.AtomicInteger(-1)
            scenario.onActivity {activity->
                val source=MainActivity::class.java.getDeclaredField("surface").apply{isAccessible=true}.get(activity) as android.view.SurfaceView
                val rect=MainActivity::class.java.getDeclaredMethod("videoRectangle").apply{isAccessible=true}.invoke(activity) as android.graphics.RectF
                android.view.PixelCopy.request(source.holder.surface,android.graphics.Rect(rect.left.toInt(),rect.top.toInt(),rect.right.toInt(),rect.bottom.toInt()),frozen,{copied.set(it)},android.os.Handler(android.os.Looper.getMainLooper()))
            }
            withTimeout(5000){while(copied.get()==-1)delay(100)}
            assertEquals(android.view.PixelCopy.SUCCESS,copied.get())
            val still=File(context.cacheDir,"geometry-input.png")
            still.outputStream().use{frozen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};frozen.recycle()
            scenario.onActivity {engine!!.command("set","image-display-duration","60");engine!!.open(Uri.fromFile(still).toString(),"Static geometry fixture")}
            withTimeout(10000){while(!engine!!.state.value.loaded||!engine!!.state.value.hasVideo)delay(100)}
            scenario.onActivity {engine!!.pause()}
            withTimeout(5000){while(!engine!!.state.value.paused)delay(100)}
            scenario.onActivity {activity->
                MainActivity::class.java.getDeclaredField("hidden").apply{isAccessible=true}.setBoolean(activity,true)
                MainActivity::class.java.getDeclaredMethod("rebuildControls").apply{isAccessible=true}.invoke(activity)
            }
            delay(250)
            val baseline=instrumentation.uiAutomation.takeScreenshot()
            scenario.onActivity {activity->
                activity.getSharedPreferences("neon",0).edit().putInt("ambient",1).putInt("edge",0).putInt("brightness",70).apply()
                MainActivity::class.java.getDeclaredMethod("picture").apply{isAccessible=true}.invoke(activity)
            }
            delay(1500)
            val lit=instrumentation.uiAutomation.takeScreenshot()
            // The centre stays identical while only the right free bar receives ambient colour.
            for(x in listOf(.2,.4,.6))for(y in listOf(.2,.4,.6,.8)) {
                val px=(baseline.width*x).toInt();val py=(baseline.height*y).toInt()
                assertEquals("Ambient resized or changed centre at $px,$py",baseline.getPixel(px,py),lit.getPixel(px,py))
            }
            val bar=lit.getPixel((lit.width*.94).toInt(),(lit.height*.4).toInt())
            assertTrue("Free bar must contain ambient colour",android.graphics.Color.red(bar)+android.graphics.Color.green(bar)+android.graphics.Color.blue(bar)>30)
            File(context.cacheDir,"ambient-off.png").outputStream().use{baseline.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            File(context.cacheDir,"ambient-on.png").outputStream().use{lit.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            baseline.recycle();lit.recycle()
            scenario.onActivity {activity->
                assertEquals(-1.0,engine!!.property("video-aspect-override")!!.toDouble(),.001)
                activity.getSharedPreferences("neon",0).edit().putInt("ambient",0).apply()
                MainActivity::class.java.getDeclaredMethod("picture").apply{isAccessible=true}.invoke(activity)
            }
            scenario.onActivity {engine!!.open(Uri.fromFile(file).toString(),"Neon fixture")}
            withTimeout(10000){while(!engine!!.state.value.loaded||engine!!.state.value.duration<=0)delay(100)}
            scenario.onActivity {engine!!.pause()}
            scenario.onActivity {activity->MainActivity::class.java.getDeclaredMethod("settings").apply{isAccessible=true}.invoke(activity)}
            delay(200)
            var navigation:SettingsNavigator?=null
            scenario.onActivity {activity->
                navigation=MainActivity::class.java.getDeclaredField("settingsNav").apply{isAccessible=true}.get(activity) as SettingsNavigator
                navigation!!.screen.list.performItemClick(navigation!!.screen.list,3,3)
                assertEquals("Подсветка полос",navigation!!.screen.heading.text.toString())
                navigation!!.screen.list.performItemClick(navigation!!.screen.list,1,1)
                assertTrue(navigation!!.showing)
                assertTrue(engine!!.state.value.loaded)
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            scenario.onActivity {assertTrue(navigation!!.showing);assertEquals("Настройки",navigation!!.screen.heading.text.toString());navigation!!.close()}
            scenario.onActivity {activity->activity.getSharedPreferences("neon",0).edit().putInt("ambient",0).apply();MainActivity::class.java.getDeclaredMethod("picture").apply{isAccessible=true}.invoke(activity)}
            for(layout in listOf("right","both")) {
                scenario.onActivity {activity->
                    MainActivity::class.java.getDeclaredField("layout").apply{isAccessible=true}.set(activity,layout)
                    MainActivity::class.java.getDeclaredField("hidden").apply{isAccessible=true}.setBoolean(activity,false)
                    engine!!.picture(org.json.JSONObject().put("mode",0).put("scale",1).put("align",if(layout=="both")0 else -1).put("shader","native"))
                    MainActivity::class.java.getDeclaredMethod("rebuildControls").apply{isAccessible=true}.invoke(activity)
                }
                delay(250)
                val bitmap=instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir,"layout-$layout.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            var surface:android.view.SurfaceView?=null
            scenario.onActivity {activity->
                surface=MainActivity::class.java.getDeclaredField("surface").apply{isAccessible=true}.get(activity) as android.view.SurfaceView
                MainActivity::class.java.getDeclaredField("foldHalf").apply{isAccessible=true}.setBoolean(activity,true)
                MainActivity::class.java.getDeclaredField("foldBounds").apply{isAccessible=true}.set(activity,android.graphics.Rect(0,480,context.resources.displayMetrics.widthPixels,490))
                MainActivity::class.java.getDeclaredMethod("picture").apply{isAccessible=true}.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("rebuildControls").apply{isAccessible=true}.invoke(activity)
            }
            withTimeout(5000){while(surface?.height!=480)delay(100)}
            scenario.onActivity{activity->
                val overlay=MainActivity::class.java.getDeclaredField("controls").apply{isAccessible=true}.get(activity) as android.widget.FrameLayout
                assertTrue("Controls must stay below hinge",overlay.getChildAt(overlay.childCount-1).top>=490)
                MainActivity::class.java.getDeclaredField("foldHalf").apply{isAccessible=true}.setBoolean(activity,false)
                MainActivity::class.java.getDeclaredField("foldBounds").apply{isAccessible=true}.set(activity,null)
                MainActivity::class.java.getDeclaredMethod("picture").apply{isAccessible=true}.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("rebuildControls").apply{isAccessible=true}.invoke(activity)
            }
            withTimeout(5000){while(surface?.height==480)delay(100)}
            val second=File(context.cacheDir,"neon-episode2.mp4");file.copyTo(second,overwrite=true)
            val episodes=listOf(MediaRef(Uri.fromFile(file).toString(),"Episode 1"),MediaRef(Uri.fromFile(second).toString(),"Episode 2"))
            scenario.onActivity {activity->
                MainActivity::class.java.getDeclaredMethod("startQueue",List::class.java,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,episodes,0)
            }
            withTimeout(10000){while(!engine!!.state.value.loaded||engine!!.state.value.title!="Episode 1")delay(100)}
            scenario.onActivity {engine!!.seek(engine!!.state.value.duration-.4)}
            withTimeout(15000){while(!engine!!.state.value.loaded||engine!!.state.value.title!="Episode 2")delay(100)}
            assertTrue("Next episode starts at the beginning",engine!!.state.value.position<5)
            val watched=MediaLibrary(context).history().first{it.media.uri==episodes[0].uri}
            assertTrue(watched.position>50)
            assertEquals(0.0,watched.resume,0.0)
            val remote=object:RemoteObject {
                override val name="fixture.mp4";override val size=file.length()
                override fun open(offset:Long):java.io.InputStream=java.io.FileInputStream(file).apply {channel.position(offset)}
                override fun close(){}
            }
            StreamBridge(remote).use {bridge->
                scenario.onActivity {engine!!.open(bridge.url,"Network fixture")}
                withTimeout(15000){while(!engine!!.state.value.loaded||engine!!.state.value.duration<=0)delay(100)}
                scenario.onActivity {engine!!.seek(20.0)}
                withTimeout(10000){while(engine!!.state.value.position<19)delay(100)}
                scenario.onActivity {engine!!.command("stop")}
            }
            val rotated=File(context.cacheDir,"neon-vertical.mp4")
            instrumentation.context.assets.open("rotated-demo.mp4").use{input->rotated.outputStream().use{input.copyTo(it)}}
            scenario.onActivity {activity->MainActivity::class.java.getDeclaredMethod("openMedia",MediaRef::class.java).apply{isAccessible=true}.invoke(activity,MediaRef(Uri.fromFile(rotated).toString(),"Вертикальный ролик"))}
            withTimeout(10000){while(!engine!!.state.value.loaded||engine!!.state.value.aspect>=1||context.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)delay(100)}
            scenario.onActivity {activity->
                val profile=MainActivity::class.java.getDeclaredField("profile").apply{isAccessible=true}.get(activity) as String
                activity.getSharedPreferences("neon",0).edit().putInt("$profile.yalign",-1).apply()
                MainActivity::class.java.getDeclaredMethod("picture").apply{isAccessible=true}.invoke(activity)
                MainActivity::class.java.getDeclaredField("hidden").apply{isAccessible=true}.setBoolean(activity,false)
                MainActivity::class.java.getDeclaredMethod("rebuildControls").apply{isAccessible=true}.invoke(activity)
            }
            delay(400)
            var align=""
            scenario.onActivity {align=engine!!.property("video-align-y").orEmpty()}
            assertEquals(-1.0,align.toDouble(),0.001)
            val top=instrumentation.uiAutomation.takeScreenshot()
            File(context.cacheDir,"portrait-top.png").outputStream().use{top.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};top.recycle()
            scenario.onActivity {activity->MainActivity::class.java.getDeclaredMethod("leavePlayback").apply{isAccessible=true}.invoke(activity)}
            withTimeout(5000){while(context.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)delay(100)}
            withTimeout(5000){while(!engine!!.state.value.paused)delay(100)}
        }
    }
    @Test fun protectedRangeBridgeSeeksWithoutFullDownload() {
        val bytes=ByteArray(2048){(it%251).toByte()}
        var offset=-1L
        val remote=object:RemoteObject{override val name="fixture.bin";override val size=bytes.size.toLong()
            override fun open(start:Long):java.io.InputStream{offset=start;return ByteArrayInputStream(bytes,start.toInt(),bytes.size-start.toInt())}
            override fun close(){} }
        StreamBridge(remote).use {bridge->
            val c=URL(bridge.url).openConnection() as HttpURLConnection;c.setRequestProperty("Range","bytes=1100-1119")
            assertEquals(206,c.responseCode);assertEquals("bytes 1100-1119/2048",c.getHeaderField("Content-Range"))
            assertArrayEquals(bytes.copyOfRange(1100,1120),c.inputStream.use{it.readBytes()});assertEquals(1100L,offset);c.disconnect()
            val bad=URL(bridge.url.substringBeforeLast('/')+"/wrong-token").openConnection() as HttpURLConnection
            assertEquals(404,bad.responseCode);bad.disconnect()
        }
    }
    @Test fun jacredAndJackettResultsAreParsedWithoutLosingMagnet() {
        val magnet="magnet:?xt=urn:btih:0123456789012345678901234567890123456789"
        val native="""[{"title":"Neon fixture","magnet":"$magnet","size":2048,"sid":"12345678","seeders":4}]"""
        val jackett="""{"Results":[{"Title":"Neon fixture","MagnetUri":"$magnet","Size":2048,"Seeders":4}]}"""
        assertEquals(magnet,parseTorrentResults(native).single().link)
        assertEquals(4,parseTorrentResults(native).single().seeds)
        assertEquals(-1,parseTorrentResults("""[{"title":"Fixture","magnet":"$magnet","sid":"12345678"}]""").single().seeds)
        assertEquals(parseTorrentResults(native).single().size,parseTorrentResults(jackett).single().size)
        assertTrue(runCatching{parseTorrentResults("{\"error\":\"bad\"}")}.isFailure)
    }
    @Test fun searchAndAddUseConfiguredServerAndPreserveSaveChoice() {
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val pool=Executors.newSingleThreadExecutor()
        val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        val hash="0123456789012345678901234567890123456789";val magnet="magnet:?xt=urn:btih:$hash"
        val worker=pool.submit {
            repeat(3){server.accept().use{socket->
                socket.soTimeout=5000;val reader=socket.getInputStream().bufferedReader();val first=reader.readLine();var length=0
                while(true){val line=reader.readLine();if(line.isNullOrBlank())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                val body=CharArray(length);var count=0;while(count<length){val n=reader.read(body,count,length-count);if(n<0)break;count+=n}
                val raw=String(body,0,count);requests+="$first\n$raw"
                val response=if(first.startsWith("GET"))"""[{"title":"Neon fixture","magnet":"$magnet","size":2048,"sid":"12345678","seeders":3}]"""
                    else """{"hash":"$hash","file_stats":[{"id":1,"path":"Episode 2.mp4"}]}"""
                val bytes=response.toByteArray();socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
            }}
        }
        try {
            val base="http://127.0.0.1:${server.localPort}"
            val hit=TorrentApi.search(base,"", "Neon fixture",false).single()
            val connection=Connection("TorrServer",base,server.localPort)
            val transient=TorrentApi.add(connection,hit,false)
            assertEquals(1,TorrentApi.files(connection,hash,transient).single().index())
            assertTrue(connection.torr().stream(hash,TorrentApi.files(connection,hash,transient).single()).startsWith(base))
            TorrentApi.add(connection,hit,true)
            worker.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(requests[0].contains("search=Neon+fixture"))
            assertFalse(org.json.JSONObject(requests[1].substringAfter('\n')).getBoolean("save_to_db"))
            assertTrue(org.json.JSONObject(requests[2].substringAfter('\n')).getBoolean("save_to_db"))
        } finally {server.close();pool.shutdownNow()}
    }
    @Test fun phoneIconIsAdaptiveAndTvBannerHasWideAspect() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val icon=context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        val banner=context.packageManager.getApplicationBanner(context.packageName)!!
        val ratio=banner.intrinsicWidth.toFloat()/banner.intrinsicHeight
        assertTrue(ratio in 1.7f..1.85f)
    }
    @Test fun librarySurvivesRecreationAndKeepsFolderReferences() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="library-test"
        context.getSharedPreferences(name,0).edit().clear().commit()
        try {
            val library=MediaLibrary(context,name)
            val folder=MediaRef("content://fixture/tree/series/document/series","Сериал",true)
            val episode=MediaRef("content://fixture/episode2","Серия 2.mkv")
            val list=library.create("На выходные")
            library.save(list.copy(items=listOf(folder,episode)))
            library.favorite(folder,true)
            library.remember(episode,123.0,1200.0)
            val reopened=MediaLibrary(context,name)
            assertEquals(listOf(folder,episode),reopened.playlists().single().items)
            assertEquals(listOf(folder),reopened.favorites())
            assertEquals(123.0,reopened.history().single().resume,0.0)
            reopened.remember(episode,1195.0,1200.0)
            assertEquals(0.0,reopened.history().single().resume,0.0)
            reopened.remember(MediaRef("http://127.0.0.1:1234/token","Transient"),20.0,100.0)
            assertEquals(1,reopened.history().size)
            val sorted=listOf("Серия 10.mkv","Серия 2.mkv","Серия 1.mkv","Серия 999999999999999999999.mkv")
                .map{MediaRef("content://fixture/$it",it)}.sortedWith(naturalMediaOrder)
            assertEquals(listOf("Серия 1.mkv","Серия 2.mkv","Серия 10.mkv","Серия 999999999999999999999.mkv"),sorted.map{it.title})
            reopened.forget(episode.uri);assertTrue(reopened.history().isEmpty())
            reopened.delete(list.id);assertTrue(reopened.playlists().isEmpty())
        } finally {context.getSharedPreferences(name,0).edit().clear().commit()}
    }
    @Test fun deckScreensAndMediaStoreFoldersWorkWithManualRotation():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val created=mutableListOf<Uri>()
        val folderName="NeonQA-${System.currentTimeMillis()}"
        try {
            for(name in listOf("Серия 10.mp4","Серия 2.mp4")) {
                val values=android.content.ContentValues().apply{
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,name)
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,"Movies/$folderName/")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING,1)
                }
                val uri=context.contentResolver.insert(android.provider.MediaStore.Video.Media.getContentUri("external_primary"),values)!!
                created+=uri
                context.contentResolver.openOutputStream(uri)!!.use{output->instrumentation.context.assets.open("demo.mp4").use{it.copyTo(output)}}
                context.contentResolver.update(uri,android.content.ContentValues().apply{put(android.provider.MediaStore.Video.Media.IS_PENDING,0)},null,null)
            }
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.READ_MEDIA_VIDEO)
            val indexed=MediaCatalog(context).folders().first{it.media.title.contains(folderName)}
            assertEquals(listOf("Серия 2.mp4","Серия 10.mp4"),indexed.files.map{it.title})
            assertEquals(indexed.files,LocalFolders(context).expand(listOf(indexed.media)))
            assertEquals("Серия 10.mp4",sortMedia(indexed.files,"reverse").first().title)
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                var ready=false
                withTimeout(25000){while(!ready){scenario.onActivity{activity->ready=MainActivity::class.java.getDeclaredField("initialized").apply{isAccessible=true}.getBoolean(activity);activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};delay(100)}}
                withTimeout(5000){while(context.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)delay(100)}
                fun capture(name:String){val bitmap=instrumentation.uiAutomation.takeScreenshot();File(context.cacheDir,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
                delay(400);capture("deck-home")
                scenario.onActivity{activity->MainActivity::class.java.getDeclaredMethod("rotatePlayback").apply{isAccessible=true}.invoke(activity)}
                withTimeout(5000){while(context.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE)delay(100)}
                scenario.onActivity{activity->MainActivity::class.java.getDeclaredMethod("rotatePlayback").apply{isAccessible=true}.invoke(activity)}
                withTimeout(5000){while(context.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)delay(100)}
                scenario.onActivity{activity->MainActivity::class.java.getDeclaredMethod("fileExplorer").apply{isAccessible=true}.invoke(activity)}
                try{withTimeout(10000){while(instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Порядок")?.isNotEmpty()!=true)delay(150)}}catch(e:Exception){capture("qa-files-timeout");throw AssertionError("Files screen not visible",e)}
                capture("deck-folders")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                val magnet="magnet:?xt=urn:btih:0123456789012345678901234567890123456789"
                val hits=(1..500).map{TorrentHit("Тестовый сериал · Сезон $it · 1080p",magnet+"&dn=$it",18400000000L,501-it,"Тестовый трекер")}
                scenario.onActivity{activity->MainActivity::class.java.getDeclaredMethod("torrentResults",List::class.java,String::class.java).apply{isAccessible=true}.invoke(activity,hits,"Тестовый сериал")}
                try{withTimeout(10000){while(instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Смотреть")?.isNotEmpty()!=true)delay(150)}}catch(e:Exception){capture("qa-search-timeout");throw AssertionError("Search screen not visible",e)}
                capture("deck-search")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                scenario.onActivity{activity->MainActivity::class.java.getDeclaredMethod("settings").apply{isAccessible=true}.invoke(activity)}
                delay(250);capture("settings-deck")
                scenario.onActivity{activity->(MainActivity::class.java.getDeclaredField("settingsNav").apply{isAccessible=true}.get(activity) as? SettingsNavigator)?.close()}
            }
        }finally{created.forEach{context.contentResolver.delete(it,null,null)}}
    }
    @Test fun newMarkersAndTreeKeepDatesAndFoldersStable(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="freshness-test";val prefs=context.getSharedPreferences(name,0);prefs.edit().clear().commit()
        try {
            val now=1800000000000L
            val old=MediaRef("content://fixture/old","Old.mkv")
            val added=MediaRef("content://fixture/new","New.mkv")
            val markers=NewVideos(context,name)
            markers.observe(listOf(old),"folder",now)
            assertFalse(markers.isNew(old,7,now))
            markers.observe(listOf(old,added),"folder",now)
            assertTrue(markers.isNew(added,7,now+1000))
            markers.observe(listOf(old,added),"folder",now+86400000L*6)
            assertFalse(markers.isNew(added,7,now+86400000L*8))
            assertFalse(markers.isNew(added,0,now+1000))
            assertTrue(markers.isNew(old.copy(added=now-86400000L),7,now))
            val index=listOf(VideoFolder(MediaRef("neon-folder://media/external_primary%3A1","Movies/Series",true),listOf(old,added)))
            val catalog=MediaCatalog(context);val root=catalog.treeRoots(index).single()
            val movies=catalog.treeChildren(root,index).single()
            val series=catalog.treeChildren(movies,index).single()
            assertEquals("Movies",movies.title);assertEquals("Series",series.title)
            assertEquals(listOf("New.mkv","Old.mkv"),catalog.treeChildren(series,index).map{it.title})
        } finally {prefs.edit().clear().commit()}
    }
    @Test fun savedNetworkSecretsStayEncryptedAndWifiDoesNotMatchUnknown(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="network-store-test";val alias="neon-network-test-only"
        context.getSharedPreferences(name,0).edit().clear().commit()
        try {
            val c=Connection("SMB","fixture.invalid",445,"Series","tester","fixture-secret")
            val store=NetworkStore(context,name,alias)
            store.remember(c,true)
            val pin=store.pin(c,"Season 1","Series")
            store.savePin(pin.copy(wifi="Home"))
            val loaded=NetworkStore(context,name,alias)
            assertEquals("fixture-secret",loaded.resolve(c.copy(password="")).password)
            assertTrue(context.getSharedPreferences(name,0).all.values.none{it.toString().contains("fixture-secret")})
            val saved=loaded.pins().single()
            assertTrue(pinVisible(saved,"Home"));assertFalse(pinVisible(saved,"Work"));assertFalse(pinVisible(saved,null));assertTrue(pinVisible(saved,null,true))
            assertEquals("",saved.connection.password)
            loaded.forget(c)
            assertFalse(NetworkStore(context,name,alias).hasSaved(c))
        }finally{
            context.getSharedPreferences(name,0).edit().clear().commit()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);if(containsAlias(alias))deleteEntry(alias)}
        }
    }

}
