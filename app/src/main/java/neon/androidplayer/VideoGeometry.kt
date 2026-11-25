package neon.androidplayer

internal data class VideoBox(val left:Double,val top:Double,val width:Double,val height:Double)
internal fun videoBox(w:Double,h:Double,aspect:Double,scale:Double,x:Double,y:Double,fit:String):VideoBox {
    val a=aspect.takeIf{it.isFinite()&&it>0}?:16.0/9
    val base=when(fit){"fill"->maxOf(w/a,h);else->minOf(w/a,h)}
    val vw=(if(fit=="stretch")w else base*a)*scale
    val vh=(if(fit=="stretch")h else base)*scale
    return VideoBox((w-vw)*(x+1)/2,(h-vh)*(y+1)/2,vw,vh)
}
