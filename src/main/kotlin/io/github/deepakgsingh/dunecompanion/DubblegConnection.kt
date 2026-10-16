package io.github.deepakgsingh.dunecompanion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Entered on the display phone only, encrypted at rest, and never served to the LAN panel. */
class DubblegConnection(context: Context) {
    private val preferences = context.getSharedPreferences("dubbleg_connection", Context.MODE_PRIVATE)
    data class Credential(val token: String, val deviceId: String, val name: String)

    fun credential(): Credential? = synchronized(LOCK) {
        val encrypted = preferences.getString("credential", null) ?: return@synchronized null
        runCatching {
            val stored = JSONObject(encrypted)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(stored.getString("iv"))))
            val json = JSONObject(String(cipher.doFinal(decode(stored.getString("ciphertext"))), Charsets.UTF_8))
            Credential(json.getString("token"), json.getString("deviceId"), json.getString("name"))
        }.getOrNull()
    }

    fun save(credential: Credential) = synchronized(LOCK) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("token", credential.token).put("deviceId", credential.deviceId).put("name", credential.name)
        val stored = JSONObject().put("iv", encode(cipher.iv)).put("ciphertext", encode(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))))
        check(preferences.edit().putString("credential", stored.toString()).commit()) { "Could not save connection" }
    }

    fun disconnect() = synchronized(LOCK) { check(preferences.edit().clear().commit()) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    companion object {
        private const val ALIAS = "dubbleg_stats_credential"
        private val LOCK = Any()
    }
}
