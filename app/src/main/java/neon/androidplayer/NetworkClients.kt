package neon.androidplayer

import android.media.MediaDataSource
import android.net.Uri
import android.util.Base64
import com.jcraft.jsch.*
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.UUID

data class Connection(val type: String, val host: String, val port: Int, val path: String = "", val user: String = "",
    val password: String = "", val domain: String = "", val fingerprint: String = "") {
    override fun toString()="Connection(type=$type, host=$host, port=$port, user=$user, password=<redacted>)"
    internal fun smb() = Sources.Smb(host, port, path, Sources.Login(user,password,domain))
    internal fun torr() = Sources.Torr(checkedBase(host), Sources.Login(user,password,""))
}
data class RemoteEntry(val name: String, val path: String, val folder: Boolean, val size: Long)
data class TorrentHit(val title: String, val link: String, val size: Long, val seeds: Int, val tracker: String,
    val peers:Int=-1,val published:String="",val updated:String="",val quality:Int=0,val year:Int=0,
    val videoType:String="",val voices:List<String> = emptyList(),val seasons:List<String> = emptyList(),val page:String="")
data class HostTrust(val actual: String, val previous: String): IOException("Ключ SSH-сервера ${if(previous.isEmpty()) "ещё не подтверждён" else "изменился"}: $actual")

internal fun checkedBase(raw: String): String {
    val uri = URI(raw.trim())
    require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Нужен адрес http(s)://сервер[:порт] без пароля, запроса и #" }
    return raw.trim().trimEnd('/')
}
internal fun queryValue(value: String) = URLEncoder.encode(value, "UTF-8")
internal fun jsonHttp(url: String, body: JSONObject? = null, headers: Map<String,String> = emptyMap(), timeoutMs:Int=15000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.instanceFollowRedirects = false; c.connectTimeout = minOf(6000,timeoutMs); c.readTimeout = timeoutMs
    c.setRequestProperty("User-Agent", "NeonPlayer/0.1"); headers.forEach(c::setRequestProperty)
    try {
        if(body!=null) { c.requestMethod="POST"; c.doOutput=true; c.setRequestProperty("Content-Type","application/json"); c.outputStream.use{it.write(body.toString().toByteArray())} }
        val status = c.responseCode
        if(status !in 200..299) throw IOException("HTTP $status: ${if(status==403) "сервис отклонил запрос; проверь адрес/ключ" else if(status==401) "нужна авторизация" else "ошибка сервера"}")
        return c.inputStream.use { Sources.readText(it,8*1024*1024) }
    } finally { c.disconnect() }
}
internal fun parseTorrentResults(raw: String): List<TorrentHit> {
    val text = raw.trim()
    val array = if(text.startsWith("[")) JSONArray(text) else {
        val obj = JSONObject(text)
        obj.optJSONArray("Results") ?: obj.optJSONArray("results") ?: throw IOException("Ответ не содержит результатов JacRed")
    }
    return (0 until array.length()).mapNotNull { index ->
        val row = array.optJSONObject(index) ?: return@mapNotNull null
        val title = row.optString("title", row.optString("Title"))
        val link = row.optString("magnet").ifBlank { row.optString("MagnetUri") }.ifBlank { row.optString("Link") }
        if(title.isBlank() || !(link.startsWith("magnet:?") || link.startsWith("https://") || link.startsWith("http://"))) null
        else TorrentHit(title,link,row.torrentLong("size","Size"),row.torrentLong("sid","seeders","Seeders").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),row.optString("tracker",row.optString("Tracker")),
            peers=row.torrentLong("pir","peers","Peers").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            published=row.torrentText("createTime","PublishDate","date"),updated=row.torrentText("updateTime","Updated"),
            quality=row.torrentLong("quality","Quality").coerceAtLeast(0).toInt(),year=row.torrentLong("relased","year","Year").coerceAtLeast(0).toInt(),
            videoType=row.torrentText("videotype","VideoType"),voices=row.torrentTags("voices"),seasons=row.torrentTags("seasons"),page=row.torrentText("url","Details"))
    }.take(2000)
}
private fun JSONObject.torrentLong(vararg names:String):Long=names.firstNotNullOfOrNull{key->if(isNull(key))null else opt(key)?.toString()?.toDoubleOrNull()?.takeIf{it.isFinite()&&it>=0&&it<Long.MAX_VALUE.toDouble()}?.toLong()}?:-1
private fun JSONObject.torrentText(vararg names:String)=names.firstNotNullOfOrNull{key->if(isNull(key))null else optString(key).takeIf{it.isNotBlank()&&it!="null"}}.orEmpty()
private fun JSONObject.torrentTags(key:String):List<String>{val a=optJSONArray(key)?:return emptyList();return (0 until a.length()).mapNotNull{if(a.isNull(it))null else a.optString(it).takeIf{it.isNotBlank()}}}
object TorrentApi {
    fun search(base: String, key: String, query: String, jackett: Boolean): List<TorrentHit> {
        require(query.trim().length>=2) { "Введи хотя бы два символа" }
        val endpoint = if(jackett) "/api/v2.0/indexers/all/results?query=" else "/api/v1.0/torrents?search="
        return parseTorrentResults(jsonHttp(checkedBase(base)+endpoint+queryValue(query.trim()),headers=if(key.isBlank())emptyMap()else mapOf("X-Api-Key" to key)))
    }
    fun list(c: Connection): List<JSONObject> {
        val rows = c.torr().torrents(); return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }
    fun add(c: Connection, hit: TorrentHit, save: Boolean): JSONObject {
        val raw=jsonHttp(checkedBase(c.host)+"/torrents",JSONObject().put("action","add").put("link",hit.link).put("title",hit.title).put("save_to_db",save),c.torr().headers())
        val result = JSONObject(raw)
        require(result.optString("hash").isNotBlank()) { "TorrServer не вернул hash" }; return result
    }
    internal fun files(c: Connection, hash: String, summary: JSONObject): List<Sources.Entry> = c.torr().files(hash,summary)
}

