package neon.androidplayer

import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.PathParser

/** Lucide 24px line icons, ISC/MIT; attribution in assets/licenses/LUCIDE.txt. */
internal object ControlGlyphs {
    private val paths=mapOf(
        "volume" to PathParser.createPathFromPathData("M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z M16 9a5 5 0 0 1 0 6 M19.364 18.364a9 9 0 0 0 0-12.728")!!,
        "brightness" to PathParser.createPathFromPathData("M8.0 12.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0 M12 2v2 M12 20v2 m4.93 4.93 1.41 1.41 m17.66 17.66 1.41 1.41 M2 12h2 M20 12h2 m6.34 17.66-1.41 1.41 m19.07 4.93-1.41 1.41")!!,
        "settings" to PathParser.createPathFromPathData("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915 M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0")!!,
        "files" to PathParser.createPathFromPathData("M21 5H3 M10 12H3 M10 19H3 M15 12.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997a1 1 0 0 1-1.517-.86z")!!,
        "fit" to PathParser.createPathFromPathData("M3 7V5a2 2 0 0 1 2-2h2 M17 3h2a2 2 0 0 1 2 2v2 M21 17v2a2 2 0 0 1-2 2h-2 M7 21H5a2 2 0 0 1-2-2v-2")!!,
        "rotate" to PathParser.createPathFromPathData("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8 M21 3v5h-5")!!,
        "lock" to PathParser.createPathFromPathData("M11.0 16.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0 M5.0 10.0H19.0Q21.0 10.0 21.0 12.0V20.0Q21.0 22.0 19.0 22.0H5.0Q3.0 22.0 3.0 20.0V12.0Q3.0 10.0 5.0 10.0Z M7 10V7a5 5 0 0 1 10 0v3")!!,
        "levels" to PathParser.createPathFromPathData("M10 8h4 M12 21v-9 M12 8V3 M17 16h4 M19 12V3 M19 21v-5 M3 14h4 M5 10V3 M5 21v-7")!!
    )
    fun draw(canvas:Canvas,paint:Paint,symbol:String,x:Float,y:Float,size:Float):Boolean {
        val path=paths[symbol]?:return false
        canvas.save();canvas.translate(x-size/2,y-size/2);canvas.scale(size/24,size/24)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.65f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        canvas.drawPath(path,paint);canvas.restore();return true
    }
}
