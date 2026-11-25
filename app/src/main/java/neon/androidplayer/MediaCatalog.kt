package neon.androidplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

internal data class VideoFolder(val media:MediaRef,val files:List<MediaRef>) {
    val modified get()=files.maxOfOrNull{it.modified}?:0L
    val bytes get()=files.sumOf{it.bytes.coerceAtLeast(0)}
}
/** Own MediaStore index. No filesystem-wide permission and no copied reference-player code. */
internal class MediaCatalog(private val context:Context) {
    fun folders():List<VideoFolder> {
        val columns=mutableListOf("_id","_display_name","bucket_id","bucket_display_name","_size","date_modified","duration")
        if(Build.VERSION.SDK_INT>=29){columns+="volume_name";columns+="relative_path"}
        val addedColumn=columns.size;columns+="date_added"
        val groups=linkedMapOf<String,MutableList<MediaRef>>()
        val names=mutableMapOf<String,String>()
        val uri=MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cursor=context.contentResolver.query(uri,columns.toTypedArray(),null,null,null)?:return emptyList()
        cursor.use {c->while(c.moveToNext()) {
            val volume=if(Build.VERSION.SDK_INT>=29)c.getString(7).orEmpty().ifBlank{"external"}else "external"
            val bucket=c.getString(2).orEmpty();val key="$volume:$bucket"
            val collection=if(Build.VERSION.SDK_INT>=29)MediaStore.Video.Media.getContentUri(volume)else uri
            val location=ContentUris.withAppendedId(collection,c.getLong(0))
            val folderName=if(Build.VERSION.SDK_INT>=29)c.getString(8)?.trimEnd('/')?.takeIf{it.isNotBlank()}else null
            names[key]=folderName?:c.getString(3)?:"Видео"
            groups.getOrPut(key){mutableListOf()}+=MediaRef(location.toString(),c.getString(1)?:"Видео",bytes=c.getLong(4),modified=c.getLong(5)*1000,duration=c.getLong(6)/1000.0,added=c.getLong(addedColumn)*1000)
        }}
        return groups.map{(key,files)->VideoFolder(MediaRef("neon-folder://media/${Uri.encode(key)}",names.getValue(key),true),files.sortedWith(naturalMediaOrder))}
            .sortedWith(Comparator{a,b->naturalCompare(a.media.title,b.media.title)})
    }
    fun files(folder:MediaRef):List<MediaRef> = folders().firstOrNull{it.media.uri==folder.uri}?.files?:emptyList()
    fun treeRoots(index:List<VideoFolder>):List<MediaRef> = index.groupBy{volume(it)}.map{(v,entries)->
        treeRef(v,"",if(v in listOf("external","external_primary"))"Внутренняя память"else "Накопитель · $v",entries.flatMap{it.files})
    }.sortedWith(naturalMediaOrder)
    fun treeChildren(folder:MediaRef):List<MediaRef> = treeChildren(folder,folders())
    fun treeChildren(folder:MediaRef,index:List<VideoFolder>):List<MediaRef> {
        val segments=Uri.parse(folder.uri).pathSegments
        val v=segments.firstOrNull()?:return emptyList()
        val path=segments.getOrNull(1).orEmpty().trim('/')
        val children=linkedMapOf<String,MutableList<MediaRef>>()
        val files=mutableListOf<MediaRef>()
        for(entry in index.filter{volume(it)==v}) {
            val location=entry.media.title.trim('/')
            if(location==path){files+=entry.files;continue}
            val prefix=if(path.isEmpty())""else "$path/"
            if(!location.startsWith(prefix))continue
            val child=location.removePrefix(prefix).substringBefore('/')
            if(child.isNotEmpty())children.getOrPut(child){mutableListOf()}+=entry.files
        }
        return children.map{(name,entries)->treeRef(v,if(path.isEmpty())name else "$path/$name",name,entries)}.sortedWith(naturalMediaOrder)+files.sortedWith(naturalMediaOrder)
    }
    private fun volume(folder:VideoFolder)=Uri.parse(folder.media.uri).lastPathSegment.orEmpty().substringBefore(':')
    private fun treeRef(volume:String,path:String,title:String,files:List<MediaRef>)=MediaRef("neon-folder://tree/${Uri.encode(volume)}/${Uri.encode(path)}",title,true,
        bytes=files.sumOf{it.bytes.coerceAtLeast(0)},modified=files.maxOfOrNull{it.modified}?:0,added=files.maxOfOrNull{it.added}?:0)
}

internal fun sortMedia(items:List<MediaRef>,order:String):List<MediaRef> {
    val comparator=when(order){
        "new"->compareByDescending<MediaRef>{it.modified}.then(naturalMediaOrder)
        "size"->compareByDescending<MediaRef>{it.bytes}.then(naturalMediaOrder)
        "reverse"->naturalMediaOrder.reversed()
        else->naturalMediaOrder
    }
    return items.sortedWith(compareByDescending<MediaRef>{it.folder}.then(comparator))
}
