package neon.androidplayer

internal fun seekSecondsPerPixel(duration:Double,height:Double,density:Double,precision:Int):Double {
    val overview=duration.coerceAtLeast(0.0)/height.coerceAtLeast(1.0)
    return when(precision){1->minOf(overview,.5/density.coerceAtLeast(.1));2->minOf(overview,.1/density.coerceAtLeast(.1));else->overview}
}

/** Tick spacing of the rotary scale; dragging still uses the long-film precision model. */
internal fun wheelTickStep(duration:Double,precision:Int):Double = when(precision){
    1->1.0;2->.25;else->{val target=duration.coerceAtLeast(1.0)/60;listOf(1.0,2.0,5.0,10.0,15.0,30.0,60.0,120.0,300.0,600.0,1800.0,3600.0).firstOrNull{it>=target}?:3600.0}
}