object RemoteClients {
    fun list(c: Connection, path: String): List<RemoteEntry> = when(c.type) {
        "SMB" -> if(c.path.isBlank()) c.smb().shares().map { RemoteEntry(it,it,true,-1) }
            else c.smb().list(path).map { RemoteEntry(it.label(),it.path(),it.folder(),it.size()) }
        "FTP" -> ftp(c).let { client -> try {
            client.listFiles(path.ifEmpty { "/" }).filter { it.name !in listOf(".","..") }.map { RemoteEntry(it.name, join(path,it.name),it.isDirectory,it.size) }
        } finally { client.disconnect() } }
        "SFTP" -> ssh(c).let { (session,channel) -> try {
            channel.ls(path.ifEmpty { "/" }).filterIsInstance<ChannelSftp.LsEntry>().filter { it.filename !in listOf(".","..") }
                .map { RemoteEntry(it.filename,join(path,it.filename),it.attrs.isDir,it.attrs.size) }
        } finally { channel.disconnect();session.disconnect() } }
        else -> error("Неизвестный протокол")
    }.filter { it.folder || Sources.media(it.name) }.sortedWith(compareBy<RemoteEntry>{!it.folder}.thenBy{it.name.lowercase()})

    fun file(c: Connection, entry: RemoteEntry): RemoteObject = when(c.type) {
        "SMB" -> {
            val data=c.smb().file(entry.path)
            object : RemoteObject {
                override val size=data.size; override val name=entry.name
                override fun open(offset: Long): InputStream = object:InputStream() {
                    var at=offset
                    override fun read():Int {val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255}
                    override fun read(b:ByteArray, off:Int, len:Int):Int {val n=data.readAt(at,b,off,len);if(n>0)at+=n;return n}
                }
                override fun close(){data.close()}
            }
        }
        "FTP" -> object:RemoteObject {
            override val size=entry.size;override val name=entry.name
            override fun open(offset:Long):InputStream {
                val client=ftp(c)
                try {
                    client.restartOffset=offset
                    val input=client.retrieveFileStream(entry.path) ?: throw IOException("FTP: ${client.replyString}")
                    return object:FilterInputStream(input) { override fun close(){try{super.close()}finally{client.disconnect()}} }
                }catch(e:Exception){client.disconnect();throw e}
            }
            override fun close(){}
        }
        "SFTP" -> object:RemoteObject {
            override val size=entry.size;override val name=entry.name
            override fun open(offset:Long):InputStream {
                val (session,channel)=ssh(c)
                try {
                    val input=channel.get(entry.path,null,offset)
                    return object:FilterInputStream(input){override fun close(){try{super.close()}finally{channel.disconnect();session.disconnect()}}}
                }catch(e:Exception){channel.disconnect();session.disconnect();throw e}
            }
            override fun close(){}
        }
        else -> error("Неизвестный протокол")
    }
    private fun join(path:String,name:String)=path.trimEnd('/')+"/"+name
    private fun ftp(c:Connection):FTPClient {
        val client=FTPClient();client.connectTimeout=6000;client.defaultTimeout=15000;client.setDataTimeout(java.time.Duration.ofSeconds(20));client.controlEncoding="UTF-8";client.autodetectUTF8=true
        try {
            client.connect(c.host,c.port)
            require(client.login(c.user.ifBlank { "anonymous" },c.password.ifBlank { if(c.user.isBlank())"neon@example.invalid" else "" })) { "FTP отклонил вход" }
            client.enterLocalPassiveMode();client.setFileType(FTP.BINARY_FILE_TYPE)
            return client
        }catch(e:Exception){if(client.isConnected)client.disconnect();throw e}
    }
    private fun ssh(c:Connection):Pair<Session,ChannelSftp> {
        val jsch=JSch();var observed=""
        jsch.hostKeyRepository=object:HostKeyRepository {
            override fun check(host:String,key:ByteArray):Int {
                observed="SHA256:"+Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key),Base64.NO_WRAP or Base64.NO_PADDING)
                return if(c.fingerprint.isNotBlank() && observed==c.fingerprint)HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
            }
            override fun add(key:HostKey,info:UserInfo?){}
            override fun remove(host:String,type:String?){}
            override fun remove(host:String,type:String?,key:ByteArray?){}
            override fun getKnownHostsRepositoryID()="Neon confirmed fingerprints"
            override fun getHostKey():Array<HostKey>?=null
            override fun getHostKey(host:String?,type:String?):Array<HostKey>?=null
        }
        val session=jsch.getSession(c.user,c.host,c.port)
        session.setPassword(c.password);session.setConfig("StrictHostKeyChecking","yes");session.timeout=20000
        try {session.connect(7000);val channel=session.openChannel("sftp") as ChannelSftp;channel.connect(7000);return session to channel}
        catch(e:Exception){session.disconnect();if(observed.isNotBlank() && observed!=c.fingerprint)throw HostTrust(observed,c.fingerprint);throw e}
    }
}

