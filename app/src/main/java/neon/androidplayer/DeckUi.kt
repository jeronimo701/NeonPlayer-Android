package neon.androidplayer

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal val DECK_BG get()=AppAppearance.background
internal val DECK_MUTED get()=AppAppearance.muted
internal fun Context.deckSurface(active:Boolean=false):android.graphics.drawable.Drawable {
    fun shape(color:Int,stroke:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(14).toFloat();setStroke(dp(1),stroke)}
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed),shape(AppAppearance.pressed,ACCENT))
        addState(intArrayOf(android.R.attr.state_focused),shape(AppAppearance.pressed,ACCENT))
        addState(intArrayOf(),shape(if(active)AppAppearance.selected else AppAppearance.surface,if(active)ACCENT else AppAppearance.stroke))
    }
}
internal fun Context.deckText(value:String,size:Int=15,muted:Boolean=false,bold:Boolean=false)=TextView(this).apply {
    text=value;textSize=size.toFloat()*UiSizing.percent/100f;setTextColor(if(muted)DECK_MUTED else 0xFFF0F2F5.toInt())
    if(bold)setTypeface(typeface,Typeface.BOLD)
    setLineSpacing(dp(3).toFloat(),1f)
}
internal fun Context.deckButton(title:String,primary:Boolean=false,action:()->Unit)=TextView(this).apply {
    text=title;textSize=14f*UiSizing.percent/100f;setTextColor(if(primary)ACCENT else INK);gravity=Gravity.CENTER
    minHeight=dp(48);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(12),dp(10),dp(12),dp(10));background=deckSurface(primary)
    isClickable=true;isFocusable=true;setOnClickListener{action()}
}
internal data class DeckRow(val title:String,val detail:String="",val badge:String="",val actions:List<Pair<String,()->Unit>> = emptyList(),val selected:Boolean=false,val previewStyle:String?=null,val iconDrawable:Int?=null,val section:Boolean=false)

/** Virtualized readable rows: never construct hundreds of offscreen result cards. */
internal class DeckRows(private val context:Context,private val rows:List<DeckRow>):BaseAdapter() {
    override fun areAllItemsEnabled()=false
    override fun isEnabled(position:Int)=!rows[position].section
    override fun getCount()=rows.size
    override fun getItem(position:Int)=rows[position]
    override fun getItemId(position:Int)=position.toLong()
    override fun getView(position:Int,convert:View?,parent:ViewGroup):View {
        val row=rows[position]
        if(row.section)return LinearLayout(context).apply{
            orientation=LinearLayout.VERTICAL;isEnabled=false;setPadding(context.dp(4),context.dp(18),context.dp(4),context.dp(7))
            addView(View(context).apply{setBackgroundColor(AppAppearance.stroke)},LinearLayout.LayoutParams(-1,context.dp(1)))
            addView(context.deckText(row.title,13,muted=true,bold=true).apply{setPadding(0,context.dp(12),0,0)})
        }
        val outer=(convert as? FrameLayout)?:FrameLayout(context)
        outer.removeAllViews();outer.setPadding(0,context.dp(5),0,context.dp(5))
        val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;background=context.deckSurface(row.selected||row.badge in listOf("Выбрано","Выбрана","Применён","Используется"));isSelected=row.selected;setPadding(context.dp(16),context.dp(14),context.dp(16),context.dp(14))}
        row.iconDrawable?.let{content.addView(ImageView(context).apply{setImageResource(it);contentDescription=row.title;scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(context.dp(80),context.dp(80)).apply{bottomMargin=context.dp(8)})}
        row.previewStyle?.let{content.addView(IconFamilyPreview(context,it),LinearLayout.LayoutParams(-1,context.dp(96)))}
        content.addView(context.deckText((if(row.selected)"✓  "else "")+row.title,16,bold=true))
        if(row.detail.isNotEmpty())content.addView(context.deckText(row.detail,13,muted=true).apply{setPadding(0,context.dp(8),0,context.dp(3))})
        if(row.badge.isNotBlank())content.addView(context.deckText(row.badge,12).apply{setTextColor(ACCENT);setPadding(0,context.dp(6),0,0)})
        if(row.actions.isNotEmpty()) {
            val actions=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,context.dp(12),0,0)}
            row.actions.forEachIndexed {index,(label,action)->actions.addView(context.deckButton(label,index==0,action),LinearLayout.LayoutParams(0,-2,1f).apply{if(index>0)leftMargin=context.dp(8)})}
            content.addView(actions)
        }
        outer.addView(content,FrameLayout.LayoutParams(-1,-2));return outer
    }
}

