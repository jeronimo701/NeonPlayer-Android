package neon.androidplayer

import android.graphics.*
import kotlin.math.*

/** Original native paths following the approved black/white/orange control specimen. */
internal object OrangeControlIcons {
    fun draw(c:Canvas,p:Paint,s:String,x:Float,y:Float,size:Float):Boolean {
        val speed=s.endsWith("×")||s.endsWith("x")
        if(s !in listOf("play","pause","-10","+10","volume","brightness","settings","files","fit","lock","unlock","tracks","levels","tape")&&!speed)return false
        val oldColor=p.color;val a=Color.alpha(oldColor);val white=Color.argb(a,242,242,240);val orange=Color.argb(a,255,153,0)
        c.save();c.translate(x-size/2,y-size/2);c.scale(size/24,size/24)
        p.shader=null;p.pathEffect=null;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.strokeWidth=2.4f
        fun line(x1:Float,y1:Float,x2:Float,y2:Float,color:Int=white){p.style=Paint.Style.STROKE;p.color=color;c.drawLine(x1,y1,x2,y2,p)}
        fun rect(l:Float,t:Float,r:Float,b:Float,color:Int=white,stroke:Boolean=false){p.color=color;p.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL;c.drawRoundRect(l,t,r,b,1.6f,1.6f,p)}
        fun circle(x:Float,y:Float,r:Float,color:Int=white,stroke:Boolean=false){p.color=color;p.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL;c.drawCircle(x,y,r,p)}
        fun poly(color:Int,vararg points:Float){val path=Path().apply{moveTo(points[0],points[1]);var i=2;while(i<points.size){lineTo(points[i],points[i+1]);i+=2};close()};p.color=color;p.style=Paint.Style.FILL;c.drawPath(path,p)}
        when {
            speed->{rect(1f,4f,23f,20f,orange,true);p.style=Paint.Style.FILL;p.color=white;p.typeface=Typeface.DEFAULT_BOLD;p.textAlign=Paint.Align.CENTER;p.textSize=10f;while(p.measureText(s)>17f&&p.textSize>4f)p.textSize-=.25f;c.drawText(s,12f,12f-(p.fontMetrics.ascent+p.fontMetrics.descent)/2,p);p.typeface=Typeface.DEFAULT}
            s=="play"->poly(white,8f,5f,19f,12f,8f,19f)
            s=="pause"->{rect(6.5f,5f,10f,19f);rect(14f,5f,17.5f,19f)}
            s=="-10"||s=="+10"->{val back=s=="-10";p.style=Paint.Style.STROKE;p.color=white;c.drawArc(2.5f,2.5f,21.5f,21.5f,if(back)155f else 25f,if(back)-290f else 290f,false,p)
                if(back)poly(white,2f,5f,8f,1f,8f,8f)else poly(white,22f,5f,16f,1f,16f,8f)
                p.color=orange;p.style=Paint.Style.FILL;p.typeface=Typeface.DEFAULT_BOLD;p.textSize=9f;p.textAlign=Paint.Align.CENTER;c.drawText("10",12f,15.5f,p);p.typeface=Typeface.DEFAULT}
            s=="volume"->{poly(white,2f,9f,6f,9f,11f,5f,11f,19f,6f,15f,2f,15f);p.style=Paint.Style.STROKE;p.color=orange;for(r in listOf(5f,9f))c.drawArc(9-r,12-r,9+r,12+r,-52f,104f,false,p)}
            s=="brightness"->{circle(12f,12f,4.5f,white,true);repeat(8){val angle=it*PI/4;line(12+cos(angle).toFloat()*8,12+sin(angle).toFloat()*8,12+cos(angle).toFloat()*10.5f,12+sin(angle).toFloat()*10.5f,orange)}}
            s=="settings"->{repeat(8){c.save();c.rotate(it*45f,12f,12f);rect(9.5f,1f,14.5f,7f);c.restore()};circle(12f,12f,8f);circle(12f,12f,4.2f,orange);circle(12f,12f,1.7f,Color.argb(a,8,8,8))}
            s=="files"->{line(2f,5f,21f,5f);line(2f,12f,14f,12f);line(2f,19f,12f,19f);poly(orange,17f,11f,23f,16f,17f,21f)}
            s=="fit"->{for((xx,yy)in listOf(2f to 3f,22f to 3f,2f to 21f,22f to 21f)){line(xx,yy,xx+if(xx<12)5 else -5,yy,orange);line(xx,yy,xx,yy+if(yy<12)5 else -5,orange)}}
            s=="lock"||s=="unlock"->{p.color=orange;p.style=Paint.Style.STROKE;c.drawArc(6f,1f,18f,15f,180f,if(s=="lock")180f else 130f,false,p);rect(3f,10f,21f,23f);circle(12f,15f,2f,Color.argb(a,8,8,8));rect(11f,15f,13f,20f,Color.argb(a,8,8,8))}
            s=="tracks"->{rect(1f,4f,23f,21f,white,true);line(6f,9f,18f,9f);line(6f,15f,18f,15f,orange)}
            s=="levels"->{listOf(5f,12f,19f).forEachIndexed{i,yy->line(2f,yy,22f,yy);circle(if(i==1)8f else 16f,yy,2.5f,orange)}}
            s=="tape"->{line(12f,2f,12f,22f,orange);listOf(5f,9f,15f,19f).forEach{line(7f,it,17f,it)}}
        }
        p.style=Paint.Style.STROKE;p.color=oldColor;p.pathEffect=null;c.restore();return true
    }
}
