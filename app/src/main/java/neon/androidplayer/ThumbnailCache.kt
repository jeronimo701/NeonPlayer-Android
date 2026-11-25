package neon.androidplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

internal class ThumbnailCache(context:Context) {
    private val folder=File(context.cacheDir,"continue-frames").apply{mkdirs()}
    private fun file(uri:String)=File(folder,MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString(""){"%02x".format(it)}+".jpg")
    fun read(uri:String):Bitmap?=runCatching{BitmapFactory.decodeFile(file(uri).path)}.getOrNull()
    @Synchronized fun save(uri:String,bitmap:Bitmap){
        val target=file(uri);val temp=File(folder,target.name+".tmp")
        temp.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)}
        if(!temp.renameTo(target)){temp.copyTo(target,overwrite=true);temp.delete()}
        folder.listFiles{f->f.extension=="jpg"}.orEmpty().sortedByDescending{it.lastModified()}.drop(60).forEach{it.delete()}
    }
}
