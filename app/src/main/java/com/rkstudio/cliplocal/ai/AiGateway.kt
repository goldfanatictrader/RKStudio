package com.rkstudio.cliplocal.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AiGateway {
    private fun endpoint(base: String): String {
        val url = URL(base.trim().trimEnd('/'))
        require(url.protocol == "https" && url.userInfo == null && url.query == null &&
            url.ref == null && url.host.isNotBlank()) { "Gunakan URL HTTPS yang valid." }
        return url.toString().trimEnd('/')
    }

    private fun request(url: String, key: String, body: JSONObject? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = if (body == null) 30000 else 150000
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Server mengembalikan HTTP $status. Periksa key, model, dan kuota.")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) throw IllegalStateException("Server mengirim respons kosong. Coba lagi.")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    fun models(base: String, key: String): List<AiModel> {
        val data = request(endpoint(base) + "/models", key).optJSONArray("data")
            ?: throw IllegalStateException("Daftar model tidak tersedia.")
        return (0 until data.length()).mapNotNull { i ->
            val obj = data.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val cap = obj.optJSONObject("capabilities")
            AiModel(id, cap?.optBoolean("audioInput") == true,
                cap?.optBoolean("videoInput") == true, cap?.optBoolean("vision") == true)
        }
    }

    fun ask(config: AiConfig, messages: JSONArray, useGoogleSearch: Boolean = false): String {
        require(config.ready) { "Konfigurasikan provider analisis dahulu." }
        if (config.provider == "google") return GoogleGeminiGateway.ask(config, messages, useGoogleSearch)
        val body = JSONObject().put("model", config.model).put("messages", messages)
            .put("stream", false).put("max_tokens", 2600)
        val data = request(endpoint(config.endpoint) + "/chat/completions", config.apiKey, body)
        val content = data.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).joinToString("\n") { i ->
                content.optJSONObject(i)?.optString("text").orEmpty()
            }
            else -> ""
        }.trim()
        if (text.isBlank()) throw IllegalStateException("Model tidak mengirim jawaban. Coba lagi.")
        return text
    }

    fun text(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    fun audioMessage(path: String, prompt: String): JSONObject {
        val file = java.io.File(path)
        require(file.exists()) { "File lagu tidak tersedia." }
        require(file.length() <= 8L * 1024 * 1024) { "Audio melebihi 8 MB. Gunakan potongan lebih pendek." }
        val bytes = file.readBytes()
        val format = when {
            bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) -> "wav"
            bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) -> "mp3"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() &&
                (bytes[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> throw IllegalArgumentException("Analisis audio mendukung MP3/WAV. Pilih file dalam format itu.")
        }
        return JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "input_audio").put("input_audio",
                JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("format", format))))
    }

    fun videoMessage(ctx: Context, uri: Uri, prompt: String): JSONObject {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(ctx, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1) ?: 1L
            val times = listOf(0.12, 0.32, 0.52, 0.72, 0.92)
            var count = 0
            for (part in times) {
                val timeMs = (duration * part).toLong()
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 512
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 512
                val factor = 512.0 / maxOf(width, height, 1)
                val bitmap = retriever.getScaledFrameAtTime(timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * factor).toInt().coerceAtLeast(1),
                    (height * factor).toInt().coerceAtLeast(1)) ?: continue
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 72, stream)
                bitmap.recycle()
                parts.put(JSONObject().put("type", "text")
                    .put("text", "Frame video sekitar ${timeMs / 1000.0} detik"))
                parts.put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:image/jpeg;base64," +
                        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))))
                count++
            }
            require(count > 0) { "Frame video tidak dapat dibaca." }
            // Proxy accepts video_url. The sampled frames also ground visually unreliable video replies.
            val descriptor = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
            val size = descriptor?.length ?: -1L
            descriptor?.close()
            if (size in 1..(8L * 1024 * 1024)) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.size <= 8 * 1024 * 1024) {
                    parts.put(JSONObject().put("type", "video_url").put("video_url",
                        JSONObject().put("url", "data:video/mp4;base64," +
                            Base64.encodeToString(bytes, Base64.NO_WRAP))))
                }
            }
            return JSONObject().put("role", "user").put("content", parts)
        } finally { retriever.release() }
    }
}

data class AiModel(val id: String, val audio: Boolean, val video: Boolean, val vision: Boolean)
