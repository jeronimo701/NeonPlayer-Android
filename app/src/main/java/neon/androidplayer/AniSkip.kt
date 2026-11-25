package neon.androidplayer

import org.json.JSONObject
import kotlin.math.abs

/** Own API adapter. A user explicitly supplies MAL id + episode; filenames are never guessed/uploaded. */
internal object AniSkip {
    fun parse(raw:String,duration:Double):List<SkipInterval>{
        val response=JSONObject(raw);if(!response.optBoolean("found"))return emptyList()
        val rows=response.optJSONArray("results")?:return emptyList()
        return (0 until rows.length()).mapNotNull {i->
            val row=rows.optJSONObject(i)?:return@mapNotNull null
            val length=row.optDouble("episodeLength",-1.0);val interval=row.optJSONObject("interval")?:return@mapNotNull null
            val start=interval.optDouble("startTime",-1.0);val end=interval.optDouble("endTime",-1.0)
            val kind=when(row.optString("skipType")){"op"->"intro";"ed"->"credits";"recap"->"recap";else->return@mapNotNull null}
            if(duration.isFinite()&&length.isFinite()&&start.isFinite()&&end.isFinite()&&abs(length-duration)<=2&&start>=0&&end>start&&end<=duration&&end-start<=600)SkipInterval(start,end,kind)else null
        }.distinctBy{it.id}
    }
    fun fetch(mal:Int,episode:Int,duration:Double):List<SkipInterval>{
        require(mal>0&&episode>0&&duration>0)
        val url="https://api.aniskip.com/v2/skip-times/$mal/$episode?types%5B%5D=op&types%5B%5D=ed&types%5B%5D=recap&episodeLength="+java.lang.String.format(java.util.Locale.ROOT,"%.3f",duration)
        return try{parse(jsonHttp(url),duration)}catch(e:java.io.IOException){if(e.message?.startsWith("HTTP 404:")==true)emptyList()else throw e}
    }
}
