package com.rkstudio.cliplocal.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL

object AiMediaSaver {
    fun save(context: Context, remote: String, video: Boolean): Uri {
        val target = URL(remote)
        require(target.protocol == "https") { "Hasil harus memakai tautan HTTPS." }
        val connection = target.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 120000
        connection.instanceFollowRedirects = true
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            require(connection.responseCode in 200..299) { "File hasil belum dapat diunduh." }
            val type = if (video) "video/mp4" else
                if (connection.contentType?.startsWith("image/png") == true) "image/png" else "image/jpeg"
            val filename = "RKStudio_AI_${System.currentTimeMillis()}." +
                if (video) "mp4" else if (type == "image/png") "png" else "jpg"
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (video) Environment.DIRECTORY_MOVIES + "/RKStudio"
                    else Environment.DIRECTORY_PICTURES + "/RKStudio")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Tidak dapat membuat file di Galeri.")
            resolver.openOutputStream(uri)?.use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Tidak dapat menyimpan hasil.")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            throw error
        } finally { connection.disconnect() }
    }
}