interface RemoteObject:Closeable {val name:String;val size:Long;fun open(offset:Long):InputStream}
data class ByteRange(val start:Long,val end:Long) {val count get()=end-start+1}
internal fun parseRange(raw:String?,size:Long):ByteRange {
    require(size>=0)
    if(raw==null) return ByteRange(0,size-1)
    require(raw.startsWith("bytes=") && !raw.contains(',')) { "Один byte range" }
    val halves=raw.removePrefix("bytes=").split('-',limit=2);require(halves.size==2)
    val start:Long;val end:Long
    if(halves[0].isBlank()){val suffix=halves[1].toLong();require(suffix>0);start=(size-suffix).coerceAtLeast(0);end=size-1}
    else{start=halves[0].toLong();end=halves[1].takeIf{it.isNotBlank()}?.toLong()?.coerceAtMost(size-1) ?: (size-1)}
    require(start>=0 && start<size && end>=start);return ByteRange(start,end)
}

/** A loopback-only, token-protected byte-range bridge. Credentials never leave the source client. */
class StreamBridge(private val remote:RemoteObject):Closeable {
    private val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val token=UUID.randomUUID().toString()
    private val workers=Executors.newFixedThreadPool(3)
    @Volatile private var closed=false
    val url="http://127.0.0.1:${server.localPort}/$token"
    init {
        require(remote.size>=0) { "Сервер не сообщил размер файла" }
        Thread({while(!closed){try{val socket=server.accept();workers.submit{serve(socket)}}catch(_:IOException){break}}},"Neon range bridge").apply{isDaemon=true;start()}
    }
    private fun serve(socket:Socket) {socket.use { s ->
        s.soTimeout=15000
        val input=s.getInputStream().buffered();val output=s.getOutputStream().buffered()
        fun line():String {val text=StringBuilder();while(text.length<4096){val b=input.read();if(b<0||b==10)break;if(b!=13)text.append(b.toChar())};require(text.length<4096);return text.toString()}
        fun respond(status:String,headers:String=""){output.write("HTTP/1.1 $status\r\nConnection: close\r\n$headers\r\n".toByteArray(StandardCharsets.US_ASCII));output.flush()}
        try {
            val request=line().split(' ');if(request.size!=3 || request[1]!="/$token" || request[0] !in listOf("GET","HEAD")){respond("404 Not Found");return}
            var range:String?=null;var lines=0
            while(true){val header=line();if(header.isEmpty())break;require(++lines<64);if(header.startsWith("Range:",true))range=header.substringAfter(':').trim()}
            val r=try{parseRange(range,remote.size)}catch(_:Exception){respond("416 Range Not Satisfiable","Content-Range: bytes */${remote.size}\r\nContent-Length: 0\r\n");return}
            remote.open(r.start).use { source ->
                respond(if(range==null)"200 OK" else "206 Partial Content","Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nContent-Length: ${r.count}\r\n"+
                    if(range==null)"" else "Content-Range: bytes ${r.start}-${r.end}/${remote.size}\r\n")
                if(request[0]=="HEAD")return
                val buffer=ByteArray(128*1024);var remaining=r.count
                while(remaining>0&&!closed){val n=source.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(n<0)break;output.write(buffer,0,n);remaining-=n};output.flush()
            }
        }catch(_:Exception) { /* Close a failed HTTP range; libmpv exposes a read failure instead of corrupt bytes. */ }
    } }
    override fun close(){closed=true;server.close();workers.shutdownNow();remote.close()}
}
