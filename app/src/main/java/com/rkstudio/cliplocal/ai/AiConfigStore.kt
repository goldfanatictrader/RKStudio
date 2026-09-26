package com.rkstudio.cliplocal.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiConfig(val endpoint: String = "", val model: String = "", val apiKey: String = "",
    val simpleKey: String = "") {
    val ready get() = endpoint.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    val simpleReady get() = simpleKey.isNotBlank()
}

class AiConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("rkstudio_ai_config", Context.MODE_PRIVATE)
    private val alias = "rkstudio_provider_key_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun seal(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP)
    }

    private fun open(value: String): String {
        if (value.isBlank()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Konfigurasi key rusak" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun read(): AiConfig = AiConfig(
        endpoint = prefs.getString("endpoint", "").orEmpty(),
        model = prefs.getString("model", "").orEmpty(),
        apiKey = runCatching { open(prefs.getString("key", "").orEmpty()) }.getOrDefault(""),
        simpleKey = runCatching { open(prefs.getString("simple", "").orEmpty()) }.getOrDefault("")
    )

    fun saveGemini(endpoint: String, model: String, key: String) {
        prefs.edit().putString("endpoint", endpoint.trim().trimEnd('/'))
            .putString("model", model).putString("key", seal(key)).apply()
    }

    fun saveSimple(key: String) { prefs.edit().putString("simple", seal(key)).apply() }
    fun clearGemini() { prefs.edit().remove("endpoint").remove("model").remove("key").apply() }
    fun clearSimple() { prefs.edit().remove("simple").apply() }

    fun job(): String = prefs.getString("simple_job", "").orEmpty()
    fun saveJob(jobId: String) { prefs.edit().putString("simple_job", jobId).apply() }
    fun clearJob() { prefs.edit().remove("simple_job").apply() }
}
