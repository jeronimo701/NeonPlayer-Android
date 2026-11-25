package neon.androidplayer

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.URI

internal fun youtubeVideoId(input:String):String? {
    val raw=input.trim();val pattern=Regex("[A-Za-z0-9_-]{11}")
    if(pattern.matches(raw))return raw
    val uri=runCatching{URI(raw)}.getOrNull()?:return null
    if(uri.scheme?.lowercase() !in listOf("http","https"))return null
    val host=uri.host?.lowercase()?:return null
    val parts=uri.path.orEmpty().trim('/').split('/')
    val id=when(host){
        "youtu.be"->parts.firstOrNull()
        "youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","www.youtube-nocookie.com"->
            if(parts.firstOrNull() in listOf("shorts","embed","live"))parts.getOrNull(1)else uri.rawQuery?.split('&')?.firstOrNull{it.startsWith("v=")}?.substringAfter("v=")
        else->null
    }
    return id?.takeIf{pattern.matches(it)}
}
internal fun youtubeHost(input:String)=runCatching{URI(input.trim()).host?.lowercase() in setOf("youtu.be","youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","www.youtube-nocookie.com")}.getOrDefault(false)
internal data class WebStream(val video:String,val audio:String?,val title:String,val headers:Map<String,String>)
internal object YoutubeLinks {
    @Synchronized fun prepare(context:Context){
        val dl=YoutubeDL.getInstance();dl.init(context.applicationContext)
        val prefs=context.getSharedPreferences("neon",Context.MODE_PRIVATE)
        if(prefs.getString("bundledYtdlp","")!="2026.11.23"){
            val installed=dl.versionName(context)?.take(10)
            if(installed==null||installed<="2026.11.23"){
                val file=java.io.File(context.noBackupFilesDir,"youtubedl-android/yt-dlp/yt-dlp")
                context.resources.openRawResource(R.raw.neon_ytdlp).use{input->file.outputStream().use{input.copyTo(it)}}
            }
            prefs.edit().putString("bundledYtdlp","2026.11.23").apply()
        }
    }
    fun resolve(context:Context,url:String,processId:String):WebStream {
        val id=youtubeVideoId(url)?:error("Нужна ссылка на отдельное видео YouTube, Shorts или трансляцию")
        prepare(context);val dl=YoutubeDL.getInstance()
        val request=YoutubeDLRequest("https://www.youtube.com/watch?v=$id").apply{
            addOption("--no-playlist");addOption("--skip-download");addOption("--dump-single-json")
            addOption("--socket-timeout","15");addOption("--retries","1");addOption("--extractor-retries","1")
            addOption("-f","bv*[height<=1080]+ba/b[height<=1080]/best")
        }
        return parse(JSONObject(dl.execute(request,processId,null).out))
    }
    fun parse(info:JSONObject):WebStream {
        fun url(row:JSONObject):String?=row.optString("url").takeIf{runCatching{URI(it).scheme in listOf("http","https")}.getOrDefault(false)}
        val formats=info.optJSONArray("requested_formats")
        val rows=if(formats==null)listOf(info)else (0 until formats.length()).map{formats.getJSONObject(it)}
        val video=rows.firstOrNull{it.optString("vcodec","none")!="none"&&url(it)!=null}?:rows.firstOrNull{url(it)!=null}?:error("YouTube не вернул доступный видеопоток")
        val audio=rows.firstOrNull{it!==video&&it.optString("acodec","none")!="none"&&url(it)!=null}
        val headerObject=video.optJSONObject("http_headers")?:info.optJSONObject("http_headers")?:JSONObject()
        val headers=headerObject.keys().asSequence().map{it to headerObject.optString(it)}.filter{(key,value)->key.matches(Regex("[A-Za-z0-9-]+"))&&!value.contains('\r')&&!value.contains('\n')}.toMap()
        return WebStream(url(video)!!,audio?.let{url(it)},info.optString("title","YouTube"),headers)
    }
    fun cancel(id:String){runCatching{YoutubeDL.getInstance().destroyProcessById(id)}}
    fun update(context:Context){prepare(context);YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)}
}
