package com.rkstudio.cliplocal.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GoogleGeminiGateway {
    private const val ROOT = "https://generativelanguage.googleapis.com/v1beta"
    val defaultModel = "gemini-3.8-flash"
    // Google model IDs change; accept the ID the user has verified for their AI Studio project.
    val models get() = listOf(AiModel(defaultModel, true, true, true))

    private fun post(key: String, body: JSONObject): JSONObject {
        val connection = URL("$ROOT/interactions").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 180000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("x-goog-api-key", key.trim())
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("Google Gemini HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": ${it.take(180)}"}.orEmpty()}")
            }
            if (response.isBlank()) throw IllegalStateException("Google Gemini mengirim respons kosong.")
            return JSONObject(response)
        } finally { connection.disconnect() }
    }

    fun testKey(key: String, model: String): String {
        require(key.isNotBlank()) { "Masukkan Google AI API key." }
        return ask(key, model, JSONArray().put(JSONObject().put("role", "user")
            .put("content", "Balas tepat: RKStudio tersambung.")), false, 64)
    }

    fun ask(config: AiConfig, messages: JSONArray): String = ask(
        config.requestKey, config.requestModel, messages, config.googleSearch, 4096
    )

    private fun ask(key: String, model: String, messages: JSONArray, search: Boolean, maxTokens: Int): String {
        require(model.matches(Regex("^[A-Za-z0-9._-]{1,128}$"))) { "ID model Google tidak valid." }
        val system = mutableListOf<String>()
        val input = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role", "user")
            val content = message.opt("content")
            if (role == "system") {
                system += content?.toString().orEmpty()
                continue
            }
            if (content is String) {
                val prefix = if (role == "assistant") "Composer" else "User"
                input.put(textContent("$prefix:\n$content"))
            } else if (content is JSONArray) {
                val hasVideo = (0 until content.length()).any { j ->
                    content.optJSONObject(j)?.optString("type") == "video_url"
                }
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> input.put(textContent(part.optString("text")))
                        "input_audio" -> {
                            val audio = part.optJSONObject("input_audio") ?: continue
                            val data = audio.optString("data")
                            val mime = if (audio.optString("format") == "wav") "audio/wav" else "audio/mp3"
                            input.put(mediaContent("audio", data, mime))
                        }
                        "image_url" -> if (!hasVideo) {
                            val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                            dataContent(url, "image")?.let { input.put(it) }
                        }
                        "video_url" -> {
                            val url = part.optJSONObject("video_url")?.optString("url").orEmpty()
                            dataContent(url, "video")?.let { input.put(it) }
                        }
                    }
                }
            }
        }
        require(input.length() > 0) { "Input Gemini kosong." }
        val body = JSONObject().put("model", model).put("input", input)
            .put("store", false)
            .put("generation_config", JSONObject().put("max_output_tokens", maxTokens)
                .put("thinking_level", "medium"))
        if (system.isNotEmpty()) body.put("system_instruction", system.joinToString("\n\n"))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "google_search")))
        val response = post(key, body)
        val status = response.optString("status")
        if (status.isNotBlank() && status != "completed")
            throw IllegalStateException("Gemini interaction berakhir dengan status $status.")
        val steps = response.optJSONArray("steps") ?: JSONArray()
        val output = mutableListOf<String>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val parts = step.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                parts.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }?.let(output::add)
            }
        }
        val result = output.joinToString("\n\n").trim()
        if (result.isBlank()) throw IllegalStateException("Gemini tidak mengirim teks jawaban.")
        return result
    }

    private fun textContent(text: String) = JSONObject().put("type", "text").put("text", text)

    private fun mediaContent(type: String, data: String, mime: String) = JSONObject()
        .put("type", type).put("data", data).put("mime_type", mime)

    private fun dataContent(uri: String, expected: String): JSONObject? {
        val match = Regex("^data:([^;]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(uri)
            ?: throw IllegalArgumentException("Media resmi Google harus dikirim sebagai data Base64.")
        val mime = match.groupValues[1]
        require(mime.startsWith("$expected/")) { "Format media tidak cocok: $mime" }
        return mediaContent(expected, match.groupValues[2], mime)
    }
}
