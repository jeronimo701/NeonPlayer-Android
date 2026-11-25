package neon.androidplayer

import kotlinx.coroutines.*

internal data class FolderSnapshot(val root:MediaRef,val children:Map<String,List<MediaRef>>) {
    val files get()=children.values.flatten().filterNot{it.folder}.distinctBy{it.uri}
    fun tree(expanded:Set<String>):List<Pair<MediaRef,Int>> {
        val result=mutableListOf<Pair<MediaRef,Int>>()
        fun append(folder:MediaRef,depth:Int){children[folder.uri].orEmpty().forEach{m->result+=m to depth;if(m.folder&&m.uri in expanded&&depth<24)append(m,depth+1)}}
        append(root,0);return result
    }
}
/** Bounded, cancellable between directory reads; no partial snapshot is published as a complete scan. */
internal suspend fun scanFolders(root:MediaRef,read:(MediaRef)->List<MediaRef>,progress:(Int)->Unit={}):FolderSnapshot {
    val visited=mutableSetOf<String>();val children=linkedMapOf<String,List<MediaRef>>()
    val pending=java.util.ArrayDeque<Pair<MediaRef,Int>>();pending.add(root to 0);var count=0
    while(pending.isNotEmpty()){
        currentCoroutineContext().ensureActive()
        val (folder,depth)=pending.removeFirst();if(!visited.add(folder.uri))continue
        require(visited.size<=1000&&depth<=24){"Обход ограничен: 1000 папок / 24 уровня. Выбери подпапку"}
        val items=runInterruptible(Dispatchers.IO){read(folder)}
        count+=items.size;require(count<=10000){"Больше 10000 элементов. Выбери подпапку"}
        children[folder.uri]=items.sortedWith(compareByDescending<MediaRef>{it.folder}.then(naturalMediaOrder))
        items.filter{it.folder}.forEach{pending.add(it to depth+1)}
        progress(visited.size)
    }
    return FolderSnapshot(root,children)
}
