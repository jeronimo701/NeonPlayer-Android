package neon.androidplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Documents
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class MediaRef(val uri:String,val title:String,val folder:Boolean=false,val bytes:Long=-1,val modified:Long=0,val duration:Double=0.0,val added:Long=0,val source:String="") {
    fun json()=JSONObject().put("uri",uri).put("title",title).put("folder",folder).put("bytes",bytes).put("modified",modified).put("duration",duration).put("added",added).put("source",source)
    companion object {fun read(o:JSONObject)=MediaRef(o.getString("uri"),o.getString("title"),o.optBoolean("folder"),o.optLong("bytes",-1),o.optLong("modified",0),o.optDouble("duration",0.0),o.optLong("added",0),o.optString("source",""))}
}
internal data class Watched(val media:MediaRef,val position:Double,val duration:Double,val updated:Long) {
    val resume:Double get()=if(position>=5 && duration-position>10 && position<duration*.98)position else 0.0
}
internal data class SavedPlaylist(val id:String,val name:String,val items:List<MediaRef>)

/** Private, bounded, local storage. Never persist loopback URLs, passwords or request headers. */
internal class MediaLibrary(context:Context, name:String="library") {
    private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    fun history():List<Watched> = read("history").mapNotNull {o->runCatching {
        Watched(MediaRef.read(o.getJSONObject("media")),o.getDouble("position"),o.getDouble("duration"),o.getLong("updated"))
    }.getOrNull()}.sortedByDescending {it.updated}
    fun remember(media:MediaRef,position:Double,duration:Double) {
        if(!position.isFinite()||!duration.isFinite()||duration<=0||media.folder)return
        val uri=Uri.parse(media.uri)
        if(uri.scheme=="neon-source" && runCatching{NetworkSource.read(media)}.getOrNull()==null)return
        if(uri.scheme !in listOf("content","file","http","https","rtsp","neon-source") || uri.userInfo!=null || uri.host in listOf("127.0.0.1","localhost","::1"))return
        val items=listOf(Watched(media,position.coerceIn(0.0,duration),duration,System.currentTimeMillis()))+history().filter{it.media.uri!=media.uri}
        write("history",items.take(200).map {JSONObject().put("media",it.media.json()).put("position",it.position).put("duration",it.duration).put("updated",it.updated)})
    }
    fun forget(uri:String){write("history",history().filter{it.media.uri!=uri}.map {JSONObject().put("media",it.media.json()).put("position",it.position).put("duration",it.duration).put("updated",it.updated)})}
    fun clearHistory(){prefs.edit().remove("history").apply()}
    fun favorites():List<MediaRef> = read("favorites").mapNotNull{runCatching{MediaRef.read(it)}.getOrNull()}
    fun favorite(folder:MediaRef,on:Boolean){
        require(folder.folder)
        val entries=favorites().filter{it.uri!=folder.uri}+if(on)listOf(folder)else emptyList()
        require(entries.size<=100){"Не больше 100 избранных папок"}
        write("favorites",entries.map(MediaRef::json))
    }
    fun playlists():List<SavedPlaylist> = read("playlists").mapNotNull{o->runCatching {
        val rows=o.getJSONArray("items")
        SavedPlaylist(o.getString("id"),o.getString("name"),(0 until rows.length()).map{MediaRef.read(rows.getJSONObject(it))})
    }.getOrNull()}
    fun save(list:SavedPlaylist){
        val all=playlists();require(all.size<100||all.any{it.id==list.id}){"Не больше 100 плейлистов"}
        require(list.name.isNotBlank()&&list.items.size<=2000){"Нужно название; не больше 2000 элементов"}
        val updated=all.filter{it.id!=list.id}+list
        require(updated.sumOf{it.items.size}<=5000){"Не больше 5000 элементов в библиотеке"}
        write("playlists",updated.map{JSONObject().put("id",it.id).put("name",it.name).put("items",JSONArray(it.items.map(MediaRef::json)))})
    }
    fun create(name:String)=SavedPlaylist(UUID.randomUUID().toString(),name.trim(),emptyList()).also{save(it)}
    fun delete(id:String){write("playlists",playlists().filter{it.id!=id}.map{JSONObject().put("id",it.id).put("name",it.name).put("items",JSONArray(it.items.map(MediaRef::json)))})}
    private fun read(key:String):List<JSONObject> = runCatching {val a=JSONArray(prefs.getString(key,"[]"));(0 until a.length()).map{a.getJSONObject(it)}}.getOrDefault(emptyList())
    private fun write(key:String,values:List<JSONObject>){prefs.edit().putString(key,JSONArray(values).toString()).apply()}
}

