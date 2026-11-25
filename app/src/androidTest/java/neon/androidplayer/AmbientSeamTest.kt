package neon.androidplayer

import android.graphics.*
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AmbientSeamTest {
    @Test fun crossfadeHasSameColourOnBothSidesOfVideoBoundary(){
        val ins=InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync {
            val context=ins.targetContext;val view=AmbientView(context,SurfaceView(context));view.layout(0,0,400,400)
            view.configure(RectF(100f,100f,300f,300f),1,.4f,1f,24,false,edgeBlend=100)
            val old=Bitmap.createBitmap(64,36,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
            val next=Bitmap.createBitmap(64,36,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
            fun set(key:String,value:Any){AmbientView::class.java.getDeclaredField(key).apply{isAccessible=true}.set(view,value)}
            set("frame",next);set("previous",old);set("sampleRect",RectF(100f,100f,300f,300f));view.visibility=View.VISIBLE
            try{
                for(age in listOf(60L,140L,300L)){
                    set("changed",SystemClock.uptimeMillis()-age)
                    val target=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLACK)}
                    val canvas=Canvas(target);canvas.drawRect(100f,100f,300f,300f,Paint().apply{color=Color.GREEN});view.draw(canvas)
                    assertEquals(Color.GREEN,target.getPixel(200,200))
                    val outside=target.getPixel(99,200);val inside=target.getPixel(100,200)
                    assertTrue("Edge must meet ambient during crossfade",abs(Color.red(outside)-Color.red(inside))<=4&&abs(Color.green(outside)-Color.green(inside))<=4&&abs(Color.blue(outside)-Color.blue(inside))<=4)
                    target.recycle()
                }
            }finally{view.close();old.recycle();next.recycle()}
        }
    }
}
