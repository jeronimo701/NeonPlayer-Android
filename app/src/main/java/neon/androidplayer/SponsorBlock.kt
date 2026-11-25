package neon.androidplayer

import android.net.Uri
import org.json.JSONArray
import java.io.IOException

data class SponsorSegment(val start:Double,val end:Double,val category:String,val id:String,val videoDuration:Double)
internal fun youtubeId(input:String):String?=youtubeVideoId(input)
object SponsorApi {
    val categories=listOf("sponsor","selfpromo","interaction","intro","outro","preview","music_offtopic")
    fun fetch(id:String,enabled:Set<String>):List<SponsorSegment> {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        if(enabled.isEmpty())return emptyList()
        val raw=try{jsonHttp("https://sponsor.ajay.app/api/skipSegments?videoID=${queryValue(id)}&categories=${queryValue(JSONArray(enabled.toList()).toString())}")}
            catch(e:IOException){if(e.message?.startsWith("HTTP 404:")==true)return emptyList() else throw e}
        val rows=JSONArray(raw)
        return (0 until rows.length()).mapNotNull{index->
            val row=rows.getJSONObject(index);val segment=row.optJSONArray("segment")?:return@mapNotNull null
            val start=segment.optDouble(0,Double.NaN);val end=segment.optDouble(1,Double.NaN)
            if(!start.isFinite()||!end.isFinite()||start<0||end<=start||row.optString("actionType","skip")!="skip")null
            else SponsorSegment(start,end,row.optString("category"),row.getString("UUID"),row.optDouble("videoDuration",0.0))
        }.sortedBy{it.start}
    }
}
