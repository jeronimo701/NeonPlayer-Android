package neon.androidplayer

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class FolderScannerTest {
    @Test fun nestedViewDoesNotLoseSeriesAndCancellationStopsBeforeNextFolder():Unit=runBlocking {
        val root=MediaRef("test:root","root",true);val child=MediaRef("test:child","child",true);val episode=MediaRef("test:episode","Episode 2")
        val snapshot=scanFolders(root,{if(it==root)listOf(child)else listOf(episode)})
        assertEquals(listOf(episode),snapshot.files);assertEquals(1,snapshot.tree(emptySet()).size);assertEquals(2,snapshot.tree(setOf(child.uri)).size)
        var reads=0
        val job=launch{scanFolders(root,{reads++;listOf(child)}){cancel()}}
        job.join();assertTrue(job.isCancelled);assertEquals(1,reads)
    }
    @Test fun recursiveAliasesCannotMakeAnUnboundedScan():Unit=runBlocking {
        val failure=runCatching{scanFolders(MediaRef("test:0","root",true),{m->listOf(MediaRef(m.uri+"/alias","alias",true))})}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
