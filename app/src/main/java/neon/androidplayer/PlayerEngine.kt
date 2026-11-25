package neon.androidplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

data class Playback(val ready: Boolean = false, val loaded: Boolean = false, val paused: Boolean = true,
    val position: Double = 0.0, val duration: Double = 0.0, val aspect: Double = 16.0 / 9,
    val speed: Double = 1.0, val hasVideo:Boolean=false, val eof:Boolean=false, val buffering: Boolean = false, val title: String = "", val error: String? = null)

class PlayerEngine(private val context: Context) : MPVLib.EventObserver, MPVLib.LogObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(Playback())
    val state = _state.asStateFlow()
    private var rawAspect=0.0
    private var rotation=0
    private var created = false
    private var surface = false
    private var pending: Pair<String, String>? = null
    private var headers = emptyMap<String, String>()
    @Volatile internal var lastGpuError: String? = null
        private set
    val config = File(context.filesDir, "engine")

    fun prepareAssets() {
        fun copy(path: String, target: File) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) { target.parentFile!!.mkdirs(); context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
            else { target.mkdirs(); children.forEach { copy("$path/$it", File(target, it)) } }
        }
        config.mkdirs()
        val revision=context.assets.open("engine-revision.txt").bufferedReader().use {it.readText()}
        val marker=File(config,".assets-revision")
        if (!marker.exists() || marker.readText()!=revision) {
            copy("shaders", File(config, "shaders")); copy("scripts", File(config, "scripts"))
            copy("cacert.pem", File(config, "cacert.pem")); marker.writeText(revision)
        }
    }
    fun initialize() {
        check(!created)
        MPVLib.create(context.applicationContext); created = true
        MPVLib.addObserver(this); MPVLib.addLogObserver(this)
        val opts = mapOf("config" to "yes", "config-dir" to config.path, "profile" to "fast", "vo" to "gpu-next",
            "gpu-api" to "opengl", "gpu-context" to "android", "opengl-es" to "yes", "hwdec" to "mediacodec-copy",
            "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1", "idle" to "yes", "keep-open" to "yes", "osc" to "no",
            "input-default-bindings" to "no", "input-vo-keyboard" to "no", "terminal" to "no", "osd-level" to "0",
            "msg-level" to "all=warn", "interpolation" to "no",
            "tls-ca-file" to File(config, "cacert.pem").path, "cache" to "yes", "demuxer-max-bytes" to "64MiB",
            "gpu-shader-cache-dir" to File(context.cacheDir, "gpu").apply { mkdirs() }.path)
        opts.forEach { (k,v) -> MPVLib.setOptionString(k,v) }; MPVLib.init()
        MPVLib.setOptionString("force-window", "no")
        listOf("time-pos", "duration", "video-dec-params/aspect", "speed").forEach { MPVLib.observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE) }
        listOf("pause", "paused-for-cache", "eof-reached").forEach { MPVLib.observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_FLAG) }
        MPVLib.observeProperty("video-dec-params/rotate",MPVLib.MpvFormat.MPV_FORMAT_INT64)
        _state.value = _state.value.copy(ready = true)
    }
    fun attach(value: Surface) {
        if (!created) return
        MPVLib.attachSurface(value); surface = true
        MPVLib.setOptionString("force-window", "yes"); MPVLib.setPropertyString("vo", "gpu-next")
        pending?.let { pending = null; open(it.first, it.second, headers) }
    }
    fun resize(width: Int, height: Int) { if (created) MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    fun detach() {
        if (created && surface) { MPVLib.setPropertyString("vo", "null"); MPVLib.setPropertyString("force-window", "no"); MPVLib.detachSurface() }
        surface = false
    }
    fun open(location: String, title: String, requestHeaders: Map<String,String> = emptyMap()) {
        check(created)
        headers = requestHeaders
        lastGpuError = null;rawAspect=0.0;rotation=0
        _state.value = _state.value.copy(title = title, loaded = false, hasVideo=false, aspect=16.0/9, eof=false, position = 0.0, duration = 0.0, error = null)
        if (!surface) { pending = location to title; return }
        // Never retain source credentials for the next unrelated file.
        MPVLib.setPropertyString("http-header-fields", requestHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" })
        MPVLib.command(arrayOf("loadfile", location, "replace")); MPVLib.setPropertyBoolean("pause", false)
    }
    fun command(vararg values: String) { if (created) MPVLib.command(values) }
    fun property(name: String): String? = if (created) MPVLib.getPropertyString(name) else null
    fun togglePause() { if (created) MPVLib.setPropertyBoolean("pause", !_state.value.paused) }
    fun pause() { if (created) MPVLib.setPropertyBoolean("pause", true) }
    fun seek(seconds: Double) { if (_state.value.duration > 0) command("seek", seconds.coerceIn(0.0, _state.value.duration).toString(), "absolute+exact") }
    fun skip(seconds: Double) { command("seek", seconds.toString(), "relative") }
    fun speed(value: Double) { if (created) MPVLib.setPropertyDouble("speed", value) }
    fun picture(settings: JSONObject) { command("script-message", "neon-settings", settings.toString()) }
    fun destroy() {
        if (!created) return
        MPVLib.removeObserver(this); MPVLib.removeLogObserver(this); detach(); MPVLib.destroy(); created = false
        handler.removeCallbacksAndMessages(null)
    }
    override fun eventProperty(property: String) {}
    private fun geometry(){if(rawAspect>0)_state.value=_state.value.copy(aspect=if(rotation%180!=0)1.0/rawAspect else rawAspect,hasVideo=true)}
    override fun eventProperty(property: String, value: Long) {if(property=="video-dec-params/rotate")handler.post{if(created){rotation=value.toInt();geometry()}}}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Boolean) { handler.post {
        if (!created) return@post
        _state.value = when(property) { "eof-reached" -> _state.value.copy(eof=value); "pause" -> _state.value.copy(paused = value); "paused-for-cache" -> _state.value.copy(buffering = value); else -> _state.value }
    } }
    override fun eventProperty(property: String, value: Double) { if (!value.isFinite()) return; handler.post {
        if (!created) return@post
        _state.value = when(property) {
            "time-pos" -> _state.value.copy(position = value); "duration" -> _state.value.copy(duration = value)
            "video-dec-params/aspect" -> {rawAspect=value;if(value>0)_state.value.copy(aspect=if(rotation%180!=0)1.0/value else value,hasVideo=true)else _state.value}
            "speed" -> _state.value.copy(speed = value); else -> _state.value
        }
    } }
    override fun event(eventId: Int) { if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) handler.post {
        if (created) _state.value = _state.value.copy(loaded = true, error = null)
    } }
    override fun logMessage(prefix: String, level: Int, text: String) {
        if (text.contains("compiled without LittleCMS")) return // Optional colour-profile feature is not requested by this app.
        if (text.contains("Both surface and native_window are NULL")) return // MediaCodec probe can fall back successfully during surface changes.
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR && prefix.startsWith("vo/")) lastGpuError = text.take(2000)
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) handler.post {
            if (created) _state.value = _state.value.copy(error = when {
                prefix.startsWith("af") || text.contains("audio filter",true) -> "Ошибка обработки звука. Сбрось фильтры в настройках звука"
                prefix.startsWith("vo/") || text.contains("shader", true) || text.contains("compile", true) -> "Ошибка GPU/шейдера. Отключи шейдеры и подсветку в настройках"
                else -> "libmpv: ошибка ${prefix.take(40)}. Проверь источник, формат и подключение"
            })
        }
    }
}
