package neon.androidplayer

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

internal val iconFamilies=listOf("duo","line","soft","cut","orange")
internal fun iconFamilyName(id:String)=when(id){"orange"->"ORANGE · Чёрный и оранжевый";"line"->"LINE · Контурные";"soft"->"SOFT · Мягкие";"cut"->"CUT · Геометричные";else->"DUO · Двухцветные"}

/** Scalable native vector families, independently drawn from the approved concept sheets. */
internal object FamilyIcons {
    fun draw(c:Canvas,p:Paint,symbol:String,x:Float,y:Float,size:Float,family:String=UiSizing.iconStyle):Boolean {
        if(family=="orange")return OrangeControlIcons.draw(c,p,symbol,x,y,size)
        if(family=="line"&&ControlGlyphs.draw(c,p,symbol,x,y,size))return true
        val speed=symbol.endsWith("×")||symbol.endsWith("x")
        if(symbol !in listOf("play","pause","-10","+10","volume","brightness","settings","files","fit","lock","unlock","tracks","levels","tape")&&!speed)return false
        val color=p.color;val alpha=Color.alpha(color);val line=family=="line";val cut=family=="cut";val duo=family=="duo"
        val ink=Color.argb(alpha,235,235,227);val body=if(duo)if(AppAppearance.amber)Color.argb(alpha,162,104,28)else Color.argb(alpha,101,122,138)else ink;val accent=Color.argb(alpha,Color.red(ACCENT),Color.green(ACCENT),Color.blue(ACCENT))
        c.save();c.translate(x-size/2,y-size/2);c.scale(size/24,size/24)
        p.strokeWidth=if(line)1.65f else 2.2f;p.strokeJoin=if(cut)Paint.Join.MITER else Paint.Join.ROUND;p.strokeCap=if(cut)Paint.Cap.SQUARE else Paint.Cap.ROUND;p.shader=null;p.pathEffect=if(family=="soft")CornerPathEffect(.65f)else null
        fun path(vararg xy:Float)=Path().apply{moveTo(xy[0],xy[1]);var i=2;while(i<xy.size){lineTo(xy[i],xy[i+1]);i+=2};close()}
        fun shape(path:Path,color:Int=body,outline:Boolean=line){p.color=color;p.style=if(outline)Paint.Style.STROKE else Paint.Style.FILL;c.drawPath(path,p)}
        fun stroke(path:Path,color:Int=ink,width:Float=if(line)1.65f else 2.4f){p.style=Paint.Style.STROKE;p.color=color;p.strokeWidth=width;c.drawPath(path,p)}
        fun rect(l:Float,t:Float,r:Float,b:Float,color:Int=body){
            val q=if(cut)path(l+2,t,r-2,t,r,t+2,r,b-2,r-2,b,l+2,b,l,b-2,l,t+2)else Path().apply{addRoundRect(l,t,r,b,2f,2f,Path.Direction.CW)};shape(q,color)
        }
        fun circle(x:Float,y:Float,r:Float,color:Int=body,outline:Boolean=line){shape(Path().apply{addCircle(x,y,r,Path.Direction.CW)},color,outline)}
        when {
            symbol=="play"->{shape(path(8f,5f,19f,12f,8f,19f),ink,outline=line)}
            symbol=="pause"->{rect(7f,5f,10f,19f,ink);rect(14f,5f,17f,19f,ink)}
            symbol=="-10"||symbol=="+10"->{
                val back=symbol=="-10";val arc=Path()
                if(cut){arc.moveTo(6f,5f);arc.lineTo(17f,5f);arc.lineTo(21f,9f);arc.lineTo(21f,17f);arc.lineTo(17f,21f);arc.lineTo(7f,21f);arc.lineTo(3f,17f);arc.lineTo(3f,11f)}
                else arc.addArc(3f,3f,21f,21f,if(back)205f else -25f,if(back)-285f else 285f)
                stroke(arc,body,if(line)1.65f else 2.7f)
                shape(if(back)path(3f,1f,3f,8f,10f,7f)else path(21f,1f,21f,8f,14f,7f),ink,outline=line)
                if(duo)stroke(Path().apply{addArc(3f,3f,21f,21f,55f,35f)},accent,2.7f)
                p.color=ink;p.style=Paint.Style.FILL;p.typeface=if(cut)Typeface.MONOSPACE else Typeface.create("sans-serif-medium",Typeface.NORMAL);p.textSize=9f;p.textAlign=Paint.Align.CENTER;c.drawText("10",12f,16f,p)
            }
            symbol=="volume"->{
                shape(path(2f,9f,7f,9f,12f,4f,12f,20f,7f,15f,2f,15f));stroke(Path().apply{addArc(10f,6f,20f,18f,-60f,120f);moveTo(20f,3f);if(cut){lineTo(23f,8f);lineTo(23f,16f);lineTo(20f,21f)}else{addArc(8f,2f,25f,22f,-55f,110f)}},ink)
                if(duo)stroke(Path().apply{moveTo(7f,9f);lineTo(7f,15f)},accent,2.5f)
            }
            symbol=="brightness"->{
                circle(12f,12f,4f,ink)
                for(i in 0..7){val a=i*PI/4;stroke(Path().apply{moveTo((12+cos(a)*8).toFloat(),(12+sin(a)*8).toFloat());lineTo((12+cos(a)*10.5).toFloat(),(12+sin(a)*10.5).toFloat())},if(duo)body else ink)}
            }
            symbol=="settings"->{
                val gear=Path();repeat(32){i->val a=i*PI/16-PI/2;val r=if(i%4==0||i%4==3)10.5 else 8.4;val xx=(12+cos(a)*r).toFloat();val yy=(12+sin(a)*r).toFloat();if(i==0)gear.moveTo(xx,yy)else gear.lineTo(xx,yy)};gear.close()
                gear.fillType=Path.FillType.EVEN_ODD;gear.addCircle(12f,12f,3.4f,Path.Direction.CW);shape(gear)
                if(duo)circle(12f,12f,3.3f,accent,outline=true)
            }
            symbol=="files"->{
                for(i in 0..2){val yy=6f+i*6;shape(path(2f,yy-2,6f,yy,2f,yy+2),if(duo&&i==0)accent else body,outline=line);stroke(Path().apply{moveTo(9f,yy);lineTo(21f,yy)},ink)}
            }
            symbol=="fit"->{
                val q=Path();for((sx,sy)in listOf(-1 to -1,1 to -1,-1 to 1,1 to 1)){q.moveTo(12+sx*4f,12+sy*9f);q.lineTo(12+sx*9f,12+sy*9f);q.lineTo(12+sx*9f,12+sy*4f)};stroke(q,ink,if(line)1.65f else 2.8f)
            }
            symbol=="lock"||symbol=="unlock"->{
                val open=symbol=="unlock";stroke(Path().apply{if(cut){moveTo(7f,11f);lineTo(7f,5f);lineTo(10f,2f);lineTo(15f,2f);lineTo(18f,5f);if(!open)lineTo(18f,11f)}else{addArc(7f,2f,18f,15f,180f,if(open)155f else 180f);moveTo(7f,8f);lineTo(7f,11f);if(!open){moveTo(18f,8f);lineTo(18f,11f)}}},ink,if(line)1.65f else 2.5f)
                val box=if(cut)path(5f,10f,19f,10f,21f,12f,21f,21f,19f,23f,5f,23f,3f,21f,3f,12f)else Path().apply{addRoundRect(3f,10f,21f,23f,2.5f,2.5f,Path.Direction.CW)}
                if(!line){box.fillType=Path.FillType.EVEN_ODD;box.addCircle(12f,16f,1.5f,Path.Direction.CW);box.addRect(11.3f,16f,12.7f,20f,Path.Direction.CW)};shape(box)
                if(line){circle(12f,16f,1.1f,ink,true);stroke(Path().apply{moveTo(12f,17f);lineTo(12f,19f)})}
                if(duo)stroke(Path().apply{moveTo(4f,22f);lineTo(20f,22f)},accent)
            }
            symbol=="tracks"->{
                for(i in 0..4){val h=floatArrayOf(3f,7f,10f,6f,3f)[i];stroke(Path().apply{moveTo(2+i*3f,11-h/2);lineTo(2+i*3f,11+h/2)},if(duo)accent else ink,if(line)1.5f else 2f)}
                val box=Path().apply{addRoundRect(12f,12f,24f,22f,if(cut)0f else 2f,if(cut)0f else 2f,Path.Direction.CW)}
                if(duo)shape(box,body,false)else stroke(box,ink,1.6f)
                stroke(Path().apply{moveTo(15f,15.5f);lineTo(21f,15.5f);moveTo(15f,18.5f);lineTo(19f,18.5f)},ink,1.5f)
            }
            symbol=="levels"->{
                for(i in 0..2){val xx=5f+i*7;val yy=if(i==1)8f else 16f;stroke(Path().apply{moveTo(xx,2f);lineTo(xx,22f)},body);rect(xx-2,yy-2,xx+2,yy+2,if(duo)accent else ink)}
            }
            symbol=="tape"->{stroke(Path().apply{moveTo(12f,2f);lineTo(12f,22f)},body);for(i in 0..4)stroke(Path().apply{moveTo(7f,3+i*4f);lineTo(17f,3+i*4f)},ink);if(duo)circle(12f,12f,2.5f,accent,false)}
            speed->{
                p.style=Paint.Style.FILL;p.color=ink;p.typeface=if(cut)Typeface.MONOSPACE else Typeface.create("sans-serif-medium",Typeface.NORMAL);p.textSize=if(symbol.length>4)9f else 12f;p.textAlign=Paint.Align.CENTER;c.drawText(symbol,12f,16f,p)
                if(duo)stroke(Path().apply{addArc(5f,17f,19f,23f,180f,180f)},accent,1.8f)
            }
        }
        c.restore();p.pathEffect=null;p.color=color;p.typeface=Typeface.DEFAULT;p.style=Paint.Style.STROKE;return true
    }
}

internal class IconFamilyPreview(context:Context,private val family:String):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription="Превью ${iconFamilyName(family)}: воспроизведение, пауза, перемотка, звук, яркость и другие значки"}
    override fun onDraw(c:Canvas){
        val symbols=listOf("play","pause","-10","+10","1×","volume","brightness","settings","files","fit","lock","unlock","tracks","levels")
        val columns=7;val cell=width/columns.toFloat();val row=height/2f;val size=minOf(context.dp(27).toFloat(),cell*.64f)
        symbols.forEachIndexed{i,s->val x=(i%columns+.5f)*cell;val y=(i/columns+.5f)*row;p.color=0xFFE7EAE5.toInt()
            if(s=="play"||s=="pause"){p.style=Paint.Style.STROKE;p.strokeWidth=context.dp(1).toFloat();p.color=ACCENT;c.drawCircle(x,y,size*.7f,p);p.color=0xFFE7EAE5.toInt()}
            FamilyIcons.draw(c,p,s,x,y,size,family)
        }
    }
}
