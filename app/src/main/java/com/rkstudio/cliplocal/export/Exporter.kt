package com.rkstudio.cliplocal.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

// Export per-slice: mux videoTake(mute) + audioSlice(+offset) -> DCIM/RKStudio/*.mp4
// Implementasi mux via Media3 Transformer di tahap berikutnya (tetap offline).
object Exporter {
    fun exportToGallery(context: Context, videoPath: String, displayName: String): String? {
        // TODO v1 sederhana: copy video ke MediaStore. v2: mux + audio slice.
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RKStudio")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.toString()
        } catch (_: Exception) { null }
    }
}
