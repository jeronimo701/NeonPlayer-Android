package neon.androidplayer

internal data class AmbientQuality(val id:String,val title:String,val points:Int,val intervalMs:Long,val passes:Int)
internal val ambientQualities=listOf(
    AmbientQuality("fast","Быстро",32,800,1),
    AmbientQuality("medium","Средне",64,400,2),
    AmbientQuality("heavy","Тяжело",128,160,3)
)
internal fun ambientQuality(id:String?)=ambientQualities.firstOrNull{it.id==id}?:ambientQualities[1]
internal fun edgeBlendWeight(t:Float,smoothness:Float):Float {
    val x=t.coerceIn(0f,1f);val smooth=x*x*(3-2*x)
    return 1-(x+(smooth-x)*smoothness.coerceIn(0f,1f))
}
internal fun seekGestureDelta(from:Double,to:Double,forwardDown:Boolean)=(if(forwardDown)to-from else from-to)
