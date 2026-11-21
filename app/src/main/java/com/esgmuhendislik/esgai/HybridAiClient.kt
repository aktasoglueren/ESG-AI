package com.esgmuhendislik.esgai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Online AI client supporting Google's native Gemini REST API and OpenAI-compatible APIs.
 * API keys are encrypted with Android Keystore AES-GCM and are never stored in source code.
 */
object HybridAiClient {
    data class Config(val endpoint: String, val model: String, val apiKey: String)

    private const val PREFS = "esg_hybrid_ai"
    private const val KEY_ALIAS = "esg_hybrid_ai_key_v1"
    private const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    fun saveConfig(context: Context, endpoint: String, model: String, apiKey: String) {
        require(endpoint.startsWith("https://")) { "Güvenlik için HTTPS adresi gerekli." }
        require(model.isNotBlank()) { "Model adı boş olamaz." }
        require(apiKey.isNotBlank()) { "API anahtarı boş olamaz." }
        val encrypted = encrypt(apiKey)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", endpoint.trim().trimEnd('/'))
            .putString("model", model.trim())
            .putString("key", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun loadConfig(context: Context): Config? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        val model = prefs.getString("model", "") ?: ""
        val stored = prefs.getString("key", null) ?: return null
        if (model.isBlank()) return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            Config(endpoint, model, decrypt(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun clearConfig(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun ask(
        config: Config,
        prompt: String,
        imageBytes: ByteArray? = null,
        mimeType: String = "image/jpeg"
    ): String = withContext(Dispatchers.IO) {
        require(config.endpoint.startsWith("https://")) { "Yalnızca HTTPS bağlantılarına izin verilir." }
        require(prompt.isNotBlank()) { "Mesaj boş olamaz." }

        val isGeminiNative = config.endpoint.contains("generativelanguage.googleapis.com") &&
            !config.endpoint.contains("/openai/")
        val requestUrl: String
        val body: JSONObject
        val connection: HttpURLConnection

        if (isGeminiNative) {
            requestUrl = "${config.endpoint.trimEnd('/')}/${config.model}:generateContent"
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (imageBytes != null) {
                parts.put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", mimeType)
                    .put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))))
            }
            body = JSONObject().put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", parts)
            ))
            connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("x-goog-api-key", config.apiKey)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        } else {
            requestUrl = config.endpoint
            val content: Any = if (imageBytes == null) {
                prompt
            } else {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:$mimeType;base64," +
                            Base64.encodeToString(imageBytes, Base64.NO_WRAP))))
            }
            body = JSONObject()
                .put("model", config.model)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
                ))
                .put("temperature", 0.7)
            connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    val error = JSONObject(response).optJSONObject("error")
                    error?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: error?.toString()
                }.getOrNull()
                throw IllegalStateException("Çevrimiçi servis hatası ($status): ${detail ?: response.take(500)}")
            }

            val root = JSONObject(response)
            if (isGeminiNative) {
                val candidates = root.optJSONArray("candidates")
                    ?: throw IllegalStateException("Gemini yanıtında candidates alanı bulunamadı: ${response.take(300)}")
                val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini metin yanıtı döndürmedi. Yanıt: ${response.take(300)}")
                val answer = buildString {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        val text = part?.optString("text").orEmpty()
                        if (text.isNotBlank()) append(text)
                    }
                }
                answer.ifBlank { "Gemini boş yanıt döndürdü." }
            } else {
                root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return cipher.iv + encrypted
    }

    private fun decrypt(value: ByteArray): String {
        require(value.size > 12) { "Şifreli anahtar geçersiz." }
        val iv = value.copyOfRange(0, 12)
        val encrypted = value.copyOfRange(12, value.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
