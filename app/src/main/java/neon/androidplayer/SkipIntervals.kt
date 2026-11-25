package neon.androidplayer

internal data class SkipInterval(val start:Double,val end:Double,val kind:String) {
    val id get()="$kind:$start:$end"
    val title get()=when(kind){"intro"->"Пропустить интро";"credits"->"Пропустить титры";"recap"->"Пропустить повтор";else->"Пропустить фрагмент"}
}
internal data class MediaChapter(val start:Double,val title:String)
/** Conservative chapter-name matching. Never absorb the next chapter / post-credit scene. */
internal fun chapterSkips(chapters:List<MediaChapter>,duration:Double):List<SkipInterval> {
    if(!duration.isFinite()||duration<=0)return emptyList()
    val sorted=chapters.filter{it.start.isFinite()&&it.start>=0&&it.start<duration}.sortedBy{it.start}
    return sorted.mapIndexedNotNull{i,c->
        val title=c.title.lowercase(java.util.Locale.ROOT).replace(Regex("[\\p{Punct}_]+")," ").trim()
        val kind=when {
            Regex("post.?credit|after.?credit|после титров|сцена после").containsMatchIn(title)->null
            Regex("\\b(intro|opening|op|заставка|опенинг)\\b").containsMatchIn(title)->"intro"
            Regex("\\b(recap|previously|повтор)\\b|в предыдущ").containsMatchIn(title)->"recap"
            Regex("\\b(credits|outro|ending|ed|титры|эндинг)\\b").containsMatchIn(title)->"credits"
            else->null
        }
        val end=sorted.getOrNull(i+1)?.start?:duration
        if(kind!=null&&end-c.start>=1&&end-c.start<=600)SkipInterval(c.start,end,kind)else null
    }
}
internal fun parseMediaTime(raw:String):Double {
    val parts=raw.trim().split(':');require(parts.size in 1..3)
    val nums=parts.map{it.toDouble().also{n->require(n.isFinite()&&n>=0)}}
    require(nums.drop(1).all{it<60}){"Секунды и минуты после двоеточия: от 0 до 59"}
    return nums.fold(0.0){a,n->a*60+n}
}
internal fun audioGraph(normalize:Boolean,night:Boolean,gain:Int,limiter:Boolean):String {
    val filters=mutableListOf<String>()
    if(normalize)filters+="dynaudnorm=f=250:g=15:p=0.9:m=5"
    if(night)filters+="acompressor=threshold=0.125:ratio=4:attack=20:release=250:makeup=1.5"
    if(gain!=0)filters+="volume=${gain.coerceIn(-12,18)}dB"
    if(limiter)filters+="alimiter=limit=0.95:level=false:latency=true"
    return filters.joinToString(",")
}
