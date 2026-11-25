package neon.androidplayer

import java.net.URI
import java.util.Locale

internal fun normalizeTorrentQuery(raw:String):String {
    val value=raw.trim();require(value.isNotEmpty()){"Введи название, код или ссылку"}
    if(Regex("kp[1-9][0-9]*",RegexOption.IGNORE_CASE).matches(value)||Regex("tt[0-9]{7,12}",RegexOption.IGNORE_CASE).matches(value))return value.lowercase(Locale.ROOT)
    if(!value.startsWith("http://",true)&&!value.startsWith("https://",true))return value
    val uri=runCatching{URI(value)}.getOrElse{throw IllegalArgumentException("Проверь ссылку Кинопоиска или IMDb")}
    val host=uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
    if(host=="kinopoisk.ru")Regex("^/(?:film|series)/([1-9][0-9]*)/?$").matchEntire(uri.path.orEmpty())?.let{return "kp"+it.groupValues[1]}
    if(host=="imdb.com"||host=="m.imdb.com")Regex("^/title/(tt[0-9]{7,12})/?$",RegexOption.IGNORE_CASE).matchEntire(uri.path.orEmpty())?.let{return it.groupValues[1].lowercase(Locale.ROOT)}
    throw IllegalArgumentException("Нужна ссылка на фильм/сериал Кинопоиска или страницу title IMDb; либо введи название")
}
