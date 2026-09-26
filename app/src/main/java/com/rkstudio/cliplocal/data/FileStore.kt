package com.rkstudio.cliplocal.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/** Penyimpanan file lokal: filesDir/projects/{pid}/... (tanpa REST API, tanpa cloud). */
object FileStore {
    fun projectDir(ctx: Context, pid: Long): File =
        File(ctx.filesDir, "projects/$pid").apply { mkdirs() }

    fun takesDir(ctx: Context, pid: Long): File =
        File(projectDir(ctx, pid), "takes").apply { mkdirs() }

    fun audioFile(ctx: Context, pid: Long): File =
        File(projectDir(ctx, pid), "source_audio")

    fun newTakeFile(ctx: Context, pid: Long, idx: Int): File =
        File(takesDir(ctx, pid), "slice_${idx}_${System.currentTimeMillis()}.mp4")

    /** Copy lagu dari picker ke internal + baca durasi. Return durasi ms. */
    fun importAudio(ctx: Context, uri: Uri, pid: Long): Long {
        val dst = audioFile(ctx, pid)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalArgumentException("tidak bisa membaca lagu")
        return audioDurationMs(dst.absolutePath)
    }

    fun audioDurationMs(path: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