internal interface DeckBoundsProvider {fun deckArea():android.graphics.Rect?}

/** Full app-owned screen; native system chooser is only used to grant access, not to browse every file. */
internal class DeckScreen(context:Context,title:String,subtitle:String="",val side:Int?=null):Dialog(context) {
    private val owner=context
    var navigateBack:(()->Unit)?=null
    val column=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(DECK_BG);setPadding(context.dp(16),context.dp(12),context.dp(16),context.dp(12))}
    val toolbar=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    val actions=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,context.dp(8),0,context.dp(10))}
    val list=ListView(context).apply{divider=null;dividerHeight=0;setBackgroundColor(Color.TRANSPARENT);itemsCanFocus=true;clipToPadding=false}
    val status=context.deckText(subtitle,13,muted=true).apply{setPadding(context.dp(4),context.dp(8),context.dp(4),context.dp(6))}
    val heading=context.deckText(title,if(side==null)22 else 18,bold=true).apply{setPadding(context.dp(14),0,0,0);if(side!=null){maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}}
    init {
        window?.requestFeature(android.view.Window.FEATURE_NO_TITLE)
        toolbar.addView(context.deckButton("‹",false){navigateBack?.invoke()?:dismiss()},LinearLayout.LayoutParams(context.dp(48),context.dp(48)))
        toolbar.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        column.addView(toolbar);column.addView(status);column.addView(actions);column.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(column)
        window?.decorView?.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){(owner as? DialogBoundsHost)?.registerBoundDialog(this@DeckScreen){applyBounds()}}
            override fun onViewDetachedFromWindow(v:View){(owner as? DialogBoundsHost)?.unregisterBoundDialog(this@DeckScreen)}
        })
    }
    fun refreshPalette(){
        column.setBackgroundColor(DECK_BG);window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DECK_BG));heading.setTextColor(0xFFF0F2F5.toInt());status.setTextColor(DECK_MUTED)
        for(i in 0 until toolbar.childCount)toolbar.getChildAt(i).takeIf{it is TextView && it!=heading}?.let{it.background=context.deckSurface()}
    }
    fun rows(rows:List<DeckRow>,click:((Int)->Unit)?=null,hold:((Int)->Unit)?=null){
        list.adapter=DeckRows(context,rows)
        list.setOnItemClickListener{_,_,position,_->click?.invoke(position)}
        list.setOnItemLongClickListener{_,_,position,_->if(hold==null)false else {hold(position);true}}
    }
    fun action(title:String,primary:Boolean=false,click:()->Unit){actions.addView(context.deckButton(title,primary,click),LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=context.dp(5)})}
    @Deprecated("Android dialog compatibility")
    override fun onBackPressed(){navigateBack?.invoke()?:super.onBackPressed()}
    fun applyBounds(){window?.let{w->
        val area=(owner as? DeckBoundsProvider)?.deckArea()
        val width=context.resources.displayMetrics.widthPixels;val landscape=width>context.resources.displayMetrics.heightPixels
        val desiredWidth=if(side==null)-1 else if(landscape)width/3 else width/2
        if(area!=null)boundWindowToArea(w,area,desiredWidth,side)
        else{w.attributes=w.attributes.apply{y=0;x=0};w.setGravity(if(side==null)Gravity.CENTER else side or Gravity.CENTER_VERTICAL);w.setLayout(desiredWidth,-1);w.setDimAmount(.15f)}
    }}
    override fun show(){
        super.show();window?.apply{
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DECK_BG))
            WindowCompat.setDecorFitsSystemWindows(this,false)
            WindowCompat.getInsetsController(this,decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        applyBounds()
        ViewCompat.setOnApplyWindowInsetsListener(column){v,insets->val safe=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.navigationBars());v.setPadding(context.dp(16)+safe.left,context.dp(12)+(if((owner as?DeckBoundsProvider)?.deckArea()!=null)0 else safe.top),context.dp(16)+safe.right,context.dp(12)+safe.bottom);insets}
        ViewCompat.requestApplyInsets(column)
        when(context.getSharedPreferences("neon",Context.MODE_PRIVATE).getString("controlsAnimation","fade")){
            "fade"->{column.alpha=0f;column.animate().alpha(1f).setDuration(160).start()}
            "slide"->{column.alpha=0f;column.translationY=context.dp(18).toFloat();column.animate().translationY(0f).alpha(1f).setDuration(200).start()}
            "scale"->{column.alpha=0f;column.scaleX=.97f;column.scaleY=.97f;column.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()}
        }
    }
}
