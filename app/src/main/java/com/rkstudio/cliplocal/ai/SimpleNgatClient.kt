package com.rkstudio.cliplocal.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SimpleNgatClient {
    private const val ROOT = "https://simplengat.com/api/v1"
    private fun request(path: String, key: String, body: JSONObject? = null): JSONObject {
        require(key.isNotBlank()) { "Masukkan SimpleNGAT API key dahulu." }
        val connection = URL(ROOT + path).openConnection() as HttpURLConnection
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
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("SimpleNGAT HTTP $code. Periksa API key, kredit, atau parameter model.")
            val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!data.optBoolean("success", false)) {
                throw IllegalStateException(data.optString("error").take(180).ifBlank { "Request SimpleNGAT gagal." })
            }
            return data
        } finally { connection.disconnect() }
    }

    fun account(key: String): JSONObject = request("/user/me", key).getJSONObject("user")
    fun models(key: String): Pair<List<GenerationModel>, List<GenerationModel>> {
        val response = request("/models", key)
        fun read(name: String) = (response.optJSONArray(name) ?: JSONArray()).let { items ->
            (0 until items.length()).mapNotNull { i ->
                items.optJSONObject(i)?.takeIf { it.optString("model").isNotBlank() }?.let {
                    GenerationModel(it.optString("model"), it.optString("label", it.optString("model")), it)
                }
            }
        }
        return read("image") to read("video")
    }

    fun image(key: String, prompt: String, model: String, ratio: String, references: List<String>): JSONObject {
        val body = JSONObject().put("prompt", prompt).put("model", model)
            .put("aspectRatio", ratio).put("count", 1)
        if (references.isNotEmpty()) body.put("referenceImages", JSONArray(references))
        return request("/generate/image", key, body)
    }

    fun video(key: String, prompt: String, model: String, mode: String, ratio: String,
              quality: String, duration: Int?, startImage: String, endImage: String,
              references: List<String>, audio: Boolean): JSONObject {
        val body = JSONObject().put("prompt", prompt).put("model", model).put("mode", mode)
            .put("count", 1)
        if (ratio.isNotBlank()) body.put("aspectRatio", ratio)
        if (model.startsWith("veo-")) body.put("resolution", quality)
        else {
            body.put("quality", quality).put("audio", audio)
            if (duration != null) body.put("duration", duration)
        }
        if (mode == "i2v" || mode == "i2v-fl") body.put("startImage", startImage)
        if (mode == "i2v-fl") body.put("endImage", endImage)
        if (mode == "r2v") body.put("referenceImages", JSONArray(references))
        return request("/generate/video", key, body)
    }

    fun status(key: String, jobId: String, resolution: String, upscaleJobId: String): JSONObject {
        var path = "/generate/video/status?jobId=" + URLEncoder.encode(jobId, "UTF-8")
        if (resolution == "1080p") path += "&resolution=1080p"
        if (upscaleJobId.isNotBlank()) path += "&upscaleJobId=" + URLEncoder.encode(upscaleJobId, "UTF-8")
        return request(path, key)
    }
}

data class GenerationModel(val id: String, val label: String, val spec: JSONObject) {
    fun options(field: String): List<String> = spec.optJSONArray(field)?.let { a ->
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    } ?: emptyList()
    fun imageCost(): String = spec.opt("creditsPerImage")?.toString() ?: "lihat akun"
    fun videoPrice(): String = spec.optJSONObject("pricing")?.toString() ?: "Biaya dihitung oleh server"
}
