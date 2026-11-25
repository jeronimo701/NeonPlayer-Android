package neon.androidplayer

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.Window
import android.graphics.Rect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal interface DialogBoundsHost:DeckBoundsProvider {
    fun registerBoundDialog(dialog:Dialog,resize:()->Unit)
    fun unregisterBoundDialog(dialog:Dialog)
}
/** Fixed display coordinates prevent IME/navigation insets from pushing a lower-half sheet over video. */
internal fun boundWindowToArea(window:Window,area:Rect,width:Int=WindowManager.LayoutParams.MATCH_PARENT,side:Int?=null){
    WindowCompat.setDecorFitsSystemWindows(window,false)
    if(window.attributes.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN==0)window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    WindowCompat.getInsetsController(window,window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    val params=window.attributes
    val gravity=Gravity.TOP or (side?:Gravity.CENTER_HORIZONTAL)
    if(params.width!=width||params.height!=area.height()||params.y!=area.top||params.gravity!=gravity||params.dimAmount!=0f){
        params.width=width;params.height=area.height();params.gravity=gravity;params.x=0;params.y=area.top;params.dimAmount=0f
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;window.attributes=params
    }
}

/** Retain the Activity owner: Dialog.context is a theme wrapper, not the fold-bounds provider. */
internal class PlayerAlertBuilder(private val owner:Context):AlertDialog.Builder(owner,AppAppearance.dialogTheme) {
    private var applyCurrentBounds:(()->Unit)?=null
    override fun show():AlertDialog=create().also{it.show();applyCurrentBounds?.invoke()}
    override fun create():AlertDialog=super.create().also{dialog->
        val window=dialog.window?:return@also
        val normalDim=window.attributes.dimAmount
        var wasBound=false
        val resize:()->Unit={
            val area=(owner as? DeckBoundsProvider)?.deckArea()
            if(area!=null){wasBound=true;boundWindowToArea(window,area)}
            else if(wasBound){window.attributes=window.attributes.apply{y=0;softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE};window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);window.setGravity(Gravity.CENTER);window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT);window.setDimAmount(normalDim)}
        }
        applyCurrentBounds=resize
        resize()
        window.decorView.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->
            val area=(owner as?DeckBoundsProvider)?.deckArea()
            if(area!=null&&(window.attributes.height!=area.height()||window.attributes.y!=area.top))resize()
        }
        window.decorView.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){(owner as? DialogBoundsHost)?.registerBoundDialog(dialog,resize);resize()}
            override fun onViewDetachedFromWindow(v:View){(owner as? DialogBoundsHost)?.unregisterBoundDialog(dialog)}
        })
    }
}
