package neon.androidplayer

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import kotlin.math.*

/** Independent low-resolution bars. The decoder's output rectangle is never resized for ambient. */
internal class AmbientView(context:Context,private val source:SurfaceView):View(context) {
    private val worker=HandlerThread("neon-ambient").apply{start()}
    private val background=Handler(worker.looper)
    private val ui=Handler(android.os.Looper.getMainLooper())
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var picture=RectF()
    private var sampleRect=RectF()
    private var mode=0
    private var brightness=.6f
    private var softness=.5f
    private var blur=24
    private var points=64
    private var intervalMs=400L
    private var passes=2
    private var edgeSmooth=.5f
    private var running=false
    private var closed=false
    private var pending=false
    var positionSource:()->Double={0.0}
    var pausedSource:()->Boolean={false}
    private var capturedPosition=Double.NaN
    private var dirty=true
    private var generation=0
    private var frame:Bitmap?=null
    private var previous:Bitmap?=null
    private var changed=0L
    private val tick=object:Runnable{override fun run(){capture();if(running&&!closed)ui.postDelayed(this,intervalMs)}}

    fun configure(rect:RectF,style:Int,light:Float,edge:Float,radius:Int,active:Boolean,samples:Int=64,refreshMs:Long=400,blurPasses:Int=2,edgeBlend:Int=50){
        if(picture!=rect||blur!=radius||points!=samples||passes!=blurPasses){dirty=true;generation++}
        points=samples.coerceIn(16,128);intervalMs=refreshMs.coerceIn(150,1500);passes=blurPasses.coerceIn(1,3);edgeSmooth=edgeBlend.coerceIn(0,100)/100f
        picture=RectF(rect);mode=style;brightness=light.coerceIn(0f,1f);softness=edge.coerceIn(0f,1f);blur=radius
        val next=active&&style>0
        visibility=if(next)VISIBLE else GONE
        if(next!=running){running=next;ui.removeCallbacks(tick);if(next)ui.post(tick)}
        invalidate()
    }
    fun reset(){generation++;dirty=true;capturedPosition=Double.NaN;frame=null;previous=null;invalidate()}
    fun pause(){running=false;ui.removeCallbacks(tick)}
    fun close(){closed=true;pause();generation++;background.removeCallbacksAndMessages(null);worker.quitSafely()}
    private fun capture(){
        if(!running||closed||pending||!source.holder.surface.isValid||width<=0||height<=0)return
        val position=positionSource()
        if(pausedSource()&&!dirty&&frame!=null&&abs(position-capturedPosition)<.001)return
        val rect=RectF(picture)
        if(!rect.intersect(0f,0f,source.width.toFloat(),source.height.toFloat())||rect.width()<1||rect.height()<1)return
        if(picture.left<=0&&picture.top<=0&&picture.right>=width&&picture.bottom>=height)return
        val target=Bitmap.createBitmap(points,(points*9/16).coerceAtLeast(9),Bitmap.Config.ARGB_8888)
        val token=generation;val w=target.width;val h=target.height;val radius=(blur*w/448).coerceIn(0,20);val count=passes;pending=true
        val area=Rect(rect.left.roundToInt(),rect.top.roundToInt(),rect.right.roundToInt(),rect.bottom.roundToInt())
        try {
            PixelCopy.request(source.holder.surface,area,target,{result->
                if(result==PixelCopy.SUCCESS&&!closed){
                    var pixels=IntArray(w*h);target.getPixels(pixels,0,w,0,0,w,h)
                    repeat(count){if(radius>0)pixels=boxBlur(pixels,w,h,radius)};target.setPixels(pixels,0,w,0,0,w,h)
                    ui.post{pending=false;if(token==generation&&!closed){previous=frame;frame=target;sampleRect=rect;capturedPosition=position;dirty=false;changed=SystemClock.uptimeMillis();invalidate()}else target.recycle()}
                }else{target.recycle();ui.post{pending=false}}
            },background)
        }catch(_:Exception){target.recycle();pending=false}
    }
    override fun onDraw(canvas:Canvas){
        val bitmap=frame?:return
        val mix=((SystemClock.uptimeMillis()-changed)/minOf(220f,intervalMs*.8f)).coerceIn(0f,1f)
        previous?.let{if(mix<1)drawFrame(canvas,it,1f)}
        drawFrame(canvas,bitmap,if(previous==null)1f else mix)
        if(mix<1)postInvalidateOnAnimation()
    }
    private fun drawFrame(canvas:Canvas,bitmap:Bitmap,alpha:Float){
        val shader=BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
        val destination=if(mode==2){val cover=max(width/bitmap.width.toFloat(),height/bitmap.height.toFloat());RectF((width-bitmap.width*cover)/2,(height-bitmap.height*cover)/2,(width+bitmap.width*cover)/2,(height+bitmap.height*cover)/2)}else sampleRect
        shader.setLocalMatrix(Matrix().apply{setRectToRect(RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat()),destination,Matrix.ScaleToFit.FILL)})
        paint.shader=shader;paint.alpha=(255*alpha).roundToInt();paint.colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,1f,0f)))
        canvas.save();canvas.clipOutRect(picture);canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint);canvas.restore()
        val feather=min(picture.width(),picture.height())*.12f*softness
        if(feather>0){
            fun band(rect:RectF,x0:Float,y0:Float,x1:Float,y1:Float){
                val positions=FloatArray(17){it/16f};val colors=IntArray(17){Color.argb((edgeBlendWeight(positions[it],edgeSmooth)*255).roundToInt(),255,255,255)}
                paint.shader=ComposeShader(shader,LinearGradient(x0,y0,x1,y1,colors,positions,Shader.TileMode.CLAMP),PorterDuff.Mode.DST_IN)
                paint.alpha=(255*alpha).roundToInt();paint.colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,1f,0f)))
                canvas.drawRect(rect,paint)
            }
            if(picture.left>0)band(RectF(picture.left,picture.top,picture.left+feather,picture.bottom),picture.left,0f,picture.left+feather,0f)
            if(picture.right<width)band(RectF(picture.right-feather,picture.top,picture.right,picture.bottom),picture.right,0f,picture.right-feather,0f)
            if(picture.top>0)band(RectF(picture.left,picture.top,picture.right,picture.top+feather),0f,picture.top,0f,picture.top+feather)
            if(picture.bottom<height)band(RectF(picture.left,picture.bottom-feather,picture.right,picture.bottom),0f,picture.bottom,0f,picture.bottom-feather)
        }
        paint.shader=null;paint.colorFilter=null;paint.alpha=255
    }
    companion object {
        private fun boxBlur(input:IntArray,w:Int,h:Int,radius:Int):IntArray {
            fun pass(src:IntArray,horizontal:Boolean):IntArray=IntArray(src.size){index->
                val x=index%w;val y=index/w;var r=0;var g=0;var b=0
                for(d in -radius..radius){val c=src[if(horizontal)y*w+(x+d).coerceIn(0,w-1)else (y+d).coerceIn(0,h-1)*w+x];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c)}
                val n=radius*2+1;Color.rgb(r/n,g/n,b/n)
            }
            return pass(pass(input,true),false)
        }
    }
}
