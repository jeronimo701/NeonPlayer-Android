package neon.androidplayer

import android.content.Context

internal data class SettingsPage(val title:String,val rows:List<DeckRow>,val select:(Int)->Unit,val subtitle:String="Изменения применяются сразу")

internal class SettingsNavigator(private val context:Context,side:Int?=null,closed:()->Unit) {
    private data class Entry(val build:()->SettingsPage,var first:Int=0,var offset:Int=0)
    private val stack=mutableListOf<Entry>()
    val screen=DeckScreen(context,"Настройки",side=side)
    init{screen.navigateBack={back()};screen.setOnDismissListener{closed()}}
    val showing get()=screen.isShowing
    fun push(build:()->SettingsPage){
        stack.lastOrNull()?.let{it.first=screen.list.firstVisiblePosition;it.offset=screen.list.getChildAt(0)?.top?:0}
        stack+=Entry(build);refresh();if(!screen.isShowing)screen.show()
    }
    fun refresh(){
        val entry=stack.lastOrNull()?:return;val page=entry.build();screen.refreshPalette()
        screen.heading.text=page.title;screen.status.text=page.subtitle
        screen.rows(page.rows,{index->entry.first=screen.list.firstVisiblePosition;entry.offset=screen.list.getChildAt(0)?.top?:0;page.select(index);if(showing)refresh()})
        screen.list.setSelectionFromTop(entry.first,entry.offset)
        if(context.getSharedPreferences("neon",Context.MODE_PRIVATE).getString("controlsAnimation","fade")!="none"){
            screen.list.alpha=.65f;screen.list.animate().alpha(1f).setDuration(120).start()
        }
    }
    fun back(){if(stack.size<=1)screen.dismiss()else{stack.removeAt(stack.lastIndex);refresh()}}
    fun close(){screen.dismiss()}
}
