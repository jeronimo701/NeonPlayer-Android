package neon.androidplayer

import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.*

internal const val PANEL = 0xF217171A.toInt()
internal val ACCENT get()=AppAppearance.accent
internal const val INK = 0xFFC8C9D0.toInt()
internal object UiSizing {@Volatile var percent=100;@Volatile var iconStyle="duo";@Volatile var seekDown=false}
internal fun Context.dp(value:Int)=(resources.displayMetrics.density*value*UiSizing.percent/100f).roundToInt()
internal fun clock(seconds:Double):String {val s=seconds.coerceAtLeast(0.0).toLong();return if(s>=3600)"%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60)}

/** Own vector drawing; no Plllay icons, resources or code are used. */
class ControlButton(context:Context,var symbol:String,val action:()->Unit):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {isClickable=true;isFocusable=true;minimumWidth=context.dp(48);minimumHeight=context.dp(48)
        setOnClickListener{action()};setOnFocusChangeListener{_,_->invalidate()};contentDescription=symbol}
    fun update(value:String,description:String=value){symbol=value;contentDescription=description;invalidate()}
    override fun onDraw(c:Canvas) {
        val d=resources.displayMetrics.density*UiSizing.percent/100f;val cx=width/2f;val cy=height/2f
        val primary=symbol=="play"||symbol=="pause"
        val focused=hasFocus()&&!isInTouchMode
        paint.clearShadowLayer();paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        paint.color=if(focused)ACCENT else if(AppAppearance.amber)0x80D7C5AA.toInt()else 0x809FC2C5.toInt();paint.strokeWidth=1.65f*d;paint.style=Paint.Style.STROKE
        if(focused||isPressed)c.drawRoundRect(3*d,3*d,width-3*d,height-3*d,12*d,12*d,paint)
        c.save();c.translate(cx,cy);val iconScale=min(if(primary)1.5f else 1f,min(width,height)/(48*d));c.scale(iconScale,iconScale)
        if(symbol=="play"||symbol=="pause"){paint.color=ACCENT;paint.strokeWidth=(if(UiSizing.iconStyle=="orange")2.5f else 1.5f)*d;c.drawCircle(0f,0f,21*d,paint);paint.color=0xFFF1FAFA.toInt()}
        if(FamilyIcons.draw(c,paint,symbol,0f,0f,30*d)){c.restore();return}
        when(symbol){
            "play" -> {val p=Path();p.moveTo(-6*d,-10*d);p.lineTo(10*d,0f);p.lineTo(-6*d,10*d);p.close();paint.style=Paint.Style.FILL;c.drawPath(p,paint)}
            "pause" -> {paint.strokeWidth=4*d;c.drawLine(-5*d,-9*d,-5*d,9*d,paint);c.drawLine(5*d,-9*d,5*d,9*d,paint)}
            "-10","+10" -> {
                val back=symbol=="-10";c.drawArc(-15*d,-15*d,15*d,15*d,if(back)150f else 30f,if(back)-285f else 285f,false,paint)
                val angle=Math.toRadians(if(back)-135.0 else -45.0);val x=cos(angle).toFloat()*15*d;val y=sin(angle).toFloat()*15*d
                val tx=(if(back)sin(angle) else -sin(angle)).toFloat();val ty=(if(back)-cos(angle) else cos(angle)).toFloat()
                c.drawLine(x,y,x-tx*5*d-ty*3*d,y-ty*5*d+tx*3*d,paint);c.drawLine(x,y,x-tx*5*d+ty*3*d,y-ty*5*d-tx*3*d,paint)
                paint.textSize=12*d;paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;c.drawText("10",0f,5*d,paint)
            }
            "lock","unlock" -> {c.drawRoundRect(-10*d,-2*d,10*d,13*d,3*d,3*d,paint);c.drawArc(-7*d,-15*d,7*d,6*d,180f,if(symbol=="lock")180f else 115f,false,paint)}
            "settings" -> {c.drawCircle(0f,0f,6*d,paint);c.drawCircle(0f,0f,13*d,paint);repeat(8){c.save();c.rotate(it*45f);c.drawLine(0f,-13*d,0f,-17*d,paint);c.restore()}}
            "tracks" -> {c.drawRoundRect(-17*d,-11*d,17*d,11*d,2*d,2*d,paint);c.drawLine(-12*d,-3*d,12*d,-3*d,paint);c.drawLine(-12*d,4*d,7*d,4*d,paint)}
            "files" -> {c.drawLine(-14*d,-10*d,14*d,-10*d,paint);c.drawLine(-14*d,0f,14*d,0f,paint);c.drawLine(-14*d,10*d,2*d,10*d,paint);val p=Path();p.moveTo(7*d,5*d);p.lineTo(15*d,10*d);p.lineTo(7*d,15*d);p.close();c.drawPath(p,paint)}
            "rotate" -> {c.drawRoundRect(-7*d,-12*d,7*d,12*d,2*d,2*d,paint);c.drawArc(-18*d,-18*d,18*d,18*d,-120f,65f,false,paint);c.drawLine(11*d,-15*d,17*d,-15*d,paint);c.drawLine(17*d,-15*d,17*d,-9*d,paint);c.drawArc(-18*d,-18*d,18*d,18*d,60f,65f,false,paint);c.drawLine(-11*d,15*d,-17*d,15*d,paint);c.drawLine(-17*d,15*d,-17*d,9*d,paint)}
            "volume","levels" -> {
                val p=Path();p.moveTo(-13*d,-4*d);p.lineTo(-7*d,-4*d);p.lineTo(-1*d,-10*d);p.lineTo(-1*d,10*d);p.lineTo(-7*d,4*d);p.lineTo(-13*d,4*d);p.close();c.drawPath(p,paint)
                c.drawArc(0f,-9*d,13*d,9*d,-60f,120f,false,paint)
                if(symbol=="levels"){c.drawCircle(11*d,-11*d,3*d,paint);for(i in 0..7){val a=i*PI/4;c.drawLine((11+cos(a)*5).toFloat()*d,(-11+sin(a)*5).toFloat()*d,(11+cos(a)*7).toFloat()*d,(-11+sin(a)*7).toFloat()*d,paint)}}
            }
            "brightness" -> {c.drawCircle(0f,0f,6*d,paint);repeat(8){c.save();c.rotate(it*45f);c.drawLine(0f,-11*d,0f,-15*d,paint);c.restore()}}
            "device" -> {c.drawRoundRect(-9*d,-16*d,9*d,16*d,3*d,3*d,paint);c.drawCircle(0f,11*d,1*d,paint)}
            "network" -> {c.drawRoundRect(-6*d,-15*d,6*d,-5*d,2*d,2*d,paint);c.drawLine(0f,-5*d,0f,3*d,paint);c.drawLine(-13*d,3*d,13*d,3*d,paint);c.drawLine(-13*d,3*d,-13*d,9*d,paint);c.drawLine(13*d,3*d,13*d,9*d,paint);c.drawRoundRect(-18*d,9*d,-8*d,17*d,2*d,2*d,paint);c.drawRoundRect(8*d,9*d,18*d,17*d,2*d,2*d,paint)}
            "server" -> {for(y in listOf(-10f,10f)){c.drawRoundRect(-16*d,(y-7)*d,16*d,(y+7)*d,3*d,3*d,paint);c.drawCircle(-9*d,y*d,1.5f*d,paint);c.drawLine(-3*d,y*d,10*d,y*d,paint)}}
            "transfer" -> {c.drawLine(-15*d,-7*d,15*d,-7*d,paint);c.drawLine(9*d,-13*d,15*d,-7*d,paint);c.drawLine(9*d,-1*d,15*d,-7*d,paint);c.drawLine(15*d,8*d,-15*d,8*d,paint);c.drawLine(-9*d,2*d,-15*d,8*d,paint);c.drawLine(-9*d,14*d,-15*d,8*d,paint)}
            "folder" -> {val p=Path();p.moveTo(-17*d,-12*d);p.lineTo(-4*d,-12*d);p.lineTo(1*d,-7*d);p.lineTo(17*d,-7*d);p.lineTo(17*d,13*d);p.lineTo(-17*d,13*d);p.close();c.drawPath(p,paint)}
            "home" -> {val p=Path();p.moveTo(-16*d,-2*d);p.lineTo(0f,-15*d);p.lineTo(16*d,-2*d);p.moveTo(-12*d,-5*d);p.lineTo(-12*d,15*d);p.lineTo(-4*d,15*d);p.lineTo(-4*d,5*d);p.lineTo(4*d,5*d);p.lineTo(4*d,15*d);p.lineTo(12*d,15*d);p.lineTo(12*d,-5*d);c.drawPath(p,paint)}
            "search" -> {c.drawCircle(-3*d,-3*d,11*d,paint);c.drawLine(5*d,5*d,16*d,16*d,paint)}
            "history" -> {c.drawArc(-15*d,-15*d,15*d,15*d,-140f,310f,false,paint);c.drawLine(0f,-8*d,0f,0f,paint);c.drawLine(0f,0f,7*d,4*d,paint);c.drawLine(-16*d,-12*d,-16*d,-2*d,paint);c.drawLine(-16*d,-2*d,-6*d,-2*d,paint)}
            "tape" -> {c.drawLine(0f,-18*d,0f,18*d,paint);for(i in -3..3)c.drawLine(-6*d,i*5*d,6*d,i*5*d,paint);paint.color=ACCENT;c.drawCircle(0f,4*d,4*d,paint)}
            "fit" -> {val p=Path();for((x,y)in listOf(-1 to -1,1 to -1,-1 to 1,1 to 1)){p.moveTo(x*7*d,y*14*d);p.lineTo(x*14*d,y*14*d);p.lineTo(x*14*d,y*7*d)};c.drawPath(p,paint)}
            else -> {paint.style=Paint.Style.FILL;paint.textSize=if(symbol.length>5)11*d else 16*d;paint.textAlign=Paint.Align.CENTER;c.drawText(symbol,0f,6*d,paint)}
        }
        c.restore()
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        val result=super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL){isPressed=false;if(isInTouchMode)clearFocus()}
        invalidate();return result
    }
}

