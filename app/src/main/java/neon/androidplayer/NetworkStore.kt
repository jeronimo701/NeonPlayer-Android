package neon.androidplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class NetworkPin(val id:String,val name:String,val connection:Connection,val path:String,val wifi:String?=null)
internal fun pinVisible(pin:NetworkPin,ssid:String?,showAll:Boolean=false)=showAll||pin.wifi==null||(ssid!=null&&pin.wifi==ssid)

/** Secrets are encrypted with a non-exportable Android Keystore key; pin JSON has no password field. */
internal class NetworkStore(context:Context,name:String="network-store",private val keyAlias:String="neon-network-v1") {
    private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    companion object {private val memory=java.util.concurrent.ConcurrentHashMap<String,String>()}
    private fun id(c:Connection)=MessageDigest.getInstance("SHA-256").digest(listOf(c.type,c.host.lowercase(),c.port.toString(),c.user,c.domain).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(keyAlias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized private fun encrypt(value:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String {
        val bytes=Base64.decode(value,Base64.NO_WRAP);require(bytes.size>=28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
    fun hasSaved(c:Connection)=prefs.contains("secret.${id(c)}")
    fun known(c:Connection)=memory.containsKey(id(c))||hasSaved(c)||c.user.isBlank()
    fun remember(c:Connection,save:Boolean){
        val hash=id(c);memory[hash]=c.password
        val encrypted=if(save)encrypt(c.password)else null
        val edit=prefs.edit().putString("server.$hash",json(c).toString())
        if(encrypted==null)edit.remove("secret.$hash")else edit.putString("secret.$hash",encrypted)
        edit.apply()
    }
    fun resolve(c:Connection):Connection {
        if(c.password.isNotEmpty())return c
        val hash=id(c)
        val password=memory[hash]?:prefs.getString("secret.$hash",null)?.let{runCatching{decrypt(it)}.getOrElse{throw IllegalStateException("Сохранённый вход недоступен. Введи пароль заново")}}.orEmpty()
        return c.copy(password=password)
    }
    fun servers():List<Connection> = prefs.all.filterKeys{it.startsWith("server.")}.values.mapNotNull{runCatching{read(JSONObject(it as String))}.getOrNull()}
    fun forget(c:Connection){memory.remove(id(c));prefs.edit().remove("secret.${id(c)}").apply()}
    fun pins():List<NetworkPin> = prefs.all.filterKeys{it.startsWith("pin.")}.values.mapNotNull{runCatching{
        val o=JSONObject(it as String);NetworkPin(o.getString("id"),o.getString("name"),read(o.getJSONObject("connection")),o.getString("path"),o.optString("wifi").takeIf{it.isNotBlank()})
    }.getOrNull()}.sortedWith(Comparator{a,b->naturalCompare(a.name,b.name)})
    fun pin(c:Connection,path:String,title:String):NetworkPin {
        val existing=pins().firstOrNull{id(it.connection)==id(c)&&it.connection.path==c.path&&it.path==path}
        return (existing?:NetworkPin(UUID.randomUUID().toString(),title,c.copy(password=""),path)).also{savePin(it)}
    }
    fun savePin(pin:NetworkPin){
        require(pin.name.isNotBlank())
        require(pins().size<200||prefs.contains("pin.${pin.id}")){"Не больше 200 сетевых закрепов"}
        val o=JSONObject().put("id",pin.id).put("name",pin.name).put("connection",json(pin.connection)).put("path",pin.path).put("wifi",pin.wifi?:"")
        prefs.edit().putString("pin.${pin.id}",o.toString()).apply()
    }
    fun removePin(pin:NetworkPin){prefs.edit().remove("pin.${pin.id}").apply()}
    private fun json(c:Connection)=JSONObject().put("type",c.type).put("host",c.host).put("port",c.port).put("path",c.path).put("user",c.user).put("domain",c.domain).put("fingerprint",c.fingerprint)
    private fun read(o:JSONObject)=Connection(o.getString("type"),o.getString("host"),o.getInt("port"),o.optString("path"),o.optString("user"),"",o.optString("domain"),o.optString("fingerprint"))
}
