package com.rkstudio.cliplocal.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

// Export per-slice ke Gallery (DCIM/RKStudio).
// v1: copy video take apa adanya. v2 (tahap export): mux + audio slice via Transformer.
object Exporter {
    fun exportTakeToGallery(context: Context, videoPath: String, displayName: String): String? {
        return try {
            val src = File(videoPath)
            if (!src.exists()) return null
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RKStudio")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: return null
            uri.toString()
        } catch (_: Exception) {
            null
        }
    }
}