class SeekTape(context:Context,val seek:(Double)->Unit):View(context) {
    var duration=0.0;var position=0.0
    var gesture:((Boolean)->Unit)?=null
    private var shown=0.0;private var dragging=false;private var candidate=0.0
    private var anchorY=0f;private var anchorX=0f;private var anchorTime=0.0;private var precision=0
    private var heldFine=false
    private var animator:android.animation.ValueAnimator?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val hold=Runnable{if(dragging){heldFine=true;precision=1;anchorTime=candidate;anchorY=lastY;invalidate()}}
    private var lastY=0f
    init {isFocusable=true;isClickable=true;contentDescription="Перемотка: ${if(UiSizing.seekDown)"вниз"else "вверх"} вперёд; отведи палец в сторону для точности";setOnLongClickListener{precision=(precision+1)%3;invalidate();true}}
    fun update(state:Playback){
        duration=state.duration;position=state.position
        if(dragging)return
        animator?.cancel()
        if(abs(shown-position)>max(2.0,duration*.02)){shown=position;invalidate()}
        else animator=android.animation.ValueAnimator.ofFloat(shown.toFloat(),position.toFloat()).apply{duration=150;addUpdateListener{shown=(it.animatedValue as Float).toDouble();invalidate()};start()}
    }
    private fun span()=(height-context.dp(68)).coerceAtLeast(context.dp(30)).toDouble()
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density*UiSizing.percent/100f
        val value=if(dragging)candidate else shown
        val top=30*d;val bottom=(height-32*d).coerceAtLeast(top+24*d);val center=(top+bottom)/2;val half=(bottom-top)/2
        val step=wheelTickStep(duration,precision)
        paint.style=Paint.Style.FILL;paint.shader=LinearGradient(0f,top,width.toFloat(),top,intArrayOf(0x001B2429,0x402E3B40,0x001B2429),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(3*d,top,width-6*d,bottom,14*d,14*d,paint);paint.shader=null
        val base=floor(value/step).toLong()
        for(i in -12..12){
            val tick=(base+i)*step;if(tick<0||tick>duration)continue
            val phase=(tick-value)/(step*10)
            if(abs(phase)>=1)continue
            val y=center+(if(UiSizing.seekDown)1 else -1)*sin(phase*PI/2).toFloat()*half
            val fade=(cos(phase*PI/2)*.55).coerceIn(0.0,1.0)
            val major=(base+i)%5==0L
            paint.color=Color.argb((fade*255).toInt(),180,197,204);paint.strokeWidth=if(major)1.5f*d else d
            val left=width*.16f;val right=width*(if(major).56f else .40f)
            c.drawLine(left,y,right,y,paint)
            if(major&&abs(y-center)>16*d){paint.textSize=8*d;paint.textAlign=Paint.Align.RIGHT;c.drawText(clock(tick),width-9*d,y+3*d,paint)}
        }
        paint.color=0x354E626B;paint.strokeWidth=d;c.drawLine(width-4*d,top,width-4*d,bottom,paint)
        paint.color=0x8877BEBE.toInt();c.drawCircle(width-4*d,(if(UiSizing.seekDown)top+(bottom-top)*(if(duration>0)value/duration else 0.0).toFloat().coerceIn(0f,1f)else bottom-(bottom-top)*(if(duration>0)value/duration else 0.0).toFloat().coerceIn(0f,1f)),2.5f*d,paint)
        paint.color=0xE6161C22.toInt();paint.style=Paint.Style.FILL;c.drawRoundRect(2*d,center-15*d,width-7*d,center+15*d,8*d,8*d,paint)
        paint.color=(ACCENT and 0x00FFFFFF) or 0xB8000000.toInt();paint.strokeWidth=d;paint.style=Paint.Style.STROKE;c.drawRoundRect(2*d,center-15*d,width-7*d,center+15*d,8*d,8*d,paint)
        paint.style=Paint.Style.FILL;paint.color=0xFFE5F2F3.toInt();paint.textSize=11*d;paint.textAlign=Paint.Align.CENTER;c.drawText(clock(value),(width-5*d)/2,center+4*d,paint)
        paint.color=0x809FAEB8.toInt();paint.textSize=9*d;c.drawText(if(UiSizing.seekDown)"0:00"else clock(duration),width/2f,16*d,paint)
        c.drawText(if(dragging||precision>0)listOf("Обзор","Точно","Очень точно")[precision]else if(UiSizing.seekDown)"Вниз +"else "Вверх +",width/2f,height-12*d,paint)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(duration<=0)return false
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{animator?.cancel();dragging=true;heldFine=false;precision=0;anchorY=e.y;lastY=e.y;anchorX=e.x;anchorTime=position;candidate=position;parent.requestDisallowInterceptTouchEvent(true);gesture?.invoke(true);postDelayed(hold,500);invalidate();return true}
            MotionEvent.ACTION_MOVE->{
                if(abs(e.y-anchorY)>context.dp(5))removeCallbacks(hold)
                val distance=abs(e.x-anchorX)/resources.displayMetrics.density
                val next=if(distance>100)2 else if(distance>40)1 else if(heldFine)1 else 0
                if(next!=precision){precision=next;anchorTime=candidate;anchorY=e.y}
                lastY=e.y
                val rate=seekSecondsPerPixel(duration,span(),resources.displayMetrics.density.toDouble(),precision)
                candidate=(anchorTime+seekGestureDelta(anchorY.toDouble(),e.y.toDouble(),UiSizing.seekDown)*rate).coerceIn(0.0,duration);invalidate();return true
            }
            MotionEvent.ACTION_UP->{removeCallbacks(hold);dragging=false;shown=candidate;seek(candidate);parent.requestDisallowInterceptTouchEvent(false);gesture?.invoke(false);invalidate();performClick();return true}
            MotionEvent.ACTION_CANCEL->{removeCallbacks(hold);dragging=false;shown=position;parent.requestDisallowInterceptTouchEvent(false);gesture?.invoke(false);invalidate();return true}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onKeyDown(key:Int,event:KeyEvent):Boolean=when(key){
        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{precision=(precision+1)%3;invalidate();true}
        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{val step=listOf(10.0,1.0,.1)[precision];seek((position+if((key==KeyEvent.KEYCODE_DPAD_UP)!=UiSizing.seekDown)step else -step).coerceIn(0.0,duration));true}
        else->super.onKeyDown(key,event)
    }
    override fun onDetachedFromWindow(){animator?.cancel();removeCallbacks(hold);super.onDetachedFromWindow()}
}

internal class LevelSlider(context:Context,private val title:String,initial:Float,private val maxValue:Float,private val changed:(Float)->Unit):View(context){
    private var value=initial.coerceIn(0f,maxValue);private var dragging=false;private var anchorY=0f;private var anchorValue=value
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{isFocusable=true;isClickable=true;contentDescription="$title: прокрути вверх или вниз"}
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density*UiSizing.percent/100f;val x=width/2f;val top=60*d;val bottom=(height-35*d).coerceAtLeast(top+24*d);val center=(top+bottom)/2;val half=(bottom-top)/2
        p.textAlign=Paint.Align.CENTER;p.textSize=9*d;p.color=0x90B7CED0.toInt();FamilyIcons.draw(c,p,if(title=="Яркость")"brightness"else "volume",x,17*d,22*d);p.style=Paint.Style.FILL;c.drawText(title,x,42*d,p)
        p.shader=LinearGradient(0f,top,width.toFloat(),top,intArrayOf(0x001B2429,0x402E3B40,0x001B2429),null,Shader.TileMode.CLAMP);c.drawRoundRect(2*d,top,width-4*d,bottom,10*d,10*d,p);p.shader=null
        val percent=value/maxValue*100;val base=floor(percent/5).toInt()
        for(i in -10..10){val tick=(base+i)*5f;if(tick !in 0f..100f)continue;val phase=(tick-percent)/50;if(abs(phase)>=1)continue;val y=center-sin(phase*PI/2).toFloat()*half
            p.color=Color.argb((cos(phase*PI/2)*140).toInt(),180,197,204);p.strokeWidth=if((base+i)%2==0)1.5f*d else d
            c.drawLine(width*.20f,y,width*(if((base+i)%2==0).76f else .58f),y,p)
        }
        p.color=0x354E626B;p.strokeWidth=d;c.drawLine(width-3*d,top,width-3*d,bottom,p);p.color=0x8877BEBE.toInt();c.drawCircle(width-3*d,bottom-(bottom-top)*value/maxValue,2*d,p)
        p.style=Paint.Style.FILL;p.color=0xEE161C22.toInt();c.drawRoundRect(d,center-14*d,width-5*d,center+14*d,7*d,7*d,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=d;p.color=(ACCENT and 0x00FFFFFF) or 0xB8000000.toInt();c.drawRoundRect(d,center-14*d,width-5*d,center+14*d,7*d,7*d,p)
        p.style=Paint.Style.FILL;p.textSize=10*d;p.color=0xFFE5F2F3.toInt();c.drawText("${percent.roundToInt()}%",x,center+4*d,p)
        p.textSize=8*d;p.color=0x809FAEB8.toInt();c.drawText("100%",x,top-5*d,p);c.drawText("0%",x,height-12*d,p)
    }
    fun update(next:Float){if(!dragging){value=next.coerceIn(0f,maxValue);invalidate()}}
    private fun change(next:Float){value=next.coerceIn(0f,maxValue);changed(value);invalidate()}
    override fun onTouchEvent(e:MotionEvent):Boolean {when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{dragging=true;anchorY=e.y;anchorValue=value;parent.requestDisallowInterceptTouchEvent(true);return true}
        MotionEvent.ACTION_MOVE->{change(anchorValue+(anchorY-e.y)/(height-context.dp(95)).coerceAtLeast(context.dp(24))*maxValue);return true}
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragging=false;parent.requestDisallowInterceptTouchEvent(false);performClick();return true}
    };return true}
    override fun performClick():Boolean{super.performClick();return true}
    override fun onKeyDown(key:Int,event:KeyEvent)=when(key){KeyEvent.KEYCODE_DPAD_UP->{change(value+.05f);true};KeyEvent.KEYCODE_DPAD_DOWN->{change(value-.05f);true};else->super.onKeyDown(key,event)}
}
