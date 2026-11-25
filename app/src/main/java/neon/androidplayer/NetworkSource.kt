package neon.androidplayer

import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest

/** Persistent identity only. Passwords, authorization headers and loopback URLs never enter the library. */
internal data class NetworkSource(val connection:Connection,val path:String,val hash:String="",val index:Int=-1) {
    fun json()=JSONObject().put("type",connection.type).put("host",connection.host).put("port",connection.port)
        .put("share",connection.path).put("user",connection.user).put("domain",connection.domain)
        .put("path",path).put("hash",hash).put("index",index)
    fun media(title:String,folder:Boolean=false,bytes:Long=-1):MediaRef {
        val encoded=json().toString()
        val id=MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString(""){"%02x".format(it)}
        return MediaRef("neon-source://item/$id",title,folder,bytes,source=encoded)
    }
    companion object {
        fun read(media:MediaRef):NetworkSource? {
            if(Uri.parse(media.uri).scheme!="neon-source")return null
            val o=JSONObject(media.source)
            val type=o.getString("type");require(type in listOf("SMB","FTP","SFTP","TorrServer"))
            val host=o.getString("host");val port=o.getInt("port");require(port in 1..65535)
            if(type=="TorrServer")checkedBase(host)else require(host.isNotBlank()&&host.none{it in "@/\\"})
            if(type=="TorrServer")require(!media.folder&&o.optString("hash").isNotBlank()&&o.optInt("index",-1)>=0)
            require(!o.has("password")&&!o.has("headers"))
            return NetworkSource(Connection(type,host,port,o.optString("share"),o.optString("user"),domain=o.optString("domain")),o.getString("path"),o.optString("hash"),o.optInt("index",-1))
        }
    }
}
