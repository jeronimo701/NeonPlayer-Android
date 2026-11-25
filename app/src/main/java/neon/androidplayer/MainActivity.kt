package neon.androidplayer

import android.app.AlertDialog
import android.app.UiModeManager
import android.content.*
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.*
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import kotlin.math.*

class MainActivity:ComponentActivity(),DialogBoundsHost {
    private val prefs by lazy { getSharedPreferences("neon",MODE_PRIVATE) }
    private lateinit var engine:PlayerEngine
    private lateinit var root:FrameLayout
    private lateinit var canvas:FrameLayout
    private lateinit var surface:SurfaceView
    private lateinit var home:LinearLayout
    private lateinit var info:TextView
    private var controls:FrameLayout?=null
    private var ambient:AmbientView?=null
    private var settingsNav:SettingsNavigator?=null
    private var libraryNav:SettingsNavigator?=null
    private var librarySide=Gravity.END
    private var levelMode=false
    private var brightnessSlider:LevelSlider?=null
    private var volumeSlider:LevelSlider?=null
    private var brightnessPermissionOffered=false
    private var levelsScreen:DeckScreen?=null
    private val audioManager by lazy{getSystemService(android.media.AudioManager::class.java)}
    private val syncLevels=object:Runnable{override fun run(){
        if(!initialized||isFinishing||!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))return
        if(levelMode||levelsScreen?.isShowing==true){brightnessSlider?.update(if(android.provider.Settings.System.canWrite(this@MainActivity))deviceBrightness()else window.attributes.screenBrightness.takeIf{it>=0}?:deviceBrightness());volumeSlider?.update(deviceVolume());handler.postDelayed(this,250)}
    }}
    private val boundDialogs=linkedMapOf<android.app.Dialog,()->Unit>()
    override fun registerBoundDialog(dialog:android.app.Dialog,resize:()->Unit){boundDialogs[dialog]=resize;handler.removeCallbacks(hide);handler.removeCallbacks(autoLock);autoLockAt=0}
    override fun unregisterBoundDialog(dialog:android.app.Dialog){boundDialogs.remove(dialog);if(boundDialogs.isEmpty())interacted()}
    private fun resizeDialogs(){boundDialogs.values.toList().forEach{it()}}
    private var foldTask:Job?=null
    private var foldHalf=false
    private var flexPlacementOverride=false
    private var foldBounds:android.graphics.Rect?=null
    private var libraryOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var duckTaps=0
    private var lastDuckTap=0L
    private var lockLayer:FrameLayout?=null
    private var playButton:ControlButton?=null
    private val speedButtons=mutableListOf<ControlButton>()
    private var observedSpeed=Double.NaN
    private var autoLockAt=0L
    private var lastControlArea=android.graphics.Rect()
    private var lockHint:TextView?=null
    private val clearLockHint=Runnable{lockHint?.visibility=View.GONE}
    private val autoLock=Runnable{if(viewing&&hidden&&!locked&&!controlGesture&&boundDialogs.isEmpty()&&!tv()&&prefs.getBoolean("autoLock",true)&&autoLockAt>0&&SystemClock.uptimeMillis()>=autoLockAt)lockPlayback()}
    private fun armAutoLock(){
        handler.removeCallbacks(autoLock);autoLockAt=0
        if(viewing&&hidden&&!locked&&!controlGesture&&boundDialogs.isEmpty()&&!tv()&&prefs.getBoolean("autoLock",true)&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){
            autoLockAt=SystemClock.uptimeMillis()+5000;handler.postDelayed(autoLock,5000)
        }
    }
    private var tape:SeekTape?=null
    private var horizontalSeek:SeekBar?=null
    private var seeking=false
    private var controlGesture=false
    private var controlsRebuildPending=false
    private var timeLabel:TextView?=null
    private val handler=Handler(Looper.getMainLooper())
    private var initialized=false
    private var locked=false
    private var hidden=false
    private var viewing=false
    private var lastAspect=0.0
    private var safeInsets=androidx.core.graphics.Insets.NONE
    private val thumbnails by lazy {ThumbnailCache(this)}
    private var homeCover:Pair<String,ImageView>?=null
    private var lastControlsHidden=true
    private var controlsHiding=false
    private var transitionToken=0
    private val covers=android.util.LruCache<String,android.graphics.Bitmap>(2)
    private var scale=100
    private var profile=""
    private var pendingWebAudio:String?=null
    private fun twoSided()=layout in listOf("both","both_reverse")
    private var layout="right"
    private var mode="auto"
    private var activeBridge:StreamBridge?=null
    private var connection:Connection?=null
    private var torr:Connection?=null
    private var sourceGeneration=0
    private var sponsorId:String?=null
    private var segments=emptyList<SponsorSegment>()
    private val skipped=mutableSetOf<String>()
    private var operation:Job?=null
    private var lastMessage:String?=null
    private var discoveryTask:Deferred<List<Discovery.Result>>?=null
    private var discovered=emptyList<Discovery.Result>()
    private var discoveryTime=0L
    private var discoveryNetwork:String?=null
    private var discoveryTaskNetwork:String?=null
    private var networkStatus:TextView?=null
    private var resumeTorr:(()->Unit)?=null
    private var resumeTorrHost:String?=null
    private val videoPermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){fileExplorer()}
    private var manualOrientation=false
    private var orientationJob:Job?=null
    private var explorerScreen:DeckScreen?=null
    private var wasPaused=true
    private var wasLoaded=false
    private val library by lazy {MediaLibrary(this)}
    private val folders by lazy {LocalFolders(this)}
    private val newVideos by lazy {NewVideos(this)}
    private val networkStore by lazy {NetworkStore(this)}
    private var pendingWifiPin:String?=null
    private var networkCallback:ConnectivityManager.NetworkCallback?=null
    private val refreshNetwork=Runnable{if(initialized&&!viewing){buildHome();startDiscovery()}}
    private val wifiPermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        val id=pendingWifiPin;pendingWifiPin=null
        if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)networkStore.pins().firstOrNull{it.id==id}?.let{bindPinWifi(it)}else toast("Без разрешения привязка не изменена; доступны все закрепы вручную")
    }
    private var currentMedia:MediaRef?=null
    private var currentCoverRef:MediaRef?=null
    private var queue=emptyList<MediaRef>()
    private var queueIndex=-1
    private var pendingResume:Double?=null
    private var chapterIntervals=emptyList<SkipInterval>()
    private var chaptersLoaded=false
    private var activeSkip:SkipInterval?=null
    private val skippedIntervals=mutableSetOf<String>()
    private var skipLayer:View?=null
    private var skipReturn:Double?=null
    private fun intervalKey()="skip."+java.security.MessageDigest.getInstance("SHA-256").digest((currentMedia?.uri?:engine.state.value.title).toByteArray()).joinToString(""){"%02x".format(it)}
    private var lastHistorySave=0L
    private var folderTarget:String?=null
    private var filesTarget:String?=null
    private val folderPicker=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        val target=folderTarget;folderTarget=null
        if(uri!=null){
            runCatching {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            work("Чтение папки",{folders.root(uri)}){folder->if(target==null){library.favorite(folder,true);browseFolder(folder)}else addToPlaylist(target,listOf(folder))}
        }
    }
    private val filesPicker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val target=filesTarget;filesTarget=null
        val files=uris.map{uri->runCatching {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};MediaRef(uri.toString(),displayName(uri))}.sortedWith(naturalMediaOrder)
        if(files.isNotEmpty())if(target==null)startQueue(files,0)else addToPlaylist(target,files)
    }

    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            openMedia(MediaRef(it.toString(), displayName(it)))
        }
    }
    private val subtitlePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { engine.command("sub-add",it.toString(),"select") }
    }
    private val audioPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { engine.command("audio-add",it.toString(),"select") }
    }
    private val hide=Runnable { if(viewing && !locked && !controlGesture && !engine.state.value.paused) setControlsHidden(true) }

    override fun onCreate(savedInstanceState:Bundle?) {
        AppAppearance.load(this)
        setTheme(if(AppAppearance.amber)R.style.AmberTheme else R.style.NeonTheme)
        super.onCreate(savedInstanceState)
        migrateSettings(prefs)
        UiSizing.percent=prefs.getInt("uiScale",100).coerceIn(80,140)
        UiSizing.iconStyle=prefs.getString(if(AppAppearance.amber)"amberIconStyle"else "iconStyle",if(AppAppearance.amber)"orange"else "duo")?.takeIf{it in iconFamilies}?:"duo"
        UiSizing.seekDown=prefs.getBoolean("seekForwardDown",false)
        volumeControlStream=android.media.AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).apply {hide(WindowInsetsCompat.Type.systemBars());systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}
        window.attributes=window.attributes.apply{layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES}
        root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)};canvas=FrameLayout(this)
        root.addView(canvas,FrameLayout.LayoutParams(-1,-1));setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->
            val safe=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.navigationBars())
            val changed=safeInsets!=safe;safeInsets=safe
            if(changed&&::home.isInitialized){if(initialized&&!viewing)buildHome()else if(homeFlexBounds()==null)home.setPadding(dp(20)+safe.left,dp(12)+safe.top,dp(20)+safe.right,dp(12)+safe.bottom)}
            if(initialized&&changed)rebuildControls()
            insets
        }
        layout=prefs.getString("layout","right")!!;mode=prefs.getString("mode","auto")!!
        home=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(20),dp(24),dp(20));setBackgroundColor(0xFF101114.toInt())}
        canvas.addView(home,FrameLayout.LayoutParams(-1,-1));info=label("Подготовка libmpv…",16);home.addView(label("NEON PLAYER",28));home.addView(info)
        engine=PlayerEngine(this)
        canvas.addOnLayoutChangeListener{_,l,t,r,b,ol,ot,or,ob->if(r-l!=or-ol||b-t!=ob-ot){restoreScale();if(initialized){picture();rebuildControls();resizeDialogs();if(!viewing)buildHome()}}}
        foldTask=lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){
            WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collectLatest{info->
                val feature=info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val wasPartial=foldHalf&&prefs.getBoolean("flexEnabled",true)
                if(foldHalf!=(feature?.state==FoldingFeature.State.HALF_OPENED))flexPlacementOverride=false
                foldHalf=feature?.state==FoldingFeature.State.HALF_OPENED
                foldBounds=feature?.takeIf{foldHalf&&it.orientation==FoldingFeature.Orientation.HORIZONTAL}?.bounds?.let{android.graphics.Rect(it)}
                val partial=foldHalf&&prefs.getBoolean("flexEnabled",true)
                if(initialized){
                    if(partial&&!wasPartial&&viewing&&!tv()&&!manualOrientation)requestedOrientation=libraryOrientation
                    restoreScale();picture();rebuildControls();resizeDialogs();if(!viewing)buildHome()
                    if(!partial&&wasPartial)scheduleOrientation()
                }
            }
        }}
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){
            if(locked){showLockHint();return}
            if(viewing){if(!hidden&&!controlsHiding)setControlsHidden(true)else leavePlayback()}else confirmExit()
        }})
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO){engine.prepareAssets()};engine.initialize();initialized=true
                surface=SurfaceView(this@MainActivity)
                canvas.addView(surface,0,FrameLayout.LayoutParams(-1,-1))
                surface.holder.addCallback(object:SurfaceHolder.Callback {
                    override fun surfaceCreated(holder:SurfaceHolder){engine.attach(holder.surface);picture()}
                    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){engine.resize(width,height)}
                    override fun surfaceDestroyed(holder:SurfaceHolder){engine.detach()}
                })
                ambient=AmbientView(this@MainActivity,surface).apply{positionSource={engine.state.value.position};pausedSource={engine.state.value.paused}}.also{canvas.addView(it,1,FrameLayout.LayoutParams(-1,-1))}
                restoreScale();buildHome();startDiscovery();handleIncoming(intent)
                networkCallback=object:ConnectivityManager.NetworkCallback(){
                    private fun refresh(){handler.removeCallbacks(refreshNetwork);handler.postDelayed(refreshNetwork,500)}
                    override fun onAvailable(network:Network){refresh()}
                    override fun onLost(network:Network){refresh()}
                    override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){refresh()}
                }.also{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(it)}
                engine.state.collectLatest { state ->
                    if(isFinishing)return@collectLatest
                    playButton?.update(if(state.paused)"play" else "pause",if(state.paused)"Воспроизвести" else "Пауза")
                    if(state.loaded){pendingWebAudio?.let{pendingWebAudio=null;engine.command("audio-add",it,"select")}}
                    if(state.loaded&&state.paused&&!wasPaused)cacheCover()
                    wasPaused=state.paused
                    if(state.speed!=observedSpeed){observedSpeed=state.speed;speedButtons.forEach{it.update(speedLabel(state.speed),"Скорость ${speedLabel(state.speed)}")};if(settingsNav?.screen?.heading?.text in listOf("Скорость","Быстрые настройки"))settingsNav?.refresh()}
                    tape?.update(state)
                    if(!seeking)horizontalSeek?.progress=if(state.duration>0)(state.position/state.duration*1000).toInt()else 0
                    timeLabel?.text="${clock(state.position)} / ${clock(state.duration)}"
                    if(state.loaded && state.duration>0 && pendingResume!=null){val start=pendingResume!!;pendingResume=null;if(start>0)engine.seek(start)}
                    if(state.loaded && SystemClock.elapsedRealtime()-lastHistorySave>=5000){saveProgress();lastHistorySave=SystemClock.elapsedRealtime()}
                    if(state.loaded&&state.duration>0){
                        if(!chaptersLoaded){chaptersLoaded=true;val rows=runCatching{JSONArray(engine.property("chapter-list")?:"[]")}.getOrDefault(JSONArray());chapterIntervals=chapterSkips((0 until rows.length()).map{val c=rows.getJSONObject(it);MediaChapter(c.optDouble("time",-1.0),c.optString("title"))},state.duration)}
                        val custom=runCatching{JSONArray(prefs.getString(intervalKey(),"[]"))}.getOrDefault(JSONArray())
                        val intervals=(0 until custom.length()).mapNotNull{val c=custom.getJSONObject(it);val a=c.optDouble("start",-1.0);val b=c.optDouble("end",-1.0);if(a>=0&&b>a&&b<=state.duration)SkipInterval(a,b,c.optString("kind","intro"))else null}+externalIntervals(state.duration)+chapterIntervals
                        val match=if(prefs.getString("skipMode","manual")=="off")null else intervals.firstOrNull{state.position>=it.start&&state.position<it.end-.25}
                        if(match!=activeSkip){activeSkip=match;renderSkip()}
                        if(match!=null&&!state.paused&&prefs.getString("skipMode","manual")=="auto"&&match.id !in skippedIntervals)performSkip(match)
                    }
                    if(state.eof && state.loaded && queueIndex>=0 && queueIndex<queue.lastIndex){playQueueItem(queueIndex+1,false);return@collectLatest}
                    if(state.loaded&&!wasLoaded){picture();scheduleOrientation()}
                    wasLoaded=state.loaded
                    if(abs(lastAspect-state.aspect)>.0001){lastAspect=state.aspect;rebuildControls();picture();scheduleOrientation()}
                    if(state.error!=null && state.error!=lastMessage){lastMessage=state.error;toast(state.error)}
                    if(state.loaded && !state.paused && segments.isNotEmpty() && prefs.getBoolean("sponsor",false)) {
                        val segment=segments.firstOrNull { it.id !in skipped && state.position>=it.start && state.position<it.end-.1 &&
                            (it.videoDuration<=0||abs(it.videoDuration-state.duration)<=2) && it.end<=state.duration+1 }
                        segment?.let {skipped+=it.id;engine.seek(it.end);toast("SponsorBlock: ${it.category}")}
                    }
                }
            }catch(cancel:CancellationException){throw cancel}
            catch(error:Throwable){info.text="Не удалось запустить libmpv: ${error.javaClass.simpleName}"}
        }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(initialized)handleIncoming(intent)}
    private fun handleIncoming(value:Intent){
        if(value.action==Intent.ACTION_VIEW)value.data?.let{openMedia(MediaRef(it.toString(),displayName(it)))}
        else if(value.action==Intent.ACTION_SEND)value.getStringExtra(Intent.EXTRA_TEXT)?.let { raw->
            if(raw.startsWith("magnet:?"))torrentActions(TorrentHit("Magnet",raw,-1,0,"")) else if(Uri.parse(raw).host?.removePrefix("www.") in listOf("kinopoisk.ru","imdb.com","m.imdb.com"))searchDialog(raw)else urlDialog(raw)
        }
    }
    private fun displayName(uri:Uri):String = runCatching {
        if(uri.scheme=="content")contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}
        else uri.lastPathSegment
    }.getOrNull()?.take(180) ?: "Видео"
    private fun tv()=mode=="tv"||(mode=="auto"&&getSystemService(UiModeManager::class.java).currentModeType==Configuration.UI_MODE_TYPE_TELEVISION)
    @Suppress("DEPRECATION")
    private fun restoreScale(){val c=resources.configuration;val base="scale.${windowManager.defaultDisplay.name}.${c.orientation}.${c.screenWidthDp/40}.${c.screenHeightDp/40}";val key=base+if(foldHalf&&prefs.getBoolean("flexEnabled",true))".half"else "";if(key!=profile){profile=key;scale=prefs.getInt(key,prefs.getInt(base,100)).coerceIn(50,150)}}
    private fun picture(){if(!initialized)return;applyPlaybackViewport();engine.picture(JSONObject().put("scale",scale/100.0*horizontalScale()).put("align",if(activeFlexBounds()!=null||tv()||verticalMode()||twoSided())0 else if(layout=="left")1 else -1)
        .put("fit",prefs.getString("$profile.fit","fit")).put("yalign",if(activeFlexBounds()!=null)0 else prefs.getInt("$profile.yalign",0)).put("mode",0).put("brightness",prefs.getInt("brightness",60)/100.0).put("edge",prefs.getInt("edge",50)/100.0)
        .put("blur",prefs.getInt("blur",24)).put("shader",prefs.getString("shader","native")));updateAmbient()}
    private fun foldRegion():android.graphics.Rect? {
        if(tv()||!foldHalf||!::canvas.isInitialized)return null
        val b=foldBounds?:return null;val location=IntArray(2);canvas.getLocationInWindow(location)
        val top=(b.top-location[1]).coerceIn(0,canvas.height);val bottom=(b.bottom-location[1]).coerceIn(top,canvas.height)
        return if(top>dp(100)&&canvas.height-bottom>dp(120))android.graphics.Rect(0,top,canvas.width,bottom)else null
    }
    private fun activeFlexBounds():android.graphics.Rect? = if(!viewing||flexPlacementOverride||!prefs.getBoolean("flexEnabled",true))null else foldRegion()
    private fun homeFlexBounds():android.graphics.Rect? = if(!viewing&&prefs.getBoolean("flexHomeEnabled",true))foldRegion()else null
    override fun deckArea():android.graphics.Rect?=(if(viewing)activeFlexBounds()else homeFlexBounds())?.let{android.graphics.Rect(0,it.bottom,canvas.width,canvas.height)}
    private fun applyPlaybackViewport(){
        if(!viewing||!::surface.isInitialized)return
        val h=activeFlexBounds()?.top?:-1
        if(surface.layoutParams.height!=h)surface.layoutParams=FrameLayout.LayoutParams(-1,h)
        ambient?.let{if(it.layoutParams.height!=h)it.layoutParams=FrameLayout.LayoutParams(-1,h)}
    }
    private fun horizontalReserve():Int {
        if(tv()||verticalMode()||activeFlexBounds()!=null||canvas.width<dp(280))return 0
        return minOf(dp(if(twoSided())224 else 144),(canvas.width*.45).toInt())
    }
    private fun horizontalScale():Double {
        val reserve=horizontalReserve();if(reserve==0)return 1.0
        val base=videoBox(canvas.width.toDouble(),canvas.height.toDouble(),engine.state.value.aspect,1.0,0.0,0.0,prefs.getString("$profile.fit","fit")!!)
        return minOf(1.0,(canvas.width-reserve)/base.width.coerceAtLeast(1.0))
    }
    private fun videoRectangle():android.graphics.RectF {
        val state=engine.state.value
        val flex=activeFlexBounds()
        val x=if(flex!=null||tv()||verticalMode()||twoSided())0.0 else if(layout=="left")1.0 else -1.0
        val b=videoBox(canvas.width.toDouble(),(flex?.top?:canvas.height).toDouble(),state.aspect,scale/100.0*horizontalScale(),x,if(flex!=null)0.0 else prefs.getInt("$profile.yalign",0).toDouble(),prefs.getString("$profile.fit","fit")!!)
        return android.graphics.RectF(b.left.toFloat(),b.top.toFloat(),(b.left+b.width).toFloat(),(b.top+b.height).toFloat())
    }
    private fun updateAmbient(){if(initialized&&::surface.isInitialized){
        val quality=ambientQuality(prefs.getString("ambientQuality","medium"))
        ambient?.configure(videoRectangle(),prefs.getInt("ambient",0),prefs.getInt("brightness",60)/100f,prefs.getInt("edge",50)/100f,prefs.getInt("blur",24),viewing&&engine.state.value.loaded,
            samples=prefs.getInt("ambientPoints",quality.points),refreshMs=quality.intervalMs,blurPasses=quality.passes,edgeBlend=prefs.getInt("edgeBlend",70))
    }}
    private fun leavePlayback(){
        handler.removeCallbacks(autoLock);autoLockAt=0;controlGesture=false;controlsRebuildPending=false;saveProgress();cacheCover();engine.pause();viewing=false;hidden=false;orientationJob?.cancel();manualOrientation=false;levelMode=false
        if(!tv())requestedOrientation=libraryOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderSkip();ambient?.pause();ambient?.visibility=View.GONE;home.visibility=View.VISIBLE;controls?.visibility=View.GONE;buildHome()
    }
    private fun confirmExit(){PlayerAlertBuilder(this).setTitle("Выйти из Neon Player?").setPositiveButton("Выйти"){_,_->saveProgress();finish()}.setNegativeButton("Остаться",null).show()}
    private fun duckTapped(view:View){
        val now=SystemClock.elapsedRealtime();duckTaps=if(now-lastDuckTap<1500)duckTaps+1 else 1;lastDuckTap=now
        view.animate().cancel();view.animate().rotationBy(if(duckTaps%2==0)-12f else 12f).scaleX(1.15f).scaleY(1.15f).setDuration(100).withEndAction{view.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(160).start()}.start()
        if(duckTaps>=7){duckTaps=0;duckCelebration()}
    }
    private fun duckCelebration(){
        val index=nextDuckMessage(prefs.getInt("lastDuckMessage",-1));prefs.edit().putInt("lastDuckMessage",index).apply()
        val image=ImageView(this).apply{setImageResource(R.mipmap.ic_b4);contentDescription="Танцующая уточка"}
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(28),dp(20),dp(16))}
        column.addView(image,LinearLayout.LayoutParams(dp(110),dp(110)))
        column.addView(deckText(duckMessages[index],17,bold=true).apply{gravity=Gravity.CENTER;setPadding(0,dp(14),0,0)})
        val bounce=android.animation.ObjectAnimator.ofFloat(image,View.TRANSLATION_Y,0f,-dp(22).toFloat(),0f).apply{duration=650;repeatCount=2}
        val wiggle=android.animation.ObjectAnimator.ofFloat(image,View.ROTATION,0f,-12f,12f,0f).apply{duration=650;repeatCount=2}
        val animation=android.animation.AnimatorSet().apply{playTogether(bounce,wiggle)}
        val dialog=PlayerAlertBuilder(this).setTitle("Кря! 🦆").setView(column).setPositiveButton("Продолжаем смотреть",null).create()
        dialog.setOnShowListener{animation.start()};dialog.setOnDismissListener{animation.cancel()};dialog.show()
    }
    private fun cacheCover(){
        val media=currentCoverRef?:return
        if(!initialized||!::surface.isInitialized||!engine.state.value.loaded||!surface.holder.surface.isValid)return
        val area=videoRectangle();if(!area.intersect(0f,0f,surface.width.toFloat(),surface.height.toFloat())||area.width()<1||area.height()<1)return
        val bitmap=android.graphics.Bitmap.createBitmap(320,(320*area.height()/area.width()).roundToInt().coerceIn(80,570),android.graphics.Bitmap.Config.ARGB_8888)
        val token=sourceGeneration
        try{PixelCopy.request(surface.holder.surface,android.graphics.Rect(area.left.roundToInt(),area.top.roundToInt(),area.right.roundToInt(),area.bottom.roundToInt()),bitmap,{result->
            if(result==PixelCopy.SUCCESS&&token==sourceGeneration){lifecycleScope.launch{
                withContext(Dispatchers.IO){runCatching{thumbnails.save(media.uri,bitmap)}};covers.remove(media.uri)
                homeCover?.takeIf{it.first==media.uri}?.second?.apply{scaleType=ImageView.ScaleType.CENTER_CROP;setImageBitmap(bitmap)}
            }}else bitmap.recycle()
        },handler)}catch(_:Exception){bitmap.recycle()}
    }
    private fun play(location:String,title:String,headers:Map<String,String> = emptyMap(),bridge:StreamBridge?=null,media:MediaRef?=null,resume:Double=0.0,coverKey:String?=null){
        if(!initialized){bridge?.close();return}
        if(youtubeHost(location)){bridge?.close();openYoutube(media?:MediaRef(location,title),resume);return}
        pendingWebAudio=null
        handler.removeCallbacks(autoLock);autoLockAt=0;controlGesture=false;controlsRebuildPending=false;libraryNav?.close();saveProgress();currentMedia=media;currentCoverRef=media?:coverKey?.let{MediaRef(it,title)};pendingResume=resume;if(media==null){queue=emptyList();queueIndex=-1}
        if(!viewing)libraryOrientation=requestedOrientation
        ambient?.reset()
        chapterIntervals=emptyList();chaptersLoaded=false;activeSkip=null;skippedIntervals.clear();skipReturn=null;skipLayer?.let{canvas.removeView(it)};skipLayer=null
        sourceGeneration++;manualOrientation=false;orientationJob?.cancel();segments=emptyList();skipped.clear();sponsorId=null
        engine.command("stop");val old=activeBridge;activeBridge=bridge
        engine.open(location,title,headers);applyAudio();old?.let{lifecycleScope.launch(Dispatchers.IO){it.close()}}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewing=true;hidden=false;locked=false;home.visibility=View.GONE;lastMessage=null
        rebuildControls();picture();interacted()
    }
    private fun interacted(){
        handler.removeCallbacks(hide)
        if(isFinishing||isDestroyed||locked||controlGesture||boundDialogs.isNotEmpty())return
        if(hidden)armAutoLock()else{handler.removeCallbacks(autoLock);autoLockAt=0;if(!tv()&&!levelMode)handler.postDelayed(hide,4000)}
    }
    private fun speedLabel(value:Double)=String.format(java.util.Locale.ROOT,"%.2f",value).trimEnd('0').trimEnd('.')+"×"
    private fun speedControl()=button(speedLabel(engine.property("speed")?.toDoubleOrNull()?:engine.state.value.speed),"Скорость"){speedDialog()}.also{speedButtons+=it}
    private fun scrubbing(active:Boolean){
        controlGesture=active;seeking=active;handler.removeCallbacks(hide);handler.removeCallbacks(autoLock);autoLockAt=0
        if(active){transitionToken++;controlsHiding=false;controls?.animate()?.cancel();controls?.apply{alpha=1f;translationY=0f;scaleX=1f;scaleY=1f}}
        else{interacted();if(controlsRebuildPending){controlsRebuildPending=false;rebuildControls()}}
    }
    private fun label(text:String,size:Int=15)=TextView(this).apply{this.text=text;textSize=size.toFloat()*UiSizing.percent/100f;setTextColor(INK);setPadding(dp(8),dp(6),dp(8),dp(6))}
    private fun buildHome(){
        home.removeAllViews();home.setBackgroundColor(DECK_BG)
        val fold=homeFlexBounds()
        val dashboard:LinearLayout
        val preview:LinearLayout?
        if(fold!=null){
            home.setPadding(0,0,0,0)
            val split=FrameLayout(this)
            val top=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20)+safeInsets.left,dp(16)+safeInsets.top,dp(20)+safeInsets.right,dp(12))}
            top.addView(deckText("Neon Player · Flex",20,bold=true))
            val previewScroll=ScrollView(this).apply{addView(top)}
            split.addView(previewScroll,FrameLayout.LayoutParams(-1,fold.top))
            dashboard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16)+safeInsets.left,dp(8),dp(16)+safeInsets.right,dp(8)+safeInsets.bottom)}
            split.addView(dashboard,FrameLayout.LayoutParams(-1,canvas.height-fold.bottom).apply{topMargin=fold.bottom})
            home.addView(split,LinearLayout.LayoutParams(-1,-1));preview=top
        }else{home.setPadding(dp(20)+safeInsets.left,dp(12)+safeInsets.top,dp(20)+safeInsets.right,dp(12)+safeInsets.bottom);dashboard=home;preview=null}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(ImageView(this).apply{setImageResource(R.mipmap.ic_b4);contentDescription="Уточка Neon Player";isFocusable=true;isClickable=true;setOnClickListener{duckTapped(this)}},LinearLayout.LayoutParams(dp(48),dp(48)))
        val brand=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),0,0,0)}
        brand.addView(deckText("Neon",if(fold!=null)19 else 23,bold=true))
        brand.addView(deckText("Player",if(fold!=null)19 else 23,bold=true).apply{
            setTextColor(if(AppAppearance.amber)Color.BLACK else ACCENT);setPadding(dp(8),dp(2),dp(8),dp(2))
            background=android.graphics.drawable.GradientDrawable().apply{setColor(if(AppAppearance.amber)ACCENT else AppAppearance.selected);cornerRadius=dp(8).toFloat()}
        },LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(6)})
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("settings","Настройки"){settings()},LinearLayout.LayoutParams(dp(48),dp(48)))
        dashboard.addView(header,LinearLayout.LayoutParams(-1,dp(56)))
        val scroll=ScrollView(this);val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(content);dashboard.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun section(title:String){content.addView(deckText(title,18,bold=true).apply{setPadding(0,dp(18),0,dp(12))})}
        val tabs=LinearLayout(this)
        listOf("Файлы" to {fileExplorer()},"Сеть" to {networkSources()},"Плейлисты" to {playlistsMenu()}).forEach{(title,action)->tabs.addView(deckButton(title,false,action),LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(6)})}
        content.addView(tabs)
        val recent=library.history().firstOrNull()
        if(engine.state.value.loaded||recent!=null){
            if(preview==null)section("Продолжить просмотр")else preview.addView(deckText("Продолжить просмотр",15,muted=true))
            val current=engine.state.value
            val title=if(current.loaded)current.title else recent!!.media.title
            val position=if(current.loaded)current.position else recent!!.position
            val duration=if(current.loaded)current.duration else recent!!.duration
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=deckSurface();setPadding(dp(16),dp(14),dp(16),dp(14));isFocusable=true;isClickable=true}
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;if(preview!=null)orientation=LinearLayout.VERTICAL}
            val coverMedia=if(current.loaded)currentCoverRef else recent?.media
            if(coverMedia!=null){
                val image=ImageView(this).apply{setImageResource(R.drawable.neon_duck);scaleType=ImageView.ScaleType.CENTER_INSIDE;setBackgroundColor(0xFF12161C.toInt());contentDescription="Кадр из видео"}
                row.addView(image,if(preview==null)LinearLayout.LayoutParams(dp(112),dp(80)).apply{rightMargin=dp(14)}else LinearLayout.LayoutParams(-1,minOf(dp(180),fold!!.top/3)).apply{bottomMargin=dp(8)})
                loadCover(coverMedia,image)
            }
            row.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(deckText(title,17,bold=true));addView(deckText("${clock(position)} / ${clock(duration)}",13,muted=true))},if(preview==null)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2))
            card.addView(row)
            card.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=1000;progress=if(duration>0)(position/duration*1000).toInt()else 0;progressTintList=android.content.res.ColorStateList.valueOf(if(AppAppearance.amber)ACCENT else 0xFFE52581.toInt())},LinearLayout.LayoutParams(-1,dp(4)).apply{topMargin=dp(10);bottomMargin=dp(12)})
            val resume={if(current.loaded){window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);viewing=true;home.visibility=View.GONE;hidden=false;picture();rebuildControls();scheduleOrientation();updateAmbient()}else openMedia(recent!!.media)}
            card.addView(deckButton("▶ Продолжить",true,resume));card.setOnClickListener{resume()};(preview?:content).addView(card)
        }
        if(preview!=null&&preview.childCount==1){preview.addView(ImageView(this).apply{setImageResource(R.drawable.neon_duck);contentDescription="Neon Player"},LinearLayout.LayoutParams(-1,minOf(dp(160),fold!!.top/3)))}
        section("Источники")
        val sources=listOf(Triple("На устройстве","device",{fileExplorer()}),Triple("Папки SMB","folder",{networkSources("SMB")}),Triple("Торренты · TorrServer","server",{networkSources("TorrServer")}),Triple("FTP / SFTP","transfer",{choose("FTP / SFTP",listOf("FTP","SFTP")){connectionDialog(if(it==0)"FTP"else "SFTP")}}))
        sources.chunked(2).forEach{pair->val row=LinearLayout(this);pair.forEach{(title,symbol,action)->row.addView(sourceTile(title,symbol,action),LinearLayout.LayoutParams(0,dp(62),1f).apply{rightMargin=dp(6);bottomMargin=dp(8)})};content.addView(row)}
        networkStatus=deckButton(networkSummary(),false){networkSources()};content.addView(networkStatus,LinearLayout.LayoutParams(-1,-2))
        content.addView(deckButton("＋ Ссылка · YouTube / поток / magnet",false){urlDialog()},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val favorites=library.favorites()
        if(favorites.isNotEmpty()){section("Избранные папки · удерживай, чтобы открепить");favorites.take(6).forEach{folder->content.addView(deckButton("☆  ${folder.title}",false){browseFolder(folder)}.apply{setOnLongClickListener{choose(folder.title,listOf("Открыть","Открепить с главного экрана")){i->if(i==0)browseFolder(folder)else{library.favorite(folder,false);buildHome()}};true}},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}}
        val homeSsid=currentSsid()
        val networkPins=networkStore.pins().filter{pinVisible(it,homeSsid)}
        if(networkPins.isNotEmpty()){section("Сетевые папки · удерживай для действий");networkPins.take(6).forEach{pin->content.addView(deckButton("▣  ${pin.name}",false){openPin(pin)}.apply{setOnLongClickListener{choose(pin.name,listOf("Открыть","Открепить с главного экрана","Переименовать / Wi-Fi")){i->when(i){0->openPin(pin);1->{networkStore.removePin(pin);buildHome()};2->pinActions(pin)}};true}},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}}
        content.addView(deckButton("Все сетевые закрепы",false){pinsMenu(true)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val nav=LinearLayout(this).apply{setPadding(0,dp(12),0,dp(4))}
        listOf(Triple("Главная","home",{buildHome()}),Triple("Поиск торрентов","search",{searchDialog()}),Triple("История","history",{historyMenu()})).forEachIndexed{index,(title,symbol,action)->nav.addView(sourceTile(title,symbol,action,index==0),LinearLayout.LayoutParams(0,dp(52),1f).apply{rightMargin=dp(6)})}
        dashboard.addView(nav);dashboard.addView(deckText(BuildConfig.VERSION_NAME,10,muted=true).apply{gravity=Gravity.END})
    }
    private fun sourceTile(title:String,symbol:String,action:()->Unit,active:Boolean=false)=LinearLayout(this).apply{
        gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4));background=deckSurface(active);isClickable=true;isFocusable=true;setOnClickListener{action()}
        addView(ControlButton(this@MainActivity,symbol){}.apply{isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(32),dp(38)))
        addView(deckText(title,13).apply{setPadding(dp(4),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun loadCover(media:MediaRef,target:ImageView){
        homeCover=media.uri to target
        covers.get(media.uri)?.let{target.scaleType=ImageView.ScaleType.CENTER_CROP;target.setImageBitmap(it);return}
        lifecycleScope.launch{
            val bitmap=withContext(Dispatchers.IO){thumbnails.read(media.uri)?:runCatching{
                if(Uri.parse(media.uri).scheme !in listOf("content","file"))return@runCatching null
                val retriever=android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity,Uri.parse(media.uri))
                    if(Build.VERSION.SDK_INT>=27)retriever.getScaledFrameAtTime(((library.history().firstOrNull{it.media.uri==media.uri}?.position?:1.0).coerceAtLeast(1.0)*1000000).toLong(),android.media.MediaMetadataRetriever.OPTION_CLOSEST,320,180) else null
                }finally{retriever.release()}
            }.getOrNull()}
            if(bitmap!=null){covers.put(media.uri,bitmap);target.scaleType=ImageView.ScaleType.CENTER_CROP;target.setImageBitmap(bitmap)}
        }
    }
    private fun button(symbol:String,description:String,action:()->Unit)=ControlButton(this,symbol){if(!locked){action();interacted()}}.apply{contentDescription=description}
    private fun panelColor()=if(prefs.getBoolean("transparentControls",false))Color.TRANSPARENT else Color.BLACK
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(4),dp(5),dp(4),dp(5));setBackgroundColor(panelColor())}
    private fun LinearLayout.addControl(view:View){addView(view,LinearLayout.LayoutParams(-1,0,1f))}
    private fun rebuildControls(){
        if(locked){controlGesture=false;controlsRebuildPending=false}
        if(controlGesture&&viewing&&!locked){controlsRebuildPending=true;return}
        if(!initialized||canvas.width==0)return
        applyPlaybackViewport();transitionToken++;controlsHiding=false;controls?.animate()?.cancel()
        controls?.let{canvas.removeView(it)};lockLayer?.let{canvas.removeView(it)}
        controls=null;lockLayer=null;playButton=null;speedButtons.clear();tape=null;horizontalSeek=null;timeLabel=null;seeking=false
        if(!viewing){skipLayer?.let{canvas.removeView(it)};skipLayer=null;return}
        renderSkip()
        val appearing=lastControlsHidden&&!hidden;lastControlsHidden=hidden
        val overlay=FrameLayout(this);controls=overlay;canvas.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        if(locked){overlay.visibility=View.GONE;installLock();return}
        val reveal=View(this).apply{setOnClickListener{setControlsHidden(!(hidden||controlsHiding));interacted()}}
        overlay.addView(reveal,FrameLayout.LayoutParams(-1,-1))
        if(hidden){val more=button("•••","Показать управление"){hidden=false;rebuildControls()};overlay.addView(more,FrameLayout.LayoutParams(dp(52),dp(52),Gravity.BOTTOM or Gravity.END));renderSkip();return}
        val state=engine.state.value
        val rect=videoRectangle();val videoW=rect.width().toDouble();val videoH=rect.height().toDouble()
        val flex=activeFlexBounds()
        val vertical=if(flex!=null)-1 else prefs.getInt("$profile.yalign",0)
        val freeY=if(flex!=null)(canvas.height-flex.bottom-safeInsets.bottom).coerceAtLeast(0)else (canvas.height-videoH-(if(vertical==1)safeInsets.top else safeInsets.bottom)).coerceAtLeast(0.0).toInt()
        val free=(canvas.width-videoW).coerceAtLeast(horizontalReserve().toDouble()).toInt()
        val two=twoSided()
        val enough=flex==null&&horizontalReserve()>0&&!tv()
        if(enough&&!verticalMode()&&!(vertical!=0&&freeY>=dp(120))){
            val rail=(if(two)free/2 else free).coerceAtMost(dp(if(two)104 else 200))
            lastControlArea=android.graphics.Rect(if(layout=="left")0 else canvas.width-rail,0,if(layout=="left")rail else canvas.width,canvas.height)
            fun divider(parent:LinearLayout){parent.addView(View(this).apply{setBackgroundColor(0xFF33363E.toInt())},LinearLayout.LayoutParams(-1,dp(1)).apply{leftMargin=dp(10);rightMargin=dp(10)})}
            fun brand()=deckText("NEON\nPLAYER",10,bold=true).apply{gravity=Gravity.CENTER;letterSpacing=.12f;setPadding(0,dp(4),0,dp(4))}
            fun rotate()=button("rotate","Повернуть экран; удерживать — как в системе"){rotatePlayback()}.apply{setOnLongClickListener{manualOrientation=true;orientationJob?.cancel();requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;toast("Ориентация как в системе");true}}
            fun lock()=button("lock","Блокировать касания"){lockPlayback()}
            val small=canvas.height<dp(380)
            val transportHeight=if(small)152 else 168
            fun centerTransport()=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                addView(button("-10","Назад 10 секунд"){engine.skip(-10.0)},LinearLayout.LayoutParams(-1,dp(if(small)40 else 48)))
                playButton=button(if(state.paused)"play"else "pause","Воспроизведение / пауза"){engine.togglePause()}
                addView(playButton,LinearLayout.LayoutParams(-1,dp(72)))
                addView(button("+10","Вперёд 10 секунд"){engine.skip(10.0)},LinearLayout.LayoutParams(-1,dp(if(small)40 else 48)))
            }
            if(two){
                val transport=FrameLayout(this).apply{
                    setBackgroundColor(panelColor())
                    addView(brand(),FrameLayout.LayoutParams(-1,dp(44),Gravity.TOP))
                    addView(centerTransport(),FrameLayout.LayoutParams(-1,dp(transportHeight),Gravity.CENTER))
                    val bottom=column().apply{
                        addView(button("files","Плейлист / очередь"){queueMenu()},LinearLayout.LayoutParams(-1,dp(48)))
                        addView(button("fit","Вписывание изображения"){fitMenu()},LinearLayout.LayoutParams(-1,dp(48)))
                    }
                    addView(bottom,FrameLayout.LayoutParams(-1,dp(96),Gravity.BOTTOM).apply{bottomMargin=dp(8)})
                }
                val tools=column().apply{
                    addView(button("settings","Настройки"){settings()},LinearLayout.LayoutParams(-1,dp(48)))
                    divider(this)
                    addView(button(if(levelMode)"tape"else "levels",if(levelMode)"Вернуться к перемотке"else "Яркость и громкость"){toggleLevels()},LinearLayout.LayoutParams(-1,dp(48)))
                    divider(this)
                    addView(seekOrLevels(),LinearLayout.LayoutParams(-1,0,1f))
                    divider(this);addView(speedControl(),LinearLayout.LayoutParams(-1,dp(48)))
                }
                overlay.addView(transport,FrameLayout.LayoutParams(rail,-1,if(layout=="both_reverse")Gravity.END else Gravity.START));overlay.addView(tools,FrameLayout.LayoutParams(rail,-1,if(layout=="both_reverse")Gravity.START else Gravity.END))
            }else{
                val pair=FrameLayout(this).apply{setBackgroundColor(panelColor())}
                val body=LinearLayout(this)
                val seekColumn=column().apply{setPadding(dp(3),dp(48),dp(3),dp(4));addView(seekOrLevels(),LinearLayout.LayoutParams(-1,-1))}
                val actions=FrameLayout(this).apply{setBackgroundColor(panelColor())}
                val compact=canvas.height<dp(480)
                val top=LinearLayout(this).apply{
                    orientation=if(compact)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    listOf(button("settings","Настройки воспроизведения"){settings()},button(if(levelMode)"tape"else "levels","Яркость / громкость, либо возврат к перемотке"){toggleLevels()}).forEach{v->addView(v,if(compact)LinearLayout.LayoutParams(0,dp(48),1f)else LinearLayout.LayoutParams(-1,dp(48)))}
                }
                actions.addView(top,FrameLayout.LayoutParams(-1,dp(if(compact)48 else 96),Gravity.TOP).apply{topMargin=dp(44)})
                actions.addView(centerTransport(),FrameLayout.LayoutParams(-1,dp(transportHeight),Gravity.CENTER))
                val bottom=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                if(compact){val row=LinearLayout(this);listOf(speedControl(),button("files","Очередь / плейлисты"){queueMenu()}).forEach{row.addView(it,LinearLayout.LayoutParams(0,dp(48),1f))};bottom.addView(row)}
                else{bottom.addView(speedControl(),LinearLayout.LayoutParams(-1,dp(48)));bottom.addView(button("files","Очередь / плейлисты"){queueMenu()},LinearLayout.LayoutParams(-1,dp(48)))}
                if(!small)bottom.addView(button("fit","Вписывание изображения"){fitMenu()},LinearLayout.LayoutParams(-1,dp(48)))
                actions.addView(bottom,FrameLayout.LayoutParams(-1,dp(if(small)48 else if(compact)96 else 144),Gravity.BOTTOM).apply{bottomMargin=dp(8)})
                if(layout=="left"){body.addView(actions,LinearLayout.LayoutParams(0,-1,.58f));body.addView(seekColumn,LinearLayout.LayoutParams(0,-1,.42f))}
                else{body.addView(seekColumn,LinearLayout.LayoutParams(0,-1,.42f));body.addView(actions,LinearLayout.LayoutParams(0,-1,.58f))}
                pair.addView(body,FrameLayout.LayoutParams(-1,-1))
                val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(panelColor());addView(brand(),LinearLayout.LayoutParams(0,-1,1f));addView(lock(),LinearLayout.LayoutParams(dp(40),-1));}
                pair.addView(header,FrameLayout.LayoutParams(-1,dp(44),Gravity.TOP))
                overlay.addView(pair,FrameLayout.LayoutParams(rail,-1,if(layout=="left")Gravity.START else Gravity.END))
            }
        }else{
            val inFreeSpace=flex!=null||vertical!=0&&freeY>=dp(120)
            val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(4),dp(10),dp(6));setBackgroundColor(panelColor())}
            val title=deckText(state.title.take(90),13).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
            panel.addView(title)
            val timeline=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            horizontalSeek=SeekBar(this).apply{max=1000;progress=if(state.duration>0)(state.position/state.duration*1000).toInt()else 0;progressTintList=android.content.res.ColorStateList.valueOf(ACCENT);thumbTintList=android.content.res.ColorStateList.valueOf(ACCENT)}
            timeline.addView(horizontalSeek,LinearLayout.LayoutParams(0,dp(32),1f))
            timeLabel=deckText("${clock(state.position)} / ${clock(state.duration)}",11,muted=true)
            timeline.addView(timeLabel);panel.addView(timeline)
            horizontalSeek!!.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){if(user)timeLabel?.text="${clock(engine.state.value.duration*p/1000)} / ${clock(engine.state.value.duration)}"}
                override fun onStartTrackingTouch(s:SeekBar){scrubbing(true)}
                override fun onStopTrackingTouch(s:SeekBar){engine.seek(engine.state.value.duration*s.progress/1000);scrubbing(false)}
            })
            val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(inFreeSpace&&vertical==-1&&freeY>=dp(240)){
                val quick=LinearLayout(this).apply{gravity=Gravity.CENTER}
                listOf(speedControl(),button("files","Очередь"){queueMenu()},button("levels","Яркость и громкость"){levelDialog()},button("fit","Вписывание"){fitMenu()},button("settings","Настройки"){settings()}).forEach{quick.addView(it,LinearLayout.LayoutParams(dp(56),dp(48)))}
                val transport=LinearLayout(this).apply{gravity=Gravity.CENTER}
                transport.addView(button("-10","Назад 10 секунд"){engine.skip(-10.0)},LinearLayout.LayoutParams(dp(64),dp(72)))
                playButton=button(if(state.paused)"play"else "pause","Воспроизведение / пауза"){engine.togglePause()}
                transport.addView(playButton,LinearLayout.LayoutParams(dp(88),dp(88)).apply{leftMargin=dp(24);rightMargin=dp(24)})
                transport.addView(button("+10","Вперёд 10 секунд"){engine.skip(10.0)},LinearLayout.LayoutParams(dp(64),dp(72)))
                // Move header/timeline below the flexible queue/spacer, keeping controls within thumb reach.
                panel.removeView(title);panel.removeView(timeline)
                if(freeY>=dp(340)&&queue.size>1){
                    val list=ListView(this).apply{divider=null;adapter=DeckRows(this@MainActivity,queue.mapIndexed{n,m->DeckRow(m.title,badge=if(n==queueIndex)"Сейчас"else "")});setOnItemClickListener{_,_,n,_->playQueueItem(n,true)}}
                    panel.addView(list,LinearLayout.LayoutParams(-1,0,1f))
                }else panel.addView(Space(this),LinearLayout.LayoutParams(-1,0,1f))
                panel.addView(quick,LinearLayout.LayoutParams(-1,dp(48)));panel.addView(title);panel.addView(timeline);panel.addView(transport,LinearLayout.LayoutParams(-1,dp(88)))
            }else{
                val quick=LinearLayout(this).apply{gravity=Gravity.CENTER}
                listOf(speedControl(),button("files","Очередь"){queueMenu()},button("levels","Яркость и громкость"){levelDialog()},button("settings","Настройки"){settings()}).forEach{quick.addView(it,LinearLayout.LayoutParams(dp(48),dp(40)))}
                panel.addView(quick,0)
                bar.gravity=Gravity.CENTER
                bar.addView(button("-10","Назад 10 секунд"){engine.skip(-10.0)},LinearLayout.LayoutParams(dp(60),dp(72)))
                playButton=button(if(state.paused)"play"else "pause","Воспроизведение / пауза"){engine.togglePause()}
                bar.addView(playButton,LinearLayout.LayoutParams(dp(80),dp(72)).apply{leftMargin=dp(16);rightMargin=dp(16)})
                bar.addView(button("+10","Вперёд 10 секунд"){engine.skip(10.0)},LinearLayout.LayoutParams(dp(60),dp(72)));panel.addView(bar)
                if(inFreeSpace&&freeY>dp(260)&&queue.size>1){
                    panel.addView(deckText("Серии / очередь",15,bold=true).apply{setPadding(dp(8),dp(14),0,dp(6))})
                    val list=ListView(this).apply{divider=null;adapter=DeckRows(this@MainActivity,queue.mapIndexed{n,m->DeckRow(m.title,badge=if(n==queueIndex)"Сейчас играет"else "")});setOnItemClickListener{_,_,n,_->playQueueItem(n,true)}}
                    panel.addView(list,LinearLayout.LayoutParams(-1,0,1f))
                }
            }
            val panelHeight=if(inFreeSpace)maxOf(freeY,dp(184))else dp(184)
            lastControlArea=if(vertical==1&&inFreeSpace)android.graphics.Rect(safeInsets.left,safeInsets.top,canvas.width-safeInsets.right,safeInsets.top+panelHeight)else android.graphics.Rect(safeInsets.left,canvas.height-safeInsets.bottom-panelHeight,canvas.width-safeInsets.right,canvas.height-safeInsets.bottom)
            overlay.addView(panel,FrameLayout.LayoutParams(-1,if(inFreeSpace)maxOf(freeY,dp(184)) else dp(184),if(vertical==1&&inFreeSpace)Gravity.TOP else Gravity.BOTTOM).apply{
                leftMargin=safeInsets.left;rightMargin=safeInsets.right
                if(vertical==1&&inFreeSpace)topMargin=safeInsets.top else bottomMargin=safeInsets.bottom
            })
        }
        if(appearing)when(prefs.getString("controlsAnimation","fade")){
            "fade"->{overlay.alpha=0f;overlay.animate().alpha(1f).setDuration(180).start()}
            "slide"->{overlay.translationY=dp(20).toFloat();overlay.alpha=0f;overlay.animate().translationY(0f).alpha(1f).setDuration(220).start()}
            "scale"->{overlay.scaleX=.97f;overlay.scaleY=.97f;overlay.alpha=0f;overlay.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()}
        }
        if(tv())playButton?.requestFocus()
        renderSkip()
    }
    private fun setControlsHidden(next:Boolean){
        if(next&&controlGesture)return
        controls?.animate()?.cancel();val token=++transitionToken
        if(!next){handler.removeCallbacks(autoLock);autoLockAt=0;controlsHiding=false;hidden=false;rebuildControls();return}
        val view=controls
        if(hidden||view==null||prefs.getString("controlsAnimation","fade")=="none"){hidden=true;rebuildControls();armAutoLock();return}
        controlsHiding=true
        val anim=view.animate().alpha(0f).setDuration(160)
        when(prefs.getString("controlsAnimation","fade")){"slide"->anim.translationY(dp(20).toFloat());"scale"->anim.scaleX(.97f).scaleY(.97f)}
        anim.withEndAction{if(token==transitionToken){hidden=true;controlsHiding=false;rebuildControls();armAutoLock()}}.start()
    }
    private fun deviceBrightness():Float=runCatching{android.provider.Settings.System.getInt(contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f}.getOrDefault(.5f).coerceIn(0f,1f)
    private fun deviceVolume():Float {val max=audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1);return audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()/max}
    private fun offerSystemBrightness(){
        if(android.provider.Settings.System.canWrite(this)||brightnessPermissionOffered)return
        brightnessPermissionOffered=true
        PlayerAlertBuilder(this).setTitle("Яркость телефона")
            .setMessage("Чтобы ползунок менял общую яркость телефона, Android требует разрешить изменение системных настроек. При ручной регулировке автояркость будет отключена. Без разрешения яркость изменяется только в окне плеера.")
            .setPositiveButton("Разрешить"){_,_->runCatching{startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:$packageName")))}.onFailure{toast("Открой системные настройки → Специальный доступ → Изменение системных настроек → Neon Player")}}
            .setNegativeButton("Пока только в плеере",null).show()
    }
    private fun toggleLevels(){levelMode=!levelMode;hidden=false;rebuildControls();if(levelMode)offerSystemBrightness();interacted()}
    private fun levels():LinearLayout {
        brightnessSlider=LevelSlider(this,"Яркость",if(android.provider.Settings.System.canWrite(this))deviceBrightness()else window.attributes.screenBrightness.takeIf{it>=0}?:deviceBrightness(),1f){value->
            if(android.provider.Settings.System.canWrite(this)){
                runCatching{
                    android.provider.Settings.System.putInt(contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    android.provider.Settings.System.putInt(contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,(value*255).roundToInt().coerceIn(1,255))
                    window.attributes=window.attributes.apply{screenBrightness=-1f}
                }.onFailure{window.attributes=window.attributes.apply{screenBrightness=value.coerceAtLeast(.01f)};toast("Нет доступа к общей яркости; изменена яркость окна")}
            }else window.attributes=window.attributes.apply{screenBrightness=value.coerceAtLeast(.01f)}
            interacted()
        }
        volumeSlider=LevelSlider(this,"Громкость",deviceVolume(),1f){value->
            val max=audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            runCatching{audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,(value*max).roundToInt(),0)}.onFailure{toast("Система не разрешила изменить громкость")};interacted()
        }
        engine.command("set","volume","100")
        handler.removeCallbacks(syncLevels);handler.post(syncLevels)
        return LinearLayout(this).apply{addView(brightnessSlider,LinearLayout.LayoutParams(0,-1,1f));addView(volumeSlider,LinearLayout.LayoutParams(0,-1,1f))}
    }
    private fun seekOrLevels():View {
        if(levelMode)return levels()
        return SeekTape(this){engine.seek(it);interacted()}.apply{
            update(engine.state.value);gesture={dragging->scrubbing(dragging)};tape=this
        }
    }
    private fun levelDialog(){
        val screen=DeckScreen(this,"Яркость и громкость","Громкость телефона; общая яркость — после системного разрешения")
        levelsScreen=screen;screen.list.visibility=View.GONE;screen.column.addView(levels(),LinearLayout.LayoutParams(-1,0,1f));screen.setOnDismissListener{levelsScreen=null;if(levelMode&&!isFinishing&&!isDestroyed)rebuildControls();interacted()};screen.show();handler.removeCallbacks(syncLevels);handler.post(syncLevels);offerSystemBrightness()
    }
    private fun lockPlayback(){handler.removeCallbacks(autoLock);handler.removeCallbacks(hide);autoLockAt=0;locked=true;hidden=true;rebuildControls()}
    private fun unlockPlayback(){handler.removeCallbacks(clearLockHint);locked=false;hidden=false;rebuildControls();interacted()}
    private fun showLockHint(){lockHint?.visibility=View.VISIBLE;handler.removeCallbacks(clearLockHint);handler.postDelayed(clearLockHint,1700)}
    private fun installLock(){
        val region=android.graphics.Rect(lastControlArea)
        if(!region.intersect(0,0,canvas.width,canvas.height)||region.width()<dp(48)||region.height()<dp(48)){
            val rail=horizontalReserve().coerceAtLeast(dp(96));region.set(if(layout=="left")0 else (canvas.width-rail).coerceAtLeast(0),0,if(layout=="left")rail.coerceAtMost(canvas.width)else canvas.width,canvas.height)
        }
        val detector=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean{showLockHint();return true}
            override fun onDoubleTap(e:MotionEvent):Boolean{unlockPlayback();return true}
        })
        val block=FrameLayout(this).apply{isClickable=true;setOnTouchListener{_,e->detector.onTouchEvent(e);true}}
        lockLayer=block;canvas.addView(block,FrameLayout.LayoutParams(-1,-1))
        val hint=deckText(if(tv())"Удерживай ОК для разблокировки"else "Двойной тап для разблокировки",12).apply{
            gravity=Gravity.CENTER;setTextColor(0xBBD2DEDE.toInt());setPadding(dp(8),dp(8),dp(8),dp(8));visibility=View.GONE;background=deckSurface()
        };lockHint=hint
        block.addView(hint,FrameLayout.LayoutParams((region.width()-dp(12)).coerceAtLeast(dp(48)),-2).apply{leftMargin=region.left+dp(6);topMargin=(region.centerY()-dp(22)).coerceAtLeast(region.top)})
        val unlock=ControlButton(this,"unlock"){showLockHint()}.apply{
            contentDescription=if(tv())"Удерживать ОК для разблокировки"else "Двойной тап для разблокировки"
            setOnTouchListener{_,e->detector.onTouchEvent(e);true}
            setOnLongClickListener{unlockPlayback();true}
        }
        block.addView(unlock,FrameLayout.LayoutParams(dp(48),dp(48)).apply{leftMargin=(region.centerX()-dp(24)).coerceAtLeast(region.left);topMargin=region.top+dp(6)})
        unlock.requestFocus()
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(!initialized)return super.dispatchKeyEvent(event)
        if(locked){if(event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_VOLUME_MUTE))return super.dispatchKeyEvent(event);return true}
        if(event.action==KeyEvent.ACTION_DOWN){
            if(event.keyCode==KeyEvent.KEYCODE_F8){cycleMode();return true}
            if(event.keyCode==KeyEvent.KEYCODE_MENU){settings();return true}
            if(viewing){
                when(event.keyCode){
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_SPACE->{engine.togglePause();hidden=false;rebuildControls();return true}
                    KeyEvent.KEYCODE_MEDIA_PLAY->{engine.command("set","pause","no");return true}
                    KeyEvent.KEYCODE_MEDIA_PAUSE->{engine.pause();return true}
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->if(hidden){engine.skip(if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT)-10.0 else 10.0);return true}
                    KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_CENTER->if(hidden){hidden=false;rebuildControls();playButton?.requestFocus();return true}
                }
            }
            interacted()
        };return super.dispatchKeyEvent(event)
    }
    private fun cycleMode(){mode=if(tv())"phone" else "tv";prefs.edit().putString("mode",mode).apply();picture();rebuildControls();if(!viewing)buildHome();toast(if(tv())"Android TV" else "Телефон / планшет")}
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);restoreScale();picture();rebuildControls();resizeDialogs();if(!viewing)buildHome()}
    override fun onStop(){handler.removeCallbacks(autoLock);autoLockAt=0;if(initialized){saveProgress();cacheCover();engine.pause();ambient?.pause()};super.onStop()}
    override fun onDestroy(){boundDialogs.keys.toList().forEach{runCatching{it.dismiss()}};boundDialogs.clear();libraryNav?.close();networkCallback?.let{runCatching{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)}};settingsNav?.close();ambient?.close();handler.removeCallbacksAndMessages(null);operation?.cancel();if(::engine.isInitialized)engine.destroy();activeBridge?.let{runCatching{it.close()}};super.onDestroy()}

    private fun saveProgress(){
        if(!initialized)return
        val media=currentMedia?:return;val state=engine.state.value
        if(state.loaded)library.remember(media,state.position,state.duration)
    }
    private fun openMedia(media:MediaRef){startQueue(listOf(media),0)}
    private fun startQueue(items:List<MediaRef>,index:Int){if(items.isEmpty()){toast("В папке нет медиафайлов");return};saveProgress();queue=items;queueIndex=index.coerceIn(0,items.lastIndex);playQueueItem(queueIndex,true)}
    private fun playQueueItem(index:Int,askResume:Boolean){
        val media=queue.getOrNull(index)?:return
        val saved=library.history().firstOrNull{it.media.uri==media.uri}?.resume?:0.0
        fun begin(position:Double){
            val source=runCatching{NetworkSource.read(media)}.getOrElse{errorDialog("Сохранённый источник повреждён");return}
            if(source==null){queueIndex=index;play(media.uri,media.title,media=media,resume=position);return}
            fun connected(c:Connection){
                val actual=source.copy(connection=c.copy(path=source.connection.path)).media(media.title,media.folder,media.bytes)
                if(index !in queue.indices)return
                queue=queue.toMutableList().also{it[index]=actual}
                if(c.type=="TorrServer")ensureTorr(c){queueIndex=index;play(c.torr().stream(source.hash,Sources.Entry(media.title,source.path,false,source.index)),media.title,c.torr().headers(),media=actual,resume=position)}
                else work("Подключение: ${media.title}",{StreamBridge(RemoteClients.file(c.copy(fingerprint=prefs.getString("ssh.${c.host}:${c.port}",c.fingerprint)!!),RemoteEntry(media.title,source.path,false,media.bytes)))},source=c,reconnect={connected(it.copy(path=source.connection.path))}){bridge->queueIndex=index;play(bridge.url,media.title,bridge=bridge,media=actual,resume=position)}
            }
            withSourceConnection(source.connection){connected(it)}
        }
        if(askResume&&saved>0)PlayerAlertBuilder(this).setTitle(media.title).setMessage("Продолжить с ${clock(saved)}?")
            .setPositiveButton("Продолжить"){_,_->begin(saved)}.setNeutralButton("С начала"){_,_->begin(0.0)}.setNegativeButton("Отмена",null).show()
        else begin(0.0)
    }
    private fun queueMenu(){
        if(viewing){
            if(libraryNav?.showing==true)return
            librarySide=if(layout=="left"||layout=="both")Gravity.START else Gravity.END
            libraryNav=SettingsNavigator(this,side=librarySide){libraryNav=null;interacted()}.also{it.push{queuePage()}}
        }else {val page=queuePage();choose(page.title,page.rows.map{it.title},page.select)}
    }
    private fun queuePage()=SettingsPage("Очередь и библиотека",listOf("Текущая очередь · ${queue.size}","Предыдущий файл","Следующий файл","Сохранить очередь как плейлист","Плейлисты","История просмотра","Открыть источник").map{DeckRow(it)},{i->when(i){
            0->if(queue.isEmpty())toast("Очередь пуста")else rowsWithHold("Очередь",queue.mapIndexed{index,m->"${if(index==queueIndex)"▶ " else ""}${m.title}"},{playQueueItem(it,true)}){index->
                choose(queue[index].title,listOf("Воспроизвести","Убрать из очереди","Поделиться")){action->when(action){0->playQueueItem(index,true);1->{if(index==queueIndex)toast("Текущий файл можно убрать после переключения")else{queue=queue.filterIndexed{n,_->n!=index};if(index<queueIndex)queueIndex--}};2->shareMedia(queue[index])}}
            }
            1->if(queueIndex>0)playQueueItem(queueIndex-1,true)else toast("Это первый файл")
            2->if(queueIndex<queue.lastIndex)playQueueItem(queueIndex+1,true)else toast("Это последний файл")
            3->if(queue.isEmpty())toast("Сначала открой файл или папку")else form("Сохранить очередь",listOf(Field("name","Название"))){v->val list=library.create(v.getValue("name"));library.save(list.copy(items=queue));toast("Плейлист сохранён")}
            4->playlistsMenu();5->historyMenu();6->sourcesMenu()
        }})
    private fun browseFolder(folder:MediaRef,parents:List<MediaRef> = emptyList()){
        prefs.getString("recursive.${folder.uri}",null)?.let{scanFolderView(folder,it);return}
        work("${folder.title} · файлы",{folders.children(folder).also{newVideos.observe(it,folder.uri)}}){all->
        val items=sortMedia(all,prefs.getString("fileSort","name")!!)
        val files=items.filterNot{it.folder}
        val favorite=library.favorites().any{it.uri==folder.uri}
        val screen=DeckScreen(this,folder.title,"${files.size} медиафайлов · удерживай элемент для действий")
        screen.action("Сортировка"){choose("Сортировка",listOf("Имя: 1, 2, 10","Имя: обратно","Новые сверху","По размеру")){i->prefs.edit().putString("fileSort",listOf("name","reverse","new","size")[i]).apply();screen.dismiss();browseFolder(folder,parents)}}
        screen.action(if(favorite)"★"else "☆"){library.favorite(folder,!favorite);screen.dismiss();browseFolder(folder,parents);if(!viewing)buildHome()}
        screen.action("Вид"){recursiveViewMenu(folder)}
        screen.action("▶ Все",true){screen.dismiss();startQueue(files,0)}
        screen.action("＋ Плейлист"){selectPlaylist(listOf(folder))}
        if(parents.isNotEmpty())screen.column.addView(deckButton("↰ ${parents.last().title}",false){screen.dismiss();browseFolder(parents.last(),parents.dropLast(1))},2)
        screen.rows(items.map{m->DeckRow((if(m.folder)"▣  "else "▶  ")+m.title,if(m.folder)"Папка"else listOfNotNull(m.bytes.takeIf{it>=0}?.let{size(it)},m.duration.takeIf{it>0}?.let{clock(it)},m.modified.takeIf{it>0}?.let{java.text.DateFormat.getDateInstance().format(java.util.Date(it))}).joinToString(" · "),newBadge(m))},{index->
            val m=items[index];screen.dismiss();if(m.folder)browseFolder(m,parents+folder)else startQueue(files,files.indexOf(m))
        },{index->val m=items[index];if(m.folder)folderActions(m)else choose(m.title,listOf("Воспроизвести","Добавить в плейлист","Поделиться")){action->when(action){0->{screen.dismiss();startQueue(files,files.indexOf(m))};1->selectPlaylist(listOf(m));2->shareMedia(m)}}})
        screen.show()
    }}
    private fun playlistsMenu(){if(viewing&&libraryNav?.showing!=true)leavePlayback();val lists=library.playlists();choose("Плейлисты",listOf("＋ Создать плейлист")+lists.map{"${it.name} · ${it.items.size}"}){i->
        if(i==0)form("Новый плейлист",listOf(Field("name","Название"))){v->playlistMenu(library.create(v.getValue("name")))}else playlistMenu(lists[i-1])
    }}
    private fun playlistMenu(list:SavedPlaylist){choose(list.name,listOf("Воспроизвести","Добавить файлы","Добавить папку","Состав / удалить элемент","Переименовать","Удалить плейлист")){i->when(i){
        0->preparePlaylist(list.items)
        1->{filesTarget=list.id;filesPicker.launch(arrayOf("video/*","audio/*"))}
        2->{folderTarget=list.id;folderPicker.launch(null)}
        3->rowsWithHold(list.name,list.items.map{if(it.folder)"▸ ${it.title} · папка"else it.title},{index->val item=list.items[index];if(item.folder)browseSavedFolder(item)else openMedia(item)}){index->
            PlayerAlertBuilder(this).setTitle("Убрать из плейлиста?").setMessage(list.items[index].title).setPositiveButton("Убрать"){_,_->library.save(list.copy(items=list.items.filterIndexed{n,_->n!=index}));playlistMenu(library.playlists().first{it.id==list.id})}.setNegativeButton("Отмена",null).show()
        }
        4->form("Название плейлиста",listOf(Field("name","Название",list.name))){v->library.save(list.copy(name=v.getValue("name").trim()))}
        5->PlayerAlertBuilder(this).setTitle("Удалить плейлист «${list.name}»?").setMessage("Файлы останутся на месте.").setPositiveButton("Удалить"){_,_->library.delete(list.id)}.setNegativeButton("Отмена",null).show()
    }}}
    private fun selectPlaylist(items:List<MediaRef>){val lists=library.playlists();choose("Добавить в плейлист",listOf("＋ Новый плейлист")+lists.map{it.name}){index->
        if(index==0)form("Название плейлиста",listOf(Field("name","Название"))){v->addToPlaylist(library.create(v.getValue("name")).id,items)}else addToPlaylist(lists[index-1].id,items)
    }}
    private fun addToPlaylist(id:String,items:List<MediaRef>){
        val list=library.playlists().firstOrNull{it.id==id}?:run{toast("Плейлист удалён");return}
        runCatching{library.save(list.copy(items=(list.items+items).distinctBy{it.uri}))}.onSuccess{toast("Добавлено: ${list.name}")}.onFailure{errorDialog(it.message?:"Не удалось сохранить плейлист")}
    }
    private fun historyMenu(){if(viewing&&libraryNav?.showing!=true)leavePlayback();saveProgress();val rows=library.history();if(rows.isEmpty()){toast("История пока пуста");return}
        rowsWithHold("История просмотра",listOf("Очистить историю")+rows.map{
            val date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it.updated))
            "${it.media.title}\n${clock(it.position)} / ${clock(it.duration)} · $date"
        },{index->if(index==0)PlayerAlertBuilder(this).setTitle("Очистить историю?").setPositiveButton("Очистить"){_,_->library.clearHistory()}.setNegativeButton("Отмена",null).show()else openMedia(rows[index-1].media)}){index->if(index>0){val m=rows[index-1].media;choose(m.title,listOf("Продолжить / с начала","Убрать из истории","Добавить в плейлист","Поделиться")){action->when(action){0->openMedia(m);1->library.forget(m.uri);2->selectPlaylist(listOf(m));3->shareMedia(m)}}}}
    }
    private fun rowsWithHold(title:String,rows:List<String>,click:(Int)->Unit,hold:(Int)->Unit){
        if(rows.isEmpty()){toast("Список пуст");return}
        val screen=DeckScreen(this,title,"Удерживай элемент для дополнительных действий",side=if(libraryNav?.showing==true)librarySide else null)
        screen.rows(rows.map{DeckRow(it.substringBefore('\n'),it.substringAfter('\n',""))},{screen.dismiss();click(it)},{screen.dismiss();hold(it)})
        screen.show()
    }
    private fun shareMedia(media:MediaRef){
        val uri=Uri.parse(media.uri)
        val source=runCatching{NetworkSource.read(media)}.getOrNull()
        if(source!=null){withSourceConnection(source.connection){c->
            work("Передача сетевого файла",{SharedNetworkFiles.publish(this,media.title){if(c.type=="TorrServer")HttpRangeObject(c.torr().stream(source.hash,Sources.Entry(media.title,source.path,false,source.index)),media.title,c.torr().headers())else RemoteClients.file(c,RemoteEntry(media.title,source.path,false,media.bytes))}},source=c){shared->
                val send=Intent(Intent.ACTION_SEND).apply{type="video/*";putExtra(Intent.EXTRA_STREAM,shared);clipData=ClipData.newRawUri(media.title,shared);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                runCatching{startActivity(Intent.createChooser(send,"Открыть в другом плеере"))}.onFailure{toast("Нет приложения для передачи")}
            }
        };return}
        if(uri.scheme=="file"){toast("Выбери файл через системный диалог, чтобы передать доступ другому приложению");return}
        val send=Intent(Intent.ACTION_SEND)
        if(uri.scheme=="content"){
            send.type=contentResolver.getType(uri)?:"video/*";send.putExtra(Intent.EXTRA_STREAM,uri);send.clipData=ClipData.newRawUri(media.title,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }else{send.type="text/plain";send.putExtra(Intent.EXTRA_TEXT,media.uri)}
        runCatching{startActivity(Intent.createChooser(send,"Поделиться: ${media.title}"))}.onFailure{toast("Нет приложения для передачи")}
    }

    private fun verticalMode()=prefs.getString("$profile.axis",if(prefs.getInt("$profile.yalign",0)!=0)"vertical"else "horizontal")=="vertical"
    private fun placementMenu(axis:String?=null){
        if(settingsNav==null)settingsNav=SettingsNavigator(this){settingsNav=null;interacted()}
        settingsNav?.push{
            if(axis==null)SettingsPage("Расположение видео и управления",listOf(
                DeckRow("По горизонтали","Управление справа, слева или по краям",if(!verticalMode())"Выбрана"else ""),
                DeckRow("По вертикали","Видео сверху / центр / снизу; очередь в свободном месте",if(verticalMode())"Выбрана"else "")
            ),{placementMenu(if(it==0)"horizontal"else "vertical")})
            else {
                val titles=if(axis=="horizontal")listOf("Управление справа · видео слева","Управление слева · видео справа","Управление по краям · видео в центре","По краям · наоборот: play/pause справа")else listOf("Видео сверху · управление и очередь снизу","Видео по центру","Видео снизу · управление и очередь сверху")
                val selected=if(axis=="horizontal")listOf("right","left","both","both_reverse").indexOf(layout)else listOf(-1,0,1).indexOf(prefs.getInt("$profile.yalign",0))
                SettingsPage(if(axis=="horizontal")"По горизонтали"else "По вертикали",titles.mapIndexed{i,t->DeckRow(t,badge=if(i==selected&&verticalMode()==(axis=="vertical"))"Выбрано"else "")},{i->
                    if(foldHalf)flexPlacementOverride=true
                    if(axis=="horizontal"){layout=listOf("right","left","both","both_reverse")[i];prefs.edit().putString("layout",layout).putString("$profile.axis","horizontal").putInt("$profile.yalign",0).apply()}
                    else prefs.edit().putString("$profile.axis","vertical").putInt("$profile.yalign",listOf(-1,0,1)[i]).apply()
                    picture();rebuildControls();interacted()
                })
            }
        }
    }
    private fun speedDialog(){val rates=listOf(.5,.75,1.0,1.25,1.5,1.75,2.0);selectOption("Скорость",rates.map{"$it×"},{val value=engine.property("speed")?.toDoubleOrNull()?:engine.state.value.speed;rates.indexOfFirst{abs(it-value)<.001}}){engine.speed(rates[it]);rebuildControls()}}
    private fun volumeDialog(){slider("Громкость",engine.property("volume")?.toDoubleOrNull()?.toInt()?:100,0,150){engine.command("set","volume",it.toString())}}
    private fun tracks(){choose("Дорожки",listOf("Аудиодорожка","Субтитры","Добавить внешний звук","Добавить субтитры")){index->when(index){0->trackList("audio","aid");1->trackList("sub","sid");2->audioPicker.launch(arrayOf("audio/*"));3->subtitlePicker.launch(arrayOf("*/*"))}}}
    private fun trackList(type:String,property:String){
        val rows=runCatching{JSONArray(engine.property("track-list")?:"[]")}.getOrDefault(JSONArray())
        val tracks=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.optString("type")==type}
        selectOption(if(type=="audio")"Аудиодорожки" else "Субтитры",listOf("Выключить")+tracks.map{"${it.optString("lang")} ${it.optString("title").ifBlank{it.optString("codec")}}"},{val selected=engine.property(property);tracks.indexOfFirst{it.optInt("id").toString()==selected}+1}){index->engine.command("set",property,if(index==0)"no" else tracks[index-1].getInt("id").toString())}
    }
    private fun settings(){
        if(settingsNav?.showing==true){settingsNav?.refresh();return}
        settingsNav=SettingsNavigator(this){settingsNav=null;interacted()}.also{nav->nav.push{if(viewing)quickSettingsPage()else mainSettingsPage()}}
    }
    private fun trackSummary(type:String,property:String):String {
        val value=engine.property(property)
        if(value=="no")return if(type=="sub")"Выключены"else "Выключена"
        val rows=runCatching{JSONArray(engine.property("track-list")?:"[]")}.getOrDefault(JSONArray())
        val track=(0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull{it.optString("type")==type&&(it.optBoolean("selected")||it.optInt("id").toString()==value)}
        return track?.let{listOf(it.optString("lang"),it.optString("title")).filter{it.isNotBlank()}.joinToString(" · ").ifBlank{"Дорожка ${it.optInt("id")}"}}
            ?:if(type=="sub")"Выключены или отсутствуют"else "Нет выбранной дорожки"
    }
    private fun interfaceMenu(){settingsNav?.push{SettingsPage("Интерфейс",listOf("Авто","Телефон / планшет","Android TV").mapIndexed{n,t->DeckRow(t,selected=mode==listOf("auto","phone","tv")[n])},{n->mode=listOf("auto","phone","tv")[n];prefs.edit().putString("mode",mode).apply();picture();rebuildControls();if(!viewing)buildHome()})}}
    private fun quickSettingsPage():SettingsPage {
        val entries=mutableListOf<Pair<DeckRow,(() -> Unit)?>>()
        fun section(title:String){entries+=DeckRow(title,section=true)to null}
        fun row(title:String,detail:String="",action:()->Unit){entries+=DeckRow(title,detail)to action}
        section("Воспроизведение")
        row("Скорость",speedLabel(engine.property("speed")?.toDoubleOrNull()?:engine.state.value.speed)){speedDialog()}
        row("Аудиодорожка",trackSummary("audio","aid")){trackList("audio","aid")}
        row("Субтитры",trackSummary("sub","sid")){trackList("sub","sid")}
        row("Пропуск интро и титров"){settingsNav?.push{skipSettingsPage()}}
        if(sponsorId!=null)row("SponsorBlock",if(prefs.getBoolean("sponsor",false))"Включён"else "Выключен"){sponsorSettings()}
        section("Изображение")
        val fit=when(prefs.getString("$profile.fit","fit")){"fill"->"Заполнить с обрезкой";"stretch"->"Растянуть";else->"Вписать целиком"}
        row("Вписывание и масштаб","$fit · $scale%"){fitMenu()}
        row("Шейдеры и апскейл",shaderCatalog().firstOrNull{it.id==prefs.getString("shader","native")}?.title?:"Без шейдеров"){shaders()}
        row("Подсветка полос",listOf("Выключена","Сияние от краёв","Размытая сцена")[prefs.getInt("ambient",0).coerceIn(0,2)]){settingsNav?.push{ambientPage()}}
        section("Управление")
        row("Расположение видео и управления"){placementMenu()}
        row("Режим интерфейса",if(tv())"Android TV"else "Телефон / планшет"){interfaceMenu()}
        row("Блокировать касания","Двойной тап для разблокировки"){settingsNav?.close();lockPlayback()}
        section("Дополнительно")
        row("Все настройки","Звук, оформление, сеть, файлы и сведения о приложении"){settingsNav?.push{mainSettingsPage()}}
        return SettingsPage("Быстрые настройки",entries.map{it.first},{i->entries[i].second?.invoke()},"Параметры текущего просмотра")
    }
    private fun mainSettingsPage():SettingsPage {
        val entries=mutableListOf<Pair<DeckRow,(() -> Unit)?>>()
        fun section(title:String){entries+=DeckRow(title,section=true)to null}
        fun row(title:String,detail:String="",action:()->Unit){entries+=DeckRow(title,detail)to action}
        section("Видео и апскейл")
        row("Вписывание и масштаб","${prefs.getString("$profile.fit","fit")} · $scale%"){fitMenu()}
        row("Шейдеры и апскейл","Назначение, качество и нагрузка"){shaders()}
        row("Подсветка полос",listOf("Выключена","Сияние от краёв","Размытая сцена")[prefs.getInt("ambient",0)]){settingsNav?.push{ambientPage()}}
        row("Ориентация экрана","Автоматически по видео или вручную"){orientationMenu()}
        if(viewing)row("Повернуть экран"){settingsNav?.close();rotatePlayback()}
        section("Звук и воспроизведение")
        row("Скорость воспроизведения",speedLabel(engine.property("speed")?.toDoubleOrNull()?:engine.state.value.speed)){speedDialog()}
        row("Аудио и субтитры","Дорожки файла и внешние файлы"){tracks()}
        row("Обработка звука","Нормализация, ночной режим, усиление и лимитер"){settingsNav?.push{audioPage()}}
        row("Пропуск интро и титров","Главы, свои интервалы и AniSkip"){settingsNav?.push{skipSettingsPage()}}
        row("SponsorBlock","Пропуск отмеченных сегментов YouTube"){sponsorSettings()}
        section("Торренты и сеть")
        row("TorrServer и JacRed","Воспроизведение торрентов и источник поиска"){settingsNav?.push{torrentSettingsPage()}}
        row("Сетевые папки и входы","SMB, FTP / SFTP, закрепы и сохранённые пароли"){choose("Сетевые папки",listOf("Закрепы по Wi-Fi","Все закрепы","Сохранённые входы")){n->when(n){0->pinsMenu();1->pinsMenu(true);2->networkLogins()}}}
        row("Ссылки и YouTube","Поддерживаемые адреса и обновление yt-dlp"){settingsNav?.push{webSettingsPage()}}
        section("Интерфейс и управление")
        row("Режим интерфейса",if(tv())"Android TV"else "Телефон / планшет"){interfaceMenu()}
        row("Расположение видео и управления","Горизонтально и вертикально"){placementMenu()}
        row("Оформление управления","Тема, иконки, анимации и блокировка"){settingsNav?.push{appearancePage()}}
        row("Раскладушка и Flex","Раздельное главное меню и управление под видео"){flexSettings()}
        if(viewing)row("Блокировать касания","Двойной тап для разблокировки"){settingsNav?.close();lockPlayback()}
        section("Файлы и библиотека")
        row("Файловый проводник","Вид, сортировка и метка NEW"){browserSettings()}
        section("Приложение")
        row("О приложении","Neon Player ${BuildConfig.VERSION_NAME}"){settingsNav?.push{aboutPage()}}
        return SettingsPage("Настройки",entries.map{it.first},{i->entries[i].second?.invoke()})
    }
    private fun flexSettings(){settingsNav?.push{SettingsPage("Раскладушка",listOf(
        DeckRow("Автоматический Flex при просмотре",if(prefs.getBoolean("flexEnabled",true))"Включён: видео сверху, управление снизу"else "Выключен",selected=prefs.getBoolean("flexEnabled",true)),
        DeckRow("Отдельное главное меню Flex",if(prefs.getBoolean("flexHomeEnabled",true))"Включено: превью сверху, источники снизу"else "Обычная главная",selected=prefs.getBoolean("flexHomeEnabled",true))
    ),{i->val key=if(i==0)"flexEnabled"else "flexHomeEnabled";flexPlacementOverride=false;prefs.edit().putBoolean(key,!prefs.getBoolean(key,true)).apply();restoreScale();picture();rebuildControls();resizeDialogs();if(!viewing)buildHome();scheduleOrientation()})}}
    private fun torrentSettingsPage()=SettingsPage("TorrServer и JacRed",listOf(
        DeckRow("TorrServer","Подключение, адрес сервера и авторизация"),
        DeckRow("JacRed · источник поиска",prefs.getString("jac","https://jac.red")?:"https://jac.red"),
        DeckRow("Поиск торрентов","Название, Кинопоиск или IMDb"),
        DeckRow("Серверы в локальной сети","Открыть уже найденные или запустить поиск")
    ),{i->when(i){0->connectionDialog("TorrServer"){torr=it;toast("TorrServer сохранён")};1->searchSettings();2->searchDialog();3->networkSources()}})
    private fun aboutPage()=SettingsPage("О приложении",listOf(
        DeckRow("Neon Player ${BuildConfig.VERSION_NAME}","Видеоплеер для телефона, планшета, раскладушки и Android TV.",iconDrawable=R.mipmap.ic_b4),
        DeckRow("Смотри откуда удобно","Файлы на устройстве, YouTube и прямые потоки, папки SMB / FTP / SFTP и торренты через TorrServer."),
        DeckRow("Настрой под себя","Темы Deck и Orange, выбор иконки, боковые панели, Flex, шейдеры и апскейл, подсветка полос, дорожки и обработка звука."),
        DeckRow("Библиотека и данные","История, позиции просмотра, плейлисты и настройки хранятся локально. Сохранённые пароли защищены Android Keystore. Поиск торрентов, YouTube, SponsorBlock и сетевые папки обращаются к выбранным сервисам; серверы ищутся в локальной сети."),
        DeckRow("Компоненты и лицензии","libmpv / mpv-android, yt-dlp, AndroidX, сетевые библиотеки и шейдеры. Открыть сведения.")
    ),{i->if(i==4)licenseMenu()},"Видео, управление и библиотека в одном приложении")
    private fun licenseMenu(){
        val files=assets.list("licenses").orEmpty().sorted()
        settingsNav?.push{SettingsPage("Компоненты и лицензии",listOf(DeckRow("Исходные проекты","mpv.io · github.com/mpv-android/mpv-android · github.com/yausername/youtubedl-android · github.com/yt-dlp/yt-dlp · sponsor.ajay.app"))+files.map{DeckRow(it.removeSuffix(".txt"))},{i->if(i>0){val text=assets.open("licenses/${files[i-1]}").bufferedReader().use{it.readText()};val body=deckText(text,13).apply{setTextIsSelectable(true);setPadding(dp(16),dp(12),dp(16),dp(12))};PlayerAlertBuilder(this).setTitle(files[i-1]).setView(ScrollView(this).apply{addView(body)}).setPositiveButton("Закрыть",null).show()}})}
    }
    private fun applyAudio(){val graph=audioGraph(prefs.getBoolean("audioNormalize",false),prefs.getBoolean("audioNight",false),prefs.getInt("audioGain",0),prefs.getBoolean("audioLimiter",false));engine.command("set","af",if(graph.isEmpty())""else "@neon-audio:lavfi=[$graph]")}
    private fun audioPage()=SettingsPage("Обработка звука",listOf(
        DeckRow("Нормализация",if(prefs.getBoolean("audioNormalize",false))"Включена: выравнивание громкости; может поднять фон"else "Выключена"),
        DeckRow("Ночной режим",if(prefs.getBoolean("audioNight",false))"Включён: тише взрывы, разборчивее диалоги"else "Выключен"),
        DeckRow("Усиление","${prefs.getInt("audioGain",0)} дБ"),DeckRow("Лимитер",if(prefs.getBoolean("audioLimiter",false))"Включён: ограничение пиков"else "Выключен"),DeckRow("Сброс обработки","Отключить фильтры и усиление")
    ),{i->if(i==2)slider("Усиление звука · дБ",prefs.getInt("audioGain",0),-12,18){prefs.edit().putInt("audioGain",it).apply();applyAudio()}else{if(i==4)prefs.edit().putBoolean("audioNormalize",false).putBoolean("audioNight",false).putBoolean("audioLimiter",false).putInt("audioGain",0).apply()else{val key=listOf("audioNormalize","audioNight","","audioLimiter")[i];prefs.edit().putBoolean(key,!prefs.getBoolean(key,false)).apply()};applyAudio()}})
    private fun skipSettingsPage()=SettingsPage("Интро и титры",listOf(DeckRow("Режим",mapOf("off" to "Выключено","manual" to "Кнопка пропуска","auto" to "Автоматически")[prefs.getString("skipMode","manual")]?:"Кнопка пропуска"),DeckRow("Добавить свой интервал","Для текущего файла; секунды или чч:мм:сс"),DeckRow("Удалить свои интервалы текущего файла"),DeckRow("Вернуться до последнего пропуска"),DeckRow("Источники меток","Названия глав Intro/Opening/Recap/Credits/Титры и ручные интервалы. Без данных кнопка не появляется. AniSkip подключается отдельно по точному MAL ID и серии."),DeckRow("AniSkip · загрузить метки","Нужно явно указать MAL ID и номер серии; отправляются ID и длительность"),DeckRow("Удалить метки AniSkip для этого файла")),{i->when(i){
        0->choose("Режим пропуска",listOf("Выключить","Показывать кнопку","Автоматически")){prefs.edit().putString("skipMode",listOf("off","manual","auto")[it]).apply();activeSkip=null;renderSkip()}
        1->if(!viewing||engine.state.value.duration<=0)toast("Сначала открой видео")else form("Новый интервал",listOf(Field("start","Начало · секунды или мм:сс",clock(engine.state.value.position)),Field("end","Конец · секунды или мм:сс"),Field("kind","intro / credits / recap","intro"))){v->
            val a=parseMediaTime(v.getValue("start"));val b=parseMediaTime(v.getValue("end"));val kind=v.getValue("kind");require(a<b&&b<=engine.state.value.duration&&kind in listOf("intro","credits","recap")){"Проверь границы и тип интервала"}
            val rows=JSONArray(prefs.getString(intervalKey(),"[]"));require(rows.length()<30);rows.put(JSONObject().put("start",a).put("end",b).put("kind",kind));prefs.edit().putString(intervalKey(),rows.toString()).apply();toast("Интервал сохранён")
        }
        2->{prefs.edit().remove(intervalKey()).apply();activeSkip=null;renderSkip()}
        3->skipReturn?.let{engine.seek(it);skipReturn=null}?:toast("Ещё ничего не пропущено")
        5->if(!viewing||engine.state.value.duration<=0)toast("Сначала открой видео")else form("AniSkip · точное сопоставление",listOf(Field("mal","Числовой ID MyAnimeList"),Field("episode","Номер серии"))){v->
            val mal=v.getValue("mal").toInt();val episode=v.getValue("episode").toInt();require(mal>0&&episode>0)
            val key=intervalKey();val generation=sourceGeneration;val duration=engine.state.value.duration
            work("AniSkip · загрузка",{AniSkip.fetch(mal,episode,duration)}){rows->
                if(generation==sourceGeneration){val value=JSONObject().put("duration",duration).put("mal",mal).put("episode",episode).put("items",JSONArray(rows.map{JSONObject().put("start",it.start).put("end",it.end).put("kind",it.kind)}));prefs.edit().putString("external.$key",value.toString()).apply();activeSkip=null;toast(if(rows.isEmpty())"Подходящих меток нет: проверь серию и длительность"else "Загружено интервалов: ${rows.size}")}
            }
        }
        6->{prefs.edit().remove("external.${intervalKey()}").apply();activeSkip=null;renderSkip()}
    }})
    private fun externalIntervals(duration:Double):List<SkipInterval> = runCatching{
        val saved=JSONObject(prefs.getString("external.${intervalKey()}","{}"));if(abs(saved.optDouble("duration",-100.0)-duration)>2)emptyList()else{
            val rows=saved.optJSONArray("items")?:JSONArray();(0 until rows.length()).mapNotNull{val o=rows.getJSONObject(it);val a=o.optDouble("start",-1.0);val b=o.optDouble("end",-1.0);if(a>=0&&b>a&&b<=duration)SkipInterval(a,b,o.optString("kind"))else null}
        }
    }.getOrDefault(emptyList())
    private fun performSkip(interval:SkipInterval){skipReturn=engine.state.value.position;skippedIntervals+=interval.id;engine.seek(interval.end);activeSkip=null;renderSkip()}
    private fun renderSkip(){
        skipLayer?.let{canvas.removeView(it)};skipLayer=null
        val interval=activeSkip?:return;if(!viewing||locked||prefs.getString("skipMode","manual")=="off")return
        val button=deckButton(interval.title,true){performSkip(interval)}
        val rect=videoRectangle();val flex=activeFlexBounds()
        val top=if(flex!=null)flex.bottom+dp(6)else if(rect.top>dp(60))safeInsets.top+dp(6)else (canvas.height-safeInsets.bottom-dp(54)).coerceAtLeast(0)
        canvas.addView(button,FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=top});skipLayer=button
    }
    private fun ambientPage():SettingsPage {
        val quality=ambientQuality(prefs.getString("ambientQuality","medium"));val points=prefs.getInt("ambientPoints",quality.points)
        return SettingsPage("Подсветка полос",listOf("Выключена","Сияние от краёв","Размытая сцена").mapIndexed{i,t->DeckRow(t,badge=if(prefs.getInt("ambient",0)==i)"Выбрано"else "",selected=prefs.getInt("ambient",0)==i)}+listOf(
            DeckRow("Пресет нагрузки",if(points==quality.points)quality.title else "Свой · на основе ${quality.title}"),
            DeckRow("Точки выборки цвета","$points по ширине · больше точек — подробнее цвета и выше нагрузка"),
            DeckRow("Яркость","${prefs.getInt("brightness",60)}%"),DeckRow("Размытие фона","${prefs.getInt("blur",24)}"),
            DeckRow("Ширина перехода у видео","${prefs.getInt("edge",50)}%"),DeckRow("Сглаживание границы","${prefs.getInt("edgeBlend",70)}% · плавное смешивание видео и подсветки")
        ),{i->when(i){
            0,1,2->{prefs.edit().putInt("ambient",i).apply();picture()}
            3->selectOption("Пресет подсветки",ambientQualities.map{"${it.title} · ${it.points} точек · обновление ${it.intervalMs} мс"},{val active=ambientQuality(prefs.getString("ambientQuality","medium"));val samples=prefs.getInt("ambientPoints",active.points);ambientQualities.indexOfFirst{it.id==active.id&&samples==it.points}}){n->val q=ambientQualities[n];prefs.edit().putString("ambientQuality",q.id).putInt("ambientPoints",q.points).apply();picture()}
            else->{val key=listOf("ambientPoints","brightness","blur","edge","edgeBlend")[i-4];val title=listOf("Точки выборки по ширине","Яркость подсветки","Размытие фона","Ширина перехода","Сглаживание границы")[i-4]
                slider(title,prefs.getInt(key,when(key){"ambientPoints"->quality.points;"brightness"->60;"blur"->24;"edgeBlend"->70;else->50}),if(key=="ambientPoints")16 else 0,if(key=="ambientPoints")128 else if(key=="blur")64 else 100){prefs.edit().putInt(key,it).apply();picture()}
            }
        }},"Больше точек, проходов размытия и обновлений — выше нагрузка. Настройки не меняют геометрию видео.")
    }
    private fun appearancePage()=SettingsPage("Оформление управления",listOf(DeckRow("Анимация появления",mapOf("fade" to "Проявление","slide" to "Сдвиг","scale" to "Масштабирование","none" to "Без анимации")[prefs.getString("controlsAnimation","fade")]?:"Проявление"),DeckRow("Подложка",if(prefs.getBoolean("transparentControls",false))"Прозрачная"else "Чёрная"),DeckRow("Яркость и громкость"),DeckRow("Масштаб интерфейса","${UiSizing.percent}% · отдельно от видео"),DeckRow("Стиль значков",iconFamilyName(UiSizing.iconStyle)),DeckRow("Направление перемотки",if(UiSizing.seekDown)"Вниз — вперёд"else "Вверх — вперёд"),DeckRow("Автоблокировка",if(prefs.getBoolean("autoLock",true))"Через 5 с после скрытия · двойной тап для разблокировки"else "Выключена",selected=prefs.getBoolean("autoLock",true)),DeckRow("Тема приложения",if(AppAppearance.amber)"Orange · чёрный и оранжевый"else "Deck · графит и бирюза"),DeckRow("Иконка приложения","Отдельно от темы · меняет значок в списке приложений")),{i->
        if(i==0)selectOption("Анимация управления",listOf("Проявление","Сдвиг","Масштабирование","Без анимации"),{listOf("fade","slide","scale","none").indexOf(prefs.getString("controlsAnimation","fade"))}){n->prefs.edit().putString("controlsAnimation",listOf("fade","slide","scale","none")[n]).apply();rebuildControls()}
        else if(i==1)selectOption("Подложка управления",listOf("Чёрная","Прозрачная"),{if(prefs.getBoolean("transparentControls",false))1 else 0}){n->prefs.edit().putBoolean("transparentControls",n==1).apply();rebuildControls()}
        else if(i==2)levelDialog()
        else if(i==3)slider("Масштаб интерфейса",UiSizing.percent,80,140){UiSizing.percent=it;prefs.edit().putInt("uiScale",it).apply();rebuildControls();if(!viewing)buildHome()}
        else if(i==4)iconStyleMenu()
        else if(i==5)selectOption("Перемотка вперёд",listOf("Прокручивать вверх","Прокручивать вниз"),{if(UiSizing.seekDown)1 else 0}){n->UiSizing.seekDown=n==1;prefs.edit().putBoolean("seekForwardDown",UiSizing.seekDown).apply();rebuildControls()}
        else if(i==6){prefs.edit().putBoolean("autoLock",!prefs.getBoolean("autoLock",true)).apply();armAutoLock()}
        else if(i==7)appThemeMenu()
        else launcherIconMenu()
    })
    private fun appThemeMenu(){
        settingsNav?.push{SettingsPage("Тема приложения",listOf(
            DeckRow("Deck","Графит, бирюзовые акценты и привычный DUO",selected=!AppAppearance.amber),
            DeckRow("Orange","Чёрный, белый и оранжевый · в стилистике Pornhub",selected=AppAppearance.amber)
        ),{i->AppAppearance.amber=i==1;setTheme(if(AppAppearance.amber)R.style.AmberTheme else R.style.NeonTheme);UiSizing.iconStyle=prefs.getString(if(AppAppearance.amber)"amberIconStyle"else "iconStyle",if(AppAppearance.amber)"orange"else "duo")?.takeIf{it in iconFamilies}?:"duo";prefs.edit().putString("appTheme",if(AppAppearance.amber)"amber"else "deck").apply();rebuildControls();if(!viewing)buildHome()})}
    }
    private fun launcherIconMenu(){
        settingsNav?.push{SettingsPage("Иконка приложения",LauncherIcons.options.map{DeckRow(it.title,selected=LauncherIcons.current(this)==it.id,iconDrawable=it.drawable)},{i->
            runCatching{LauncherIcons.select(this,LauncherIcons.options[i].id)}.onSuccess{toast("Иконка изменена · лаунчер может обновиться с задержкой")}.onFailure{toast("Не удалось изменить иконку")}
        },"Все 10 вариантов и оригинальная. Лаунчер может обновить значок с задержкой.")}
    }
    private fun iconStyleMenu(){
        if(settingsNav==null)settingsNav=SettingsNavigator(this){settingsNav=null;interacted()}
        settingsNav?.push{SettingsPage("Стиль значков",iconFamilies.map{id->DeckRow(iconFamilyName(id),badge=if(id==UiSizing.iconStyle)"Используется"else "",selected=id==UiSizing.iconStyle,previewStyle=id)},{i->UiSizing.iconStyle=iconFamilies[i];prefs.edit().putString(if(AppAppearance.amber)"amberIconStyle"else "iconStyle",UiSizing.iconStyle).apply();rebuildControls();if(!viewing)buildHome()})}
    }
    private fun fitMenu(){
        if(settingsNav==null)settingsNav=SettingsNavigator(this){settingsNav=null;interacted()}
        settingsNav?.push{SettingsPage("Вписывание изображения",listOf("Вписать целиком","Заполнить с обрезкой","Растянуть").mapIndexed{i,title->DeckRow(title,badge=if(prefs.getString("$profile.fit","fit")==listOf("fit","fill","stretch")[i])"Выбрано"else "")}+DeckRow("Масштаб: $scale%","Сохраняется для текущего экрана"),{i->
            if(i<3){prefs.edit().putString("$profile.fit",listOf("fit","fill","stretch")[i]).apply();picture();rebuildControls()}
            else slider("Масштаб видео · 100% вписать",scale,50,150){scale=it;prefs.edit().putInt(profile,it).apply();picture();rebuildControls()}
        })}
    }
    private fun shaders(){
        if(settingsNav==null){settings();shaders();return}
        settingsNav?.push{val presets=shaderCatalog();SettingsPage("Шейдеры / апскейл",presets.map{DeckRow(it.title,it.load,badge=if(prefs.getString("shader","native")==it.id)"Применён"else "")},{i->
            val preset=presets[i]
            settingsNav?.push{SettingsPage(preset.title,listOf(DeckRow("Для чего",preset.description),DeckRow("Нагрузка",preset.load+" · зависит от GPU и разрешения"),DeckRow("Применить",badge=if(prefs.getString("shader","native")==preset.id)"Уже выбран"else "")),{n->if(n==2){prefs.edit().putString("shader",preset.id).apply();picture();toast("Применено: ${preset.title}")}})}
        })}
    }
    private fun sponsorSettings(){
        if(settingsNav==null)settingsNav=SettingsNavigator(this){settingsNav=null;interacted()}
        settingsNav?.push{SettingsPage("SponsorBlock",listOf(
            DeckRow(if(prefs.getBoolean("sponsor",false))"Выключить"else "Включить"),DeckRow("Привязать текущее видео к YouTube",sponsorId?:"Без привязки"),DeckRow("Категории"),DeckRow("Сбросить привязку")
        ),{i->when(i){
            0->{prefs.edit().putBoolean("sponsor",!prefs.getBoolean("sponsor",false)).apply();loadSponsors()}
            1->form("YouTube ID или ссылка · для текущего видео",listOf(Field("url","URL или ID",sponsorId.orEmpty()))){values->val id=youtubeId(values.getValue("url"));require(id!=null){"Нужен корректный ID/URL YouTube"};require(viewing){"Сначала открой видео"};sponsorId=id;skipped.clear();loadSponsors()}
            2->{val all=SponsorApi.categories;val selected=all.map{it in prefs.getStringSet("categories",setOf("sponsor"))!!}.toBooleanArray();PlayerAlertBuilder(this).setTitle("Автоматически пропускать").setMultiChoiceItems(all.toTypedArray(),selected){_,which,on->selected[which]=on}.setPositiveButton("Сохранить"){_,_->prefs.edit().putStringSet("categories",all.filterIndexed{index,_->selected[index]}.toSet()).apply();loadSponsors()}.setNegativeButton("Отмена",null).show()}
            3->{sponsorId=null;segments=emptyList();skipped.clear();toast("Привязка очищена")}
        }})}
    }
    private fun loadSponsors(){
        segments=emptyList();val id=sponsorId?:run{toast("Укажи YouTube ID соответствующего видео. Для обычного фильма SponsorBlock не применяется");return}
        if(!prefs.getBoolean("sponsor",false))return
        val generation=sourceGeneration;val categories=prefs.getStringSet("categories",setOf("sponsor"))!!.toSet()
        work("SponsorBlock",{SponsorApi.fetch(id,categories)}){if(generation==sourceGeneration&&sponsorId==id){segments=it;toast("SponsorBlock: сегментов ${it.size}. При другой длительности пропуск отключён")}}
    }
    private fun sourcesMenu(){choose("Открыть",listOf("Файл","Прямая ссылка","Поиск торрентов","TorrServer","SMB","FTP","SFTP","Главный экран")){i->when(i){
        0->fileExplorer();1->urlDialog();2->searchDialog();3->networkSources("TorrServer");4->networkSources("SMB");5->connectionDialog("FTP");6->connectionDialog("SFTP")
        7->leavePlayback()
    }}}
    private fun linkHelp(){PlayerAlertBuilder(this).setTitle("Какие ссылки можно открыть?").setMessage(
        "YouTube: ссылка на отдельное видео, Shorts или трансляцию — youtube.com/watch?v=…, youtu.be/…, youtube.com/shorts/… или /live/…. Поток извлекается на устройстве; при блокировке сервиса нужен доступ к нему через твою сеть. Закрытые и платные ролики без входа не откроются.\n\nПрямой поток: HTTP(S)-адрес видео/аудиофайла (MP4, MKV, MP3 и другие форматы mpv), HLS (.m3u8), DASH (.mpd) или RTSP-поток камеры. Обычная страница сайта с плеером не является прямым потоком.\n\nТорренты: magnet:?… откроет действия TorrServer. Ссылку на .torrent добавляй через TorrServer. Файлы SMB / FTP / SFTP открывай в разделе «Сеть»."
    ).setPositiveButton("Понятно",null).show()}
    private fun urlDialog(initial:String=""){
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12))}
        val field=EditText(this).apply{hint="YouTube, прямая ссылка или magnet";setText(initial);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;maxLines=4}
        column.addView(field,LinearLayout.LayoutParams(-1,-2))
        column.addView(deckButton("?  Какие ссылки поддерживаются",false){linkHelp()},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        val dialog=PlayerAlertBuilder(this).setTitle("Открыть ссылку").setView(column).setPositiveButton("Открыть",null).setNegativeButton("Отмена",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val url=field.text.toString().trim()
            try{
                if(url.startsWith("magnet:?"))torrentActions(TorrentHit("Magnet",url,-1,0,""))
                else{val uri=Uri.parse(url);require(uri.scheme?.lowercase() in listOf("https","http","rtsp")&&!uri.host.isNullOrBlank()){ "Вставь полный адрес HTTP(S), RTSP или magnet" };if(youtubeHost(url))require(youtubeId(url)!=null){"Вставь ссылку на отдельное видео, Shorts или трансляцию YouTube"};openMedia(MediaRef(url,if(youtubeHost(url))"YouTube"else uri.lastPathSegment?:"Поток"))}
                dialog.dismiss()
            }catch(e:Exception){field.error=e.message}
        }};dialog.show()
    }
    private fun webSettingsPage()=SettingsPage("Ссылки и YouTube",listOf(
        DeckRow("Поддерживаемые ссылки","YouTube, прямые HTTP(S) / RTSP-потоки и magnet"),
        DeckRow("Обновить модуль YouTube","Обновить yt-dlp с официального сервера; полезно, если YouTube изменил выдачу")
    ),{i->if(i==0)linkHelp()else work("Обновление yt-dlp",{YoutubeLinks.update(this)}){toast("Модуль YouTube обновлён")}})
    private fun openYoutube(media:MediaRef,resume:Double){
        operation?.cancel()
        val generation=sourceGeneration
        val id="neon-youtube-"+java.util.UUID.randomUUID().toString()
        val progress=PlayerAlertBuilder(this).setTitle("Открываем YouTube").setMessage("Подготовка видеопотока…").setNegativeButton("Отмена",null).create()
        val job=lifecycleScope.launch(start=CoroutineStart.LAZY){
            try{
                val result=withTimeout(60000){runInterruptible(Dispatchers.IO){YoutubeLinks.resolve(this@MainActivity,media.uri,id)}}
                if(!isFinishing&&generation==sourceGeneration){play(result.video,result.title,result.headers,media=media.copy(title=result.title),resume=resume);pendingWebAudio=result.audio;sponsorId=youtubeId(media.uri);handler.post{if(prefs.getBoolean("sponsor",false))loadSponsors()}}
            }catch(timeout:TimeoutCancellationException){YoutubeLinks.cancel(id);errorDialog("YouTube не ответил за минуту. Проверь подключение или VPN и попробуй снова.")}
            catch(cancel:CancellationException){YoutubeLinks.cancel(id);throw cancel}
            catch(e:Exception){errorDialog("Не удалось открыть YouTube. Проверь доступ к сервису/VPN; при необходимости обнови модуль в настройках ссылок.\n\n"+(e.message?:"Ошибка извлечения потока").take(800))}
            finally{progress.dismiss();if(operation==currentCoroutineContext()[Job])operation=null}
        }
        progress.setOnCancelListener{job.cancel();YoutubeLinks.cancel(id)}
        progress.setOnShowListener{progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{job.cancel();YoutubeLinks.cancel(id);progress.dismiss()}}
        operation=job;progress.show();job.start()
    }
    private fun searchSettings(){form("Источник поиска",listOf(Field("base","Адрес JacRed",prefs.getString("jac","https://jac.red")!!),Field("key","API-ключ (если нужен)",searchKey,true))){v->
        val base=checkedBase(v.getValue("base"));prefs.edit().putString("jac",base).apply();searchKey=v.getValue("key")
        choose("API сервера",listOf("Native /api/v1.0/torrents","Jackett /api/v2.0/indexers/all/results")){prefs.edit().putBoolean("jackett",it==1).apply()}
    }}
    private var searchKey=""
    private fun searchDialog(initial:String=""){if(viewing)leavePlayback();form("Поиск торрентов",listOf(Field("query","Название, kp123456, tt1234567 или ссылка",initial))){v->
        val query=normalizeTorrentQuery(v.getValue("query"))
        work("Поиск торрентов",{TorrentApi.search(prefs.getString("jac","https://jac.red")!!,searchKey,query,prefs.getBoolean("jackett",false))}){hits->
            if(hits.isEmpty())toast("Ничего не найдено") else torrentResults(hits,query)
        }
    }}
    private fun torrentResults(hits:List<TorrentHit>,query:String){
        val screen=DeckScreen(this,"Поиск торрентов",query)
        var order="seeds";var tracker:String?=null;var quality:String?=null
        fun render(){
            val filtered=hits.distinctBy{it.link}.filter{(tracker==null||it.tracker==tracker)&&(quality==null||it.title.contains(quality!!,ignoreCase=true))}
            val sorted=when(order){"size"->filtered.sortedBy{it.size.takeIf{s->s>=0}?:Long.MAX_VALUE};"name"->filtered.sortedWith(Comparator{a,b->naturalCompare(a.title,b.title)});else->filtered.sortedByDescending{it.seeds}}
            screen.status.text="$query · ${sorted.size} результатов"
            screen.rows(sorted.map{hit->DeckRow(hit.title,"${size(hit.size)} · ${if(hit.seeds<0)"сиды: нет данных"else "сидов: ${hit.seeds}"}",hit.tracker,listOf(
                "▶ Смотреть" to {torrentActions(hit,0){screen.dismiss()}},"＋ TorrServer" to {torrentActions(hit,1)}
            ))},{index->torrentActions(sorted[index])})
        }
        screen.action("Сортировка"){choose("Сортировка",listOf("Больше сидов","Меньше размер","По названию")){order=listOf("seeds","size","name")[it];render()}}
        screen.action("Качество"){val options=listOf("Все","2160","1080","720","480");choose("Качество в названии",options){quality=options[it].takeIf{it!="Все"};render()}}
        screen.action("Трекер"){val options=listOf("Все трекеры")+hits.map{it.tracker}.filter{it.isNotBlank()}.distinct().sorted();choose("Трекер",options){tracker=options[it].takeIf{it!="Все трекеры"};render()}}
        render();screen.show()
    }
    private fun torrentActions(hit:TorrentHit,selected:Int?=null,onPlaying:()->Unit = {}){
        fun execute(choice:Int){withTorr{c->
        work(if(choice==0)"Добавление и получение файлов…" else "Добавление в TorrServer",{
            var summary=TorrentApi.add(c,hit,choice==1)
            if(choice==0){val hash=summary.getString("hash");var files=TorrentApi.files(c,hash,summary);var attempts=0
                while(files.isEmpty()&&attempts++<20){Thread.sleep(1000);summary=JSONObject(c.torr().let{jsonHttp(checkedBase(c.host)+"/torrents",JSONObject().put("action","get").put("hash",hash),it.headers())});files=TorrentApi.files(c,hash,summary)}
                if(files.isEmpty())throw java.io.IOException("TorrServer ещё не получил метаданные. Открой торрент в библиотеке позже")
            };summary
        }){summary->if(choice==1)toast("Добавлено в TorrServer") else {onPlaying();torrentFiles(c,summary)}}
        }}
        if(selected==null)choose(hit.title,listOf("Воспроизвести через TorrServer","Добавить в библиотеку TorrServer")){execute(it)}else execute(selected)
    }
    private fun withTorr(done:(Connection)->Unit){val value=torr;if(value!=null)ensureTorr(value){done(value)}else connectionDialog("TorrServer"){torr=it;done(it)}}
    private fun localTorr(c:Connection):Boolean {
        val host=Uri.parse(c.host).host?:return false
        if(host in setOf("localhost","127.0.0.1","::1","[::1]"))return true
        val manager=getSystemService(android.net.ConnectivityManager::class.java)
        return manager.allNetworks.any{n->manager.getLinkProperties(n)?.linkAddresses.orEmpty().any{it.address.hostAddress==host}}
    }
    private fun torrReachable(c:Connection):Result<String> = runCatching {
        val response=jsonHttp(checkedBase(c.host)+"/echo",headers=c.torr().headers(),timeoutMs=1500).trim()
        require(response.startsWith("MatriX.")||response.startsWith("TorrServer")||response.startsWith("1.1.")){"Адрес отвечает, но это не TorrServer"}
        response
    }
    private fun ensureTorr(c:Connection,done:()->Unit){
        work("Подключение к TorrServer",{torrReachable(c)}){result->
            if(result.isSuccess){done();return@work}
            val error=result.exceptionOrNull()?.message.orEmpty()
            if(!localTorr(c)||error.contains("401")||error.contains("403")){errorDialog("TorrServer недоступен: ${c.host}\n$error");return@work}
            PlayerAlertBuilder(this).setTitle("Локальный TorrServer не запущен")
                .setMessage("Открыть установленный TorrServe? После возврата в Neon Player подключение повторится. В TorrServe должен быть установлен сервер и выбран локальный режим.")
                .setPositiveButton("Открыть TorrServe"){_,_->
                    val launch=packageManager.getLaunchIntentForPackage("ru.yourok.torrserve")?:packageManager.getLeanbackLaunchIntentForPackage("ru.yourok.torrserve")
                    if(launch==null)errorDialog("TorrServe не установлен или не предоставляет экран запуска. Запусти свой локальный сервер вручную.")
                    else {resumeTorr=done;resumeTorrHost=c.host;torr=c;runCatching{startActivity(launch)}.onFailure{resumeTorr=null;resumeTorrHost=null;errorDialog("Не удалось открыть TorrServe")}}
                }.setNegativeButton("Отмена",null).show()
        }
    }
    override fun onResume(){
        super.onResume()
        if(initialized){if(android.provider.Settings.System.canWrite(this))window.attributes=window.attributes.apply{screenBrightness=-1f};if(!viewing)buildHome()else updateAmbient();handler.removeCallbacks(syncLevels);handler.post(syncLevels);if(hidden)armAutoLock()}
        val done=resumeTorr?:return;val host=resumeTorrHost;val c=torr?.takeIf{it.host==host}?:return
        resumeTorr=null;resumeTorrHost=null
        lifecycleScope.launch {
            var ready=false
            for(attempt in 0 until 10){if(withContext(Dispatchers.IO){torrReachable(c).isSuccess}){ready=true;break};delay(500)}
            if(ready)done()else errorDialog("Локальный TorrServer пока не отвечает: ${c.host}. Проверь установку серверного модуля и локальный режим в TorrServe, затем повтори подключение.")
        }
    }
    private fun browseTorr(c:Connection){if(viewing)leavePlayback();work("TorrServer",{TorrentApi.list(c)}){rows->if(rows.isEmpty())toast("Библиотека TorrServer пуста")else choose("TorrServer",rows.map{it.optString("title",it.optString("hash"))}){torrentFiles(c,rows[it])}}}
    private fun torrentFiles(c:Connection,summary:JSONObject){val hash=summary.optString("hash");if(hash.isBlank()){errorDialog("TorrServer не сообщил hash торрента");return};work("Файлы торрента",{TorrentApi.files(c,hash,summary)}){files->
        if(files.isEmpty())toast("Нет файлов для воспроизведения. Возможно, ещё загружаются метаданные")else choose("Выбери файл",files.map{it.label()}){index->startQueue(files.map{f->NetworkSource(c,f.path(),hash,f.index()).media(f.label(),bytes=f.size())},index)}
    }}
    private fun connectionDialog(type:String,initialHost:String?=null,initialConnection:Connection?=null,done:((Connection)->Unit)?=null){
        val prefix="conn.$type.";val defaultPort=when(type){"SMB"->445;"FTP"->21;"SFTP"->22;else->8090}
        val remembered=initialConnection?:networkStore.servers().firstOrNull{it.type==type&&it.host==(initialHost?:prefs.getString(prefix+"host",""))}
        val restored=remembered?.let{runCatching{networkStore.resolve(it)}.getOrNull()}
        form("$type · подключение",listOf(Field("host",if(type=="TorrServer")"http(s)://сервер:порт" else "Сервер / IP",initialHost?:remembered?.host?:prefs.getString(prefix+"host",if(type=="TorrServer")"http://127.0.0.1:8090" else "")!!),
            Field("port","Порт (для TorrServer — в адресе)",(remembered?.port?:prefs.getInt(prefix+"port",defaultPort)).toString()),
            Field("path",if(type=="SMB")"Общая папка (пусто: список)" else "Начальная папка",remembered?.path?:prefs.getString(prefix+"path","")!!),
            Field("user",if(type=="SMB")"Логин (пусто: гость)" else "Логин",remembered?.user?:prefs.getString(prefix+"user","")!!),Field("pass","Пароль",restored?.password.orEmpty(),true),Field("domain","Домен SMB",remembered?.domain?:prefs.getString(prefix+"domain","")!!),Field("save","Сохранить логин и пароль",(remembered?.let{networkStore.hasSaved(it)}?:false).toString(),toggle=true))){v->
            val host=v.getValue("host").trim();require(host.isNotBlank()){"Нужен сервер"};val port=v.getValue("port").toInt();require(port in 1..65535)
            if(type=="TorrServer")checkedBase(host)else require(!host.contains('@')&&!host.contains('/')&&!host.contains('\\')){"Укажи имя сервера/IP без протокола и логина"}
            val c=Connection(type,host,port,v.getValue("path").trim(),v.getValue("user"),v.getValue("pass"),v.getValue("domain"),prefs.getString("ssh.$host:$port","")!!)
            prefs.edit().putString(prefix+"host",host).putInt(prefix+"port",port).putString(prefix+"path",c.path).putString(prefix+"user",c.user).putString(prefix+"domain",c.domain).apply()
            networkStore.remember(c,v["save"]=="true")
            if(type=="TorrServer"){torr=c;ensureTorr(c){if(done!=null)done(c)else browseTorr(c)}}else{connection=c;if(done!=null)done(c)else browseRemote(c,if(type=="SMB")"" else c.path.ifBlank{"/"})}
        }
    }
    private fun preparePlaylist(items:List<MediaRef>){
        val source=items.mapNotNull{runCatching{NetworkSource.read(it)}.getOrNull()}.firstOrNull{!networkStore.known(it.connection)}
        if(source!=null){withSourceConnection(source.connection){c->
            val updated=items.map{m->val old=NetworkSource.read(m);if(old!=null&&old.connection==source.connection)old.copy(connection=c.copy(path=old.connection.path)).media(m.title,m.folder,m.bytes)else m}
            preparePlaylist(updated)
        };return}
        work("Подготовка плейлиста",{expandLibraryItems(items)}){startQueue(it,0)}
    }
    private fun recursiveViewMenu(folder:MediaRef){choose("Отображение папки",listOf("Дерево с раскрытием","Все видео во вложенных папках")){i->
        val source=runCatching{NetworkSource.read(folder)}.getOrNull()
        fun begin(){val mode=if(i==0)"tree"else "all";prefs.edit().putString("recursive.${folder.uri}",mode).apply();scanFolderView(folder,mode)}
        if(source!=null)withSourceConnection(source.connection){begin()}else begin()
    }}
    private fun scanFolderView(folder:MediaRef,mode:String){
        if(operation?.isActive==true){toast("Дождись текущей операции");return}
        val status=label("Чтение папок…")
        val progress=PlayerAlertBuilder(this).setTitle("${folder.title} · обход").setView(status).setNegativeButton("Отменить"){_,_->operation?.cancel()}.create()
        progress.setOnCancelListener{operation?.cancel()};progress.show()
        operation=lifecycleScope.launch{
            try {
                val snapshot=scanFolders(folder,{sourceChildren(it)}){n->status.text="Просмотрено папок: $n"}
                snapshot.children.forEach{(scope,items)->newVideos.observe(items,scope)}
                progress.dismiss();operation=null;showFolderSnapshot(snapshot,mode)
            }catch(cancel:CancellationException){throw cancel}catch(e:Exception){errorDialog(e.message?:"Папка недоступна. Проверь вход и разрешение на чтение")}finally{progress.dismiss();if(operation==currentCoroutineContext()[Job])operation=null}
        }
    }
    private fun showFolderSnapshot(snapshot:FolderSnapshot,initialMode:String){
        var mode=initialMode;val expanded=mutableSetOf<String>()
        val screen=DeckScreen(this,snapshot.root.title,"Долгое нажатие — действия; обновление перечитывает папки")
        fun rows(){
            val items=if(mode=="all")sortMedia(snapshot.files,prefs.getString("fileSort","name")!!).map{it to 0}else snapshot.tree(expanded)
            screen.status.text=if(mode=="all")"Все файлы: ${snapshot.files.size}"else "Дерево: ${snapshot.children.size} папок"
            val files=items.map{it.first}.filterNot{it.folder}
            screen.rows(items.map{(m,depth)->
                val hasNew=if(m.folder)snapshot.children[m.uri].orEmpty().any{newBadge(it).isNotBlank()}else newBadge(m).isNotBlank()
                val path=runCatching{NetworkSource.read(m)?.path}.getOrNull()?:Uri.parse(m.uri).lastPathSegment.orEmpty()
                DeckRow("${"   ".repeat(depth)}${if(m.folder)if(m.uri in expanded)"▾ "else "▸ "else "▶ "}${m.title}",if(mode=="all")path else if(m.folder)"Папка"else size(m.bytes),if(hasNew)"NEW"else "")
            },{i->val m=items[i].first;if(m.folder){if(!expanded.add(m.uri))expanded.remove(m.uri);rows()}else{screen.dismiss();startQueue(files,files.indexOf(m))}}, {i->
                val m=items[i].first
                choose(m.title,if(m.folder)listOf("Открыть папку","Воспроизвести папку","Добавить в плейлист","В избранное")else listOf("Воспроизвести","Добавить в плейлист","Поделиться")){a->
                    if(m.folder)when(a){0->{screen.dismiss();browseSavedFolder(m)};1->{screen.dismiss();startQueue(snapshot.children[m.uri].orEmpty().filterNot{it.folder},0)};2->selectPlaylist(listOf(m));3->{val src=NetworkSource.read(m);if(src!=null)pinFolder(src.connection,src.path,m.title)else library.favorite(m,true)}}
                    else when(a){0->{screen.dismiss();startQueue(files,files.indexOf(m))};1->selectPlaylist(listOf(m));2->shareMedia(m)}
                }
            })
        }
        screen.action("Вид"){mode=if(mode=="all")"tree"else "all";prefs.edit().putString("recursive.${snapshot.root.uri}",mode).apply();rows()}
        screen.action("Порядок"){choose("Сортировка",listOf("Имя: 1, 2, 10","Имя: обратно","Новые сверху","По размеру")){i->prefs.edit().putString("fileSort",listOf("name","reverse","new","size")[i]).apply();rows()}}
        screen.action("Обновить"){screen.dismiss();scanFolderView(snapshot.root,mode)}
        rows();screen.show()
    }
    private fun withSourceConnection(saved:Connection,done:(Connection)->Unit){
        if(!networkStore.known(saved)){connectionDialog(saved.type,initialConnection=saved){done(it.copy(path=saved.path))};return}
        runCatching{networkStore.resolve(saved)}.onSuccess{done(it.copy(fingerprint=prefs.getString("ssh.${it.host}:${it.port}",it.fingerprint)!!))}.onFailure{networkStore.forget(saved);connectionDialog(saved.type,initialConnection=saved){done(it.copy(path=saved.path))}}
    }
    private fun browseSavedFolder(media:MediaRef){val source=runCatching{NetworkSource.read(media)}.getOrNull();if(source==null)browseFolder(media)else withSourceConnection(source.connection){browseRemote(it,source.path)}}
    private fun sourceChildren(folder:MediaRef):List<MediaRef>{
        val source=NetworkSource.read(folder)?:return folders.children(folder)
        require(networkStore.known(source.connection)){"Открой сетевую папку и введи логин для этого сервера"}
        val c=networkStore.resolve(source.connection).let{it.copy(fingerprint=prefs.getString("ssh.${it.host}:${it.port}",it.fingerprint)!!)}
        return RemoteClients.list(c,source.path).map{e->
            val share=c.type=="SMB"&&c.path.isBlank()
            NetworkSource(if(share)c.copy(path=e.name)else c,if(share)""else e.path).media(e.name,e.folder,e.size)
        }
    }
    private fun expandLibraryItems(items:List<MediaRef>)=items.flatMap{if(it.folder)sourceChildren(it).filterNot{m->m.folder}else listOf(it)}.distinctBy{it.uri}.also{require(it.size<=2000){"В очереди больше 2000 файлов"}}
    private fun remoteCoverKey(c:Connection,path:String)="neon-cover://remote/"+java.security.MessageDigest.getInstance("SHA-256").digest("${c.type}|${c.host}|${c.port}|${c.path}|$path".toByteArray()).joinToString(""){"%02x".format(it)}
    private fun browseRemote(c:Connection,path:String){if(viewing)leavePlayback();connection=c;val root=NetworkSource(c,path).media(c.path.ifBlank{c.host}+"/"+path,true);prefs.getString("recursive.${root.uri}",null)?.let{scanFolderView(root,it);return};work("${c.type} · папка",{RemoteClients.list(c,path)},source=c){entries->
        val screen=DeckScreen(this,"${c.type} · ${c.path}/$path","Удерживай папку, чтобы добавить в избранное")
        screen.action("Закрепы"){pinsMenu()}
        screen.action("Сохранить эту папку"){pinFolder(c,path,if(path.isNotBlank())path.trimEnd('/','\\').substringAfterLast('/').substringAfterLast('\\')else c.path.ifBlank{c.host})}
        screen.action("Вид"){recursiveViewMenu(NetworkSource(c,path).media(c.path.ifBlank{c.host}+"/"+path,true))}
        screen.action("Вход"){connectionDialog(c.type,initialConnection=c)}
        val rows=listOf(DeckRow("↰ На уровень выше"))+entries.map{DeckRow((if(it.folder)"▸  "else "▶  ")+it.name,if(it.folder)"Папка"else size(it.size))}
        fun open(index:Int){
            if(index==0){val parent=path.trimEnd('/','\\').let{if(c.type=="SMB")it.substringBeforeLast('\\',"")else it.substringBeforeLast('/',"/")};screen.dismiss();if(path.isBlank()||path=="/")connectionDialog(c.type,initialConnection=c)else browseRemote(c,parent)}
            else{val e=entries[index-1];if(e.folder){screen.dismiss();if(c.type=="SMB"&&c.path.isBlank())browseRemote(c.copy(path=e.name),"")else browseRemote(c,e.path)}
                else {screen.dismiss();val files=entries.filterNot{it.folder};startQueue(files.map{NetworkSource(c,it.path).media(it.name,bytes=it.size)},files.indexOf(e))}}
        }
        screen.rows(rows,{open(it)},{index->if(index>0){
            val e=entries[index-1];val child=if(c.type=="SMB"&&c.path.isBlank())c.copy(path=e.name)else c
            val childPath=if(child!=c)""else e.path
            val media=NetworkSource(child,childPath).media(e.name,e.folder,e.size)
            choose(e.name,if(e.folder)listOf("Открыть","В избранное","Добавить в плейлист","Воспроизвести папку")else listOf("Воспроизвести","Добавить в плейлист","Поделиться")){i->
                if(e.folder)when(i){0->open(index);1->pinFolder(child,childPath,e.name);2->selectPlaylist(listOf(media));3->work("Подготовка папки",{sourceChildren(media)}){screen.dismiss();startQueue(it.filterNot{it.folder},0)}}
                else when(i){0->open(index);1->selectPlaylist(listOf(media));2->shareMedia(media)}
            }
        }})
        screen.show()
    }}
    private fun pinFolder(c:Connection,path:String,title:String){
        val pin=networkStore.pin(c,path,title);toast("Закреплено: ${pin.name}");if(!viewing)buildHome()
    }
    private fun openPin(pin:NetworkPin){
        if(!networkStore.known(pin.connection)){
            connectionDialog(pin.connection.type,initialConnection=pin.connection){c->networkStore.savePin(pin.copy(connection=c.copy(password="")));openPin(pin.copy(connection=c))};return
        }
        runCatching{networkStore.resolve(pin.connection)}.onSuccess{c->val active=c.copy(fingerprint=prefs.getString("ssh.${c.host}:${c.port}",c.fingerprint)!!);connection=active;browseRemote(active,pin.path)}.onFailure{
            errorDialog(it.message?:"Введи пароль заново");networkStore.forget(pin.connection)
        }
    }
    @Suppress("DEPRECATION")
    private fun currentSsid():String?=runCatching{
        val wifi=applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        wifi.connectionInfo?.ssid?.trim('"')?.takeIf{it.isNotBlank()&&it!=android.net.wifi.WifiManager.UNKNOWN_SSID&&it!="0x"}
    }.getOrNull()
    private fun pinsMenu(showAll:Boolean=false){
        val ssid=currentSsid();val all=networkStore.pins();val pins=all.filter{pinVisible(it,ssid,showAll)}
        val screen=DeckScreen(this,"Сетевые закрепы",if(showAll)"Все сети · удерживай папку для настройки"else "${ssid?:"Wi-Fi не определён"} · непривязанные папки видны везде")
        screen.action(if(showAll)"По Wi-Fi"else "Показать все"){screen.dismiss();pinsMenu(!showAll)}
        screen.action("Входы"){networkLogins()}
        screen.rows(pins.map{DeckRow(it.name,"${it.connection.type} · ${it.connection.host} · ${it.connection.path}/${it.path}",it.wifi?:"Все сети")},{index->screen.dismiss();openPin(pins[index])},{index->pinActions(pins[index])})
        if(pins.isEmpty())screen.status.text="Закрепов для этой сети нет. Добавь папку удержанием в SMB или выбери «Показать все»."
        screen.show()
    }
    private fun pinActions(pin:NetworkPin){choose(pin.name,listOf("Открыть","Переименовать","Привязать к текущей Wi-Fi","Показывать во всех сетях","Обновить логин / пароль","Удалить закреп")){i->when(i){
        0->openPin(pin)
        1->form("Название закрепа",listOf(Field("name","Название",pin.name))){v->networkStore.savePin(pin.copy(name=v.getValue("name").trim()));if(!viewing)buildHome()}
        2->bindPinWifi(pin)
        3->{networkStore.savePin(pin.copy(wifi=null));if(!viewing)buildHome();toast("Папка доступна во всех сетях")}
        4->connectionDialog(pin.connection.type,initialConnection=pin.connection){c->networkStore.savePin(pin.copy(connection=c.copy(password="")));openPin(pin.copy(connection=c))}
        5->PlayerAlertBuilder(this).setTitle("Удалить закреп?").setMessage("Сетевая папка останется на сервере.").setPositiveButton("Удалить"){_,_->networkStore.removePin(pin);if(!viewing)buildHome()}.setNegativeButton("Отмена",null).show()
    }}}
    private fun bindPinWifi(pin:NetworkPin){
        if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            PlayerAlertBuilder(this).setTitle("Определение текущей Wi-Fi сети").setMessage("Android требует разрешение на точное местоположение, чтобы сообщить приложению имя Wi-Fi. Координаты не запрашиваются и не сохраняются. Это нужно только для фильтра закрепов.")
                .setPositiveButton("Продолжить"){_,_->pendingWifiPin=pin.id;wifiPermissions.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION))}.setNegativeButton("Отмена",null).show();return
        }
        val ssid=currentSsid()?:run{errorDialog("Android не сообщает имя Wi-Fi. Проверь подключение, точное разрешение и включённую службу местоположения. Закреп остаётся доступен через «Показать все».");return}
        networkStore.savePin(pin.copy(wifi=ssid));if(!viewing)buildHome();toast("${pin.name} · $ssid")
    }
    private fun networkLogins(){val servers=networkStore.servers();choose("Сохранённые подключения",servers.map{"${it.type} · ${it.host} · ${it.user.ifBlank{"гость"}}${if(networkStore.hasSaved(it))" · пароль сохранён"else " · без сохранённого пароля"}"}){index->
        val c=servers[index];choose("${c.type} · ${c.host}",listOf("Открыть / изменить вход","Забыть сохранённый пароль")){i->if(i==0)connectionDialog(c.type,initialConnection=c)else{networkStore.forget(c);toast("Сохранённый пароль удалён")}}
    }}
    private fun networkSummary()=when {
        discoveryTask?.isActive==true->"⌁  Найти серверы в сети · поиск…"
        discovered.isNotEmpty()->"⌁  Серверы в сети · найдено ${discovered.size}"
        else->"⌁  Найти серверы в сети"
    }
    private fun localNetwork():android.net.Network? {
        val manager=getSystemService(android.net.ConnectivityManager::class.java)
        val preferred=manager.activeNetwork
        fun local(n:android.net.Network):Boolean {val c=manager.getNetworkCapabilities(n)?:return false;return c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)}
        return preferred?.takeIf{local(it)}?:manager.allNetworks.firstOrNull{local(it)}
    }
    private fun startDiscovery(force:Boolean=false):Deferred<List<Discovery.Result>> {
        val network=localNetwork();val key=network?.toString()
        discoveryTask?.takeIf{it.isActive}?.let{if(discoveryTaskNetwork==key&&!force)return it else it.cancel()}
        if(!force&&discoveryTime>0&&key==discoveryNetwork)return CompletableDeferred(discovered)
        if(key!=discoveryNetwork){discovered=emptyList();discoveryTime=0}
        discoveryTaskNetwork=key
        networkStatus?.text=if(discovered.isEmpty())"Найти серверы в сети · поиск…"else "Серверы в сети · найдено ${discovered.size} · обновление…"
        return lifecycleScope.async {
            val manager=getSystemService(ConnectivityManager::class.java)
            val found=try{withContext(Dispatchers.IO){Discovery.search(network,network?.let{manager.getLinkProperties(it)})}}catch(cancel:CancellationException){throw cancel}catch(_:Exception){emptyList()}
            if(localNetwork()?.toString()!=key)return@async emptyList()
            discovered=found;discoveryTime=SystemClock.elapsedRealtime();discoveryNetwork=key
            networkStatus?.text=if(found.isEmpty())"Найти серверы в сети"else "Серверы в сети · найдено ${found.size}";found
        }.also{discoveryTask=it}
    }
    private fun networkSources(type:String?=null,force:Boolean=false){
        val screen=DeckScreen(this,when(type){"SMB"->"Папки SMB";"TorrServer"->"Торренты · TorrServer";else->"Серверы в сети"},"SMB — папки NAS/ПК. TorrServer — раздачи торрентов. Поиск серверов показывает оба типа.")
        fun render(all:List<Discovery.Result>,searching:Boolean=false){
            val found=all.filter{type==null||(if(it.torr())"TorrServer"else "SMB")==type}
            screen.status.text=if(searching)"${found.size} найдено · обновление в фоне…"else if(found.isEmpty())"Серверов этого типа не найдено. Нажми «Обновить» или укажи адрес вручную."else "Найдено: ${found.size}. Сохранённые результаты; повторный поиск — «Обновить»."
            screen.rows(found.map{DeckRow(if(it.torr())"TorrServer · торренты"else "SMB · папки NAS / ПК",it.address(),it.detail())},{index->val item=found[index];connectionDialog(if(item.torr())"TorrServer"else "SMB",initialHost=item.address())})
        }
        screen.action("Обновить"){screen.dismiss();networkSources(type,true)}
        screen.action("Вручную"){choose("Подключение",if(type==null)listOf("SMB","TorrServer","FTP","SFTP")else listOf(type)){i->connectionDialog(if(type==null)listOf("SMB","TorrServer","FTP","SFTP")[i]else type)}}
        if(type==null||type=="TorrServer")screen.action("Локальный"){connectionDialog("TorrServer",initialHost="http://127.0.0.1:8090")}
        val hasCache=discoveryTime>0&&discoveryNetwork==localNetwork()?.toString()
        if(hasCache)render(discovered,force)else screen.status.text="Поиск SMB и TorrServer в этой сети…"
        screen.show()
        if(hasCache&&!force)return
        lifecycleScope.launch{val found=startDiscovery(force).await();if(screen.isShowing)render(found)}
    }
    private fun discover(){networkSources(force=true)}
    private fun mediaAccess():Boolean {
        val permission=if(Build.VERSION.SDK_INT>=33)android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT>=34&&checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED)
    }
    private fun requestVideoAccess(){videoPermissions.launch(when{
        Build.VERSION.SDK_INT>=34->arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO,android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT>=33->arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        else->arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    })}
    private fun newBadge(media:MediaRef)=if(newVideos.isNew(media,prefs.getInt("newDays",7)))"NEW"else ""
    private fun explorerMode(done:()->Unit){choose("Отображение файлов",listOf("Дерево папок","Все видео","Папки с видео")){i->prefs.edit().putString("explorerMode",listOf("tree","all","folders")[i]).apply();done()}}
    private fun browserSettings(){choose("Файловый проводник",listOf("Отображение: ${when(prefs.getString("explorerMode","tree")){"all"->"Все видео";"folders"->"Папки с видео";else->"Дерево папок"}}","Метка NEW: ${prefs.getInt("newDays",7).let{if(it==0)"выключена"else "$it дней"}}")){i->
        if(i==0)explorerMode{}else form("Сколько дней показывать NEW",listOf(Field("days","Дней: 0 — выключить, 1–365",prefs.getInt("newDays",7).toString()))){v->
            val days=v.getValue("days").trim().toIntOrNull();require(days!=null&&days in 0..365){"Укажи число от 0 до 365"}
            prefs.edit().putInt("newDays",days).apply();toast(if(days==0)"Метки NEW выключены"else "Метки NEW: $days дней")
        }
    }}
    private fun fileExplorer(){
        if(viewing)leavePlayback()
        explorerScreen?.dismiss();explorerScreen=null
        val access=mediaAccess()
        work("Папки с видео",{if(access)MediaCatalog(this).folders().also{newVideos.observe(it.flatMap{it.files},"mediastore")}else emptyList()}){found->
            val screen=DeckScreen(this,"Файлы",if(access)"Доступные видео · удерживай элемент для действий"else "Разреши доступ к видео или добавь нужную папку")
            explorerScreen=screen
            val mode=prefs.getString("explorerMode","tree")!!
            screen.action("Вид"){explorerMode{screen.dismiss();fileExplorer()}}
            screen.action("Порядок"){choose("Сортировка",listOf("Имя: 1, 2, 10","Имя: обратно","Новые сверху","По размеру")){i->prefs.edit().putString("fileSort",listOf("name","reverse","new","size")[i]).apply();screen.dismiss();fileExplorer()}}
            screen.action("☆ Избранное"){
                val favorites=library.favorites()
                rowsWithHold("Избранные папки",favorites.map{it.title},{index->screen.dismiss();browseFolder(favorites[index])}){index->folderActions(favorites[index])}
            }
            val catalog=MediaCatalog(this)
            val roots=catalog.treeRoots(found)
            val expanded=prefs.getStringSet("treeExpanded",roots.map{it.uri}.toSet())!!.toMutableSet()
            val order=prefs.getString("fileSort","name")!!
            val parents=found.flatMap{f->f.files.map{it.uri to f.media.title}}.toMap()
            fun render(){
                val entries=mutableListOf<Pair<MediaRef,Int>>()
                fun branch(ref:MediaRef,depth:Int){
                    entries+=ref to depth
                    if(ref.folder&&ref.uri in expanded&&depth<32)sortMedia(catalog.treeChildren(ref,found),order).forEach{branch(it,depth+1)}
                }
                when(mode){
                    "all"->sortMedia(found.flatMap{it.files},order).forEach{entries+=it to 0}
                    "folders"->sortMedia(found.map{it.media.copy(bytes=it.bytes,modified=it.modified,added=it.files.maxOfOrNull{f->f.added}?:0)},order).forEach{entries+=it to 0}
                    else->sortMedia(roots,order).forEach{branch(it,0)}
                }
                val favoriteUris=library.favorites().map{it.uri}.toSet()
                val newCount=found.sumOf{f->f.files.count{newBadge(it).isNotBlank()}}
                screen.status.text=if(!access)"Разреши доступ к видео или добавь конкретную папку"else "${when(mode){"all"->"Все видео";"folders"->"Папки с видео";else->"Дерево папок"}} · ${found.sumOf{it.files.size}} видео${if(newCount>0)" · NEW: $newCount"else ""}"
                screen.rows(entries.map{(m,depth)->
                    val favorite=m.uri in favoriteUris
                    val symbol=if(!m.folder)"▶"else if(mode=="tree"&&m.uri in expanded)"▾"else "▸"
                    val detail=if(m.folder)listOfNotNull(m.bytes.takeIf{it>=0}?.let{size(it)},"Папка").joinToString(" · ")else listOfNotNull(m.bytes.takeIf{it>=0}?.let{size(it)},m.duration.takeIf{it>0}?.let{clock(it)},parents[m.uri]?.takeIf{mode=="all"}).joinToString(" · ")
                    DeckRow("${"   ".repeat(depth.coerceAtMost(12))}$symbol  ${m.title}",detail,listOfNotNull(newBadge(m).takeIf{it.isNotBlank()},"Избранное".takeIf{favorite}).joinToString(" · "))
                },{index->
                    val m=entries[index].first
                    if(m.folder){
                        if(mode=="tree"){if(!expanded.add(m.uri))expanded.remove(m.uri);prefs.edit().putStringSet("treeExpanded",expanded.toSet()).apply();val position=screen.list.firstVisiblePosition;val offset=screen.list.getChildAt(0)?.top?:0;render();screen.list.setSelectionFromTop(position,offset)}
                        else {screen.dismiss();browseFolder(m)}
                    }else{val files=if(mode=="all")entries.map{it.first}.filterNot{it.folder}else sortMedia(found.firstOrNull{f->f.files.any{it.uri==m.uri}}?.files?:listOf(m),order);screen.dismiss();startQueue(files,files.indexOfFirst{it.uri==m.uri})}
                },{index->val m=entries[index].first;if(m.folder)folderActions(m)else choose(m.title,listOf("Воспроизвести","Добавить в плейлист","Поделиться")){action->when(action){0->{screen.dismiss();openMedia(m)};1->selectPlaylist(listOf(m));2->shareMedia(m)}}})
            }
            render()
            val footer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(10),0,0)}
            footer.addView(deckButton("＋ Добавить папку",false){folderTarget=null;folderPicker.launch(null)},LinearLayout.LayoutParams(-1,-2))
            footer.addView(deckButton(if(access)"Доступ к видео"else "Разрешить доступ к видео",false){requestVideoAccess()},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
            footer.addView(deckButton("Выбрать файл",false){picker.launch(arrayOf("video/*","audio/*"))},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
            screen.column.addView(footer)
            screen.show()
        }
    }
    private fun folderActions(folder:MediaRef){
        val favorite=library.favorites().any{it.uri==folder.uri}
        choose(folder.title,listOf("Открыть","Воспроизвести папку","Добавить в плейлист",if(favorite)"Убрать из избранного"else "В избранное")){action->when(action){
            0->browseFolder(folder)
            1->work("Очередь папки",{folders.expand(listOf(folder))}){startQueue(it,0)}
            2->selectPlaylist(listOf(folder))
            3->{library.favorite(folder,!favorite);if(!viewing)buildHome();toast(if(favorite)"Убрано из избранного"else "Папка в избранном")}
        }}
    }
    private fun scheduleOrientation(){
        orientationJob?.cancel()
        orientationJob=lifecycleScope.launch{delay(200);applyVideoOrientation()}
    }
    private fun applyVideoOrientation(){
        if(!initialized||!viewing||tv()||manualOrientation||foldHalf&&prefs.getBoolean("flexEnabled",true))return
        val state=engine.state.value
        val target=when(prefs.getString("orientationPolicy","video")){
            "landscape"->ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait"->ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "system"->ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else->{if(!state.loaded||!state.hasVideo)return;if(state.aspect>1.04)ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else if(state.aspect<.96)ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else return}
        }
        if(requestedOrientation!=target)requestedOrientation=target
    }
    private fun rotatePlayback(){
        if(tv()){toast("Android TV уже использует горизонтальный интерфейс");return}
        manualOrientation=true;orientationJob?.cancel()
        requestedOrientation=if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    private fun orientationMenu(){selectOption("Ориентация экрана",listOf("По видео · автоматически","Всегда горизонтально","Всегда вертикально","Как в системе"),{listOf("video","landscape","portrait","system").indexOf(prefs.getString("orientationPolicy","video"))}){i->
        prefs.edit().putString("orientationPolicy",listOf("video","landscape","portrait","system")[i]).apply();manualOrientation=false
        if(i==3)requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else if(i==1)requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else if(i==2)requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else applyVideoOrientation()
    }}
    private data class Field(val id:String,val hint:String,val value:String="",val password:Boolean=false,val toggle:Boolean=false)
    private fun form(title:String,fields:List<Field>,accept:(Map<String,String>)->Unit){
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
        val inputs=fields.associate{field->field.id to if(field.toggle)CheckBox(this).apply{text=field.hint;isChecked=field.value=="true";column.addView(this)}else EditText(this).apply{hint=field.hint;setText(field.value);isSingleLine=true;inputType=if(field.password)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT;column.addView(this)}}
        if(inputs["save"] is CheckBox&&inputs["pass"] is EditText){
            val clear=object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){(inputs["pass"] as EditText).setText("");(inputs["save"] as CheckBox).isChecked=false};override fun afterTextChanged(s:android.text.Editable?) {}}
            listOf("host","port","user","domain").forEach{(inputs[it] as? EditText)?.addTextChangedListener(clear)}
        }
        val scroll=ScrollView(this).apply{addView(column)}
        val dialog=PlayerAlertBuilder(this).setTitle(title).setView(scroll).setPositiveButton("Продолжить",null).setNegativeButton("Отмена",null).create()
        dialog.setOnDismissListener{settingsNav?.refresh()}
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{accept(inputs.mapValues{when(val view=it.value){is CheckBox->view.isChecked.toString();is EditText->view.text.toString();else->""}});dialog.dismiss()}catch(e:Exception){toast(e.message?:"Проверь поля")}
        }};dialog.show()
    }
    private fun selectOption(title:String,items:List<String>,current:()->Int,action:(Int)->Unit){
        val existing=libraryNav?.takeIf{it.showing}?:settingsNav?.takeIf{it.showing}
        val nav=existing?:SettingsNavigator(this){settingsNav=null;interacted()}.also{settingsNav=it}
        nav.push{SettingsPage(title,items.mapIndexed{i,text->DeckRow(text,badge=if(i==current())"Используется"else "",selected=i==current())},action)}
    }
    private fun choose(title:String,items:List<String>,action:(Int)->Unit){
        if(isFinishing)return
        (libraryNav?.takeIf{it.showing}?:settingsNav?.takeIf{it.showing})?.let{nav->nav.push{SettingsPage(title,items.map{DeckRow(it.substringBefore('\n'),it.substringAfter('\n',""))},action)};return}
        val screen=DeckScreen(this,title)
        screen.rows(items.map{DeckRow(it.substringBefore('\n'),it.substringAfter('\n',""))},{index->screen.dismiss();action(index)})
        screen.show()
    }
    private fun slider(title:String,value:Int,min:Int,max:Int,onChange:(Int)->Unit){
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),dp(8))};val text=label(value.toString(),20);val seek=SeekBar(this).apply{this.max=max-min;progress=value-min}
        column.addView(text);column.addView(seek);seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){text.text=(p+min).toString();if(user)onChange(p+min)};override fun onStartTrackingTouch(s:SeekBar){};override fun onStopTrackingTouch(s:SeekBar){}})
        val dialog=PlayerAlertBuilder(this).setTitle(title).setView(column).setPositiveButton("Готово",null).setNeutralButton("Сброс"){_,_->onChange(when{title.startsWith("Масштаб")->100;title=="Громкость"->100;title.startsWith("Усиление звука")->0;else->min})}.create();dialog.setOnDismissListener{settingsNav?.refresh()};dialog.show()
    }
    private fun <T> work(title:String,block:()->T,source:Connection?=null,reconnect:((Connection)->Unit)?=null,done:(T)->Unit){
        if(operation?.isActive==true){toast("Дождись текущей операции");return}
        val progress=PlayerAlertBuilder(this).setTitle(title).setView(ProgressBar(this)).setCancelable(false).create();progress.show()
        operation=lifecycleScope.launch{
            try {val result=withContext(Dispatchers.IO){block()};progress.dismiss();operation=null;if(!isFinishing)done(result)else(result as? Closeable)?.close()}
            catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){progress.dismiss();operation=null
                if(error is HostTrust){val c=source?:connection;if(c!=null)PlayerAlertBuilder(this@MainActivity).setTitle(if(error.previous.isBlank())"Ключ SSH-сервера" else "Ключ SSH изменился!")
                    .setMessage("${c.host}:${c.port}\n${error.actual}\n${if(error.previous.isNotBlank())"Прежний: ${error.previous}\n" else ""}Подтверждай только после сверки с владельцем сервера.")
                    .setPositiveButton("Доверять этому ключу"){_,_->prefs.edit().putString("ssh.${c.host}:${c.port}",error.actual).apply();val trusted=c.copy(fingerprint=error.actual);connection=trusted;if(reconnect!=null)reconnect(trusted)else browseRemote(trusted,trusted.path.ifBlank{"/"})}.setNegativeButton("Отмена",null).show()
                }else if(source!=null)PlayerAlertBuilder(this@MainActivity).setTitle("${source.type} · ${source.host}").setMessage(error.message?:"Ошибка подключения").setPositiveButton("Повторить"){_,_->work(title,block,source,reconnect,done)}.setNeutralButton("Изменить вход"){_,_->connectionDialog(source.type,initialConnection=source,done=reconnect)}.setNegativeButton("Закрыть",null).show()
                else errorDialog(if(error is SecurityException)"Нет доступа к файлу или папке. Выбери её заново через «Файлы → Добавить папку»"else error.message?:"Ошибка подключения")
            }finally{if(!isFinishing)progress.dismiss()}
        }
    }
    private fun errorDialog(text:String){if(!isFinishing)PlayerAlertBuilder(this).setTitle("Neon Player").setMessage(text).setPositiveButton("OK",null).show()}
    private fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun size(bytes:Long)=when{bytes<0->"размер неизвестен";bytes>=1024*1024*1024->"%.1f ГБ".format(bytes.toDouble()/(1024*1024*1024));bytes>=1024*1024->"%.1f МБ".format(bytes.toDouble()/(1024*1024));else->"${bytes/1024} КБ"}
}