/** Numeric chunks compare by value, without converting arbitrary episode numbers to Int. */
internal val naturalMediaOrder=Comparator<MediaRef>{a,b->naturalCompare(a.title,b.title)}
internal fun naturalCompare(left:String,right:String):Int {
    val a=Regex("[0-9]+|[^0-9]+").findAll(left.lowercase(java.util.Locale.ROOT)).map{it.value}.toList()
    val b=Regex("[0-9]+|[^0-9]+").findAll(right.lowercase(java.util.Locale.ROOT)).map{it.value}.toList()
    for(i in 0 until minOf(a.size,b.size)) {
        val x=a[i];val y=b[i]
        val result=if(x[0].isDigit()&&y[0].isDigit()) {
            val nx=x.trimStart('0').ifEmpty{"0"};val ny=y.trimStart('0').ifEmpty{"0"}
            nx.length.compareTo(ny.length).takeIf{it!=0}?:nx.compareTo(ny)
        } else x.compareTo(y)
        if(result!=0)return result
    }
    return a.size.compareTo(b.size).takeIf{it!=0}?:left.compareTo(right)
}

internal class LocalFolders(private val context:Context) {
    fun root(tree:Uri):MediaRef {
        val uri=Documents.buildDocumentUriUsingTree(tree,Documents.getTreeDocumentId(tree))
        val name=context.contentResolver.query(uri,arrayOf(Documents.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}?:"Папка"
        return MediaRef(uri.toString(),name,true)
    }
    fun children(folder:MediaRef):List<MediaRef> {
        val parent=Uri.parse(folder.uri)
        if(parent.scheme=="neon-folder")return if(parent.host=="tree")MediaCatalog(context).treeChildren(folder)else MediaCatalog(context).files(folder)
        val children=Documents.buildChildDocumentsUriUsingTree(parent,Documents.getDocumentId(parent))
        val fields=arrayOf(Documents.Document.COLUMN_DOCUMENT_ID,Documents.Document.COLUMN_DISPLAY_NAME,Documents.Document.COLUMN_MIME_TYPE,Documents.Document.COLUMN_SIZE,Documents.Document.COLUMN_LAST_MODIFIED)
        val result=mutableListOf<MediaRef>()
        val cursor=context.contentResolver.query(children,fields,null,null,null)?:throw java.io.IOException("Папка недоступна. Выбери её заново")
        cursor.use {while(it.moveToNext()) {
            val id=it.getString(0);val title=it.getString(1)?:"Файл";val mime=it.getString(2).orEmpty()
            val directory=mime==Documents.Document.MIME_TYPE_DIR
            if(directory||mime.startsWith("video/")||mime.startsWith("audio/")||title.substringAfterLast('.',"").lowercase() in setOf("mkv","mp4","m4v","avi","mov","webm","ts","m2ts","mp3","flac","m4a","ogg","opus","wav"))
                result+=MediaRef(Documents.buildDocumentUriUsingTree(parent,id).toString(),title,directory,if(it.isNull(3))-1 else it.getLong(3),if(it.isNull(4))0 else it.getLong(4))
            require(result.size<=2000){"В папке больше 2000 медиафайлов/каталогов. Выбери подпапку"}
        }}
        return result.sortedWith(compareByDescending<MediaRef>{it.folder}.then(naturalMediaOrder))
    }
    // A saved folder is refreshed on playback, not a stale snapshot of its current episodes.
    fun expand(items:List<MediaRef>):List<MediaRef> {
        val result=items.flatMap{if(it.folder)children(it).filterNot(MediaRef::folder)else listOf(it)}.distinctBy{it.uri}
        require(result.size<=2000){"В очереди больше 2000 файлов"}
        return result
    }
}
