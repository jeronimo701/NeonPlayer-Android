package neon.androidplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Temporary read-only content grants. No network login is encoded in the URI or handed to the recipient. */
internal object SharedNetworkFiles {
    data class Grant(val title:String,val expires:Long,val factory:()->RemoteObject)
    private val grants=linkedMapOf<String,Grant>()
    @Synchronized fun publish(context:Context,title:String,factory:()->RemoteObject):Uri {
        grants.entries.removeAll{it.value.expires<SystemClock.elapsedRealtime()}
        require(grants.size<32){"Слишком много активных передач. Перезапусти приложение или дождись истечения доступа"}
        val token=UUID.randomUUID().toString();grants[token]=Grant(title,SystemClock.elapsedRealtime()+2*60*60*1000,factory)
        return Uri.parse("content://${context.packageName}.network/$token")
    }
    @Synchronized fun find(uri:Uri):Grant {
        val grant=grants[uri.lastPathSegment]?:throw FileNotFoundException("Открой файл через Neon Player заново")
        if(grant.expires<SystemClock.elapsedRealtime())throw FileNotFoundException("Доступ истёк; поделись файлом заново")
        return grant
    }
}

class NetworkFileProvider:ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri)="video/*"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val grant=SharedNetworkFiles.find(uri)
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply{addRow(columns.map{if(it==OpenableColumns.DISPLAY_NAME)grant.title else null})}
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r")throw FileNotFoundException("Только чтение")
        val grant=SharedNetworkFiles.find(uri)
        val thread=HandlerThread("Neon shared file").apply{start()}
        // Connections are opened on the callback thread, not Android's main/UI thread.
        val callback=object:ProxyFileDescriptorCallback(){
            var remote:RemoteObject?=null;var input:InputStream?=null;var at=-1L
            fun file():RemoteObject=remote?:grant.factory().also{remote=it}
            override fun onGetSize():Long=try{file().size.also{if(it<0)throw IOException("Размер неизвестен")}}catch(e:Exception){throw ErrnoException("size",OsConstants.EIO)}
            override fun onRead(offset:Long,size:Int,data:ByteArray):Int=try {
                if(at!=offset||input==null){input?.close();input=file().open(offset);at=offset}
                val n=input!!.read(data,0,minOf(size,data.size));if(n>0){at+=n;n}else 0
            }catch(e:Exception){runCatching{input?.close()};input=null;at=-1;throw ErrnoException("read",OsConstants.EIO)}
            override fun onRelease(){runCatching{input?.close()};runCatching{remote?.close()};thread.quitSafely()}
        }
        return try{context!!.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,callback,Handler(thread.looper))}catch(e:Exception){thread.quitSafely();throw FileNotFoundException("Не удалось открыть сетевой файл")}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}

/** Range-capable authenticated TorrServer stream, also used by external-player content grants. */
internal class HttpRangeObject(private val url:String,override val name:String,private val headers:Map<String,String>):RemoteObject {
    private var length=-1L
    override val size:Long get(){if(length<0)open(0).close();return length}
    override fun open(offset:Long):InputStream {
        val c=URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects=false;c.connectTimeout=6000;c.readTimeout=20000
        headers.forEach(c::setRequestProperty);c.setRequestProperty("Range","bytes=$offset-")
        try {
            val status=c.responseCode
            require(status==206||(offset==0L&&status==200)){"Сервер не поддержал диапазон: HTTP $status"}
            val range=c.getHeaderField("Content-Range")
            if(status==206){require(range?.startsWith("bytes $offset-")==true);length=range.substringAfterLast('/').toLongOrNull()?:-1}
            else length=c.contentLengthLong
            return object:FilterInputStream(c.inputStream){override fun close(){try{super.close()}finally{c.disconnect()}}}
        }catch(e:Exception){c.disconnect();throw e}
    }
    override fun close(){}
}
