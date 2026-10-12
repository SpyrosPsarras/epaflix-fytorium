package com.epaflix.fytorium

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Tokens(val access: String, val refresh: String)
interface CredentialStore {
    val tokensState: StateFlow<Tokens?>
    var tokens: Tokens?
    var pending: LoginAttempt?
    fun clear()
}

class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "fytorium.credentials"
    private val savedTokens = MutableStateFlow<Tokens?>(null)
    override val tokensState: StateFlow<Tokens?> = savedTokens.asStateFlow()
    init { savedTokens.value = read("tokens")?.let { Tokens(it.getString("access"), it.getString("refresh")) } }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun read(name: String): JSONObject? {
        val encoded = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray())
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { clear(); null }
    }
    @Synchronized private fun write(name: String, json: JSONObject?) {
        val editor = prefs.edit()
        if (json == null) editor.remove(name) else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name.toByteArray())
            editor.putString(name, Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(json.toString().toByteArray())))
        }
        check(editor.commit()) { "Credential storage unavailable" }
    }
    override var tokens: Tokens?
        get() = savedTokens.value
        @Synchronized set(value) {
            write("tokens", value?.let { JSONObject().put("access", it.access).put("refresh", it.refresh) })
            savedTokens.value = value
        }
    override var pending: LoginAttempt?
        get() = read("pending")?.let { LoginAttempt(it.getString("verifier"), it.getString("state"), it.getLong("createdAt")) }
        set(value) = write("pending", value?.let { JSONObject().put("verifier", it.verifier).put("state", it.state).put("createdAt", it.createdAt) })
    @Synchronized override fun clear() {
        check(prefs.edit().clear().commit()) { "Credential storage unavailable" }
        savedTokens.value = null
    }
}
