package com.rkstudio.cliplocal.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

@OptIn(UnstableApi::class)
object Exporter {
    private var activeTransformer: Transformer? = null

    fun exportTakeWithAudioToGallery(
        context: Context,
        videoPath: String,
        audioPath: String,
        sliceStartMs: Long,
        sliceEndMs: Long,
        audioDurationMs: Long,
        offsetMs: Int,
        displayName: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val videoFile = File(videoPath)
        val audioFile = File(audioPath)
        if (!videoFile.exists()) {
            onFailure("file video tidak ditemukan")
            return
        }
        if (!audioFile.exists() || audioDurationMs <= 0) {
            onFailure("file audio tidak ditemukan")
            return
        }

        val sourceLastMs = audioDurationMs.coerceAtLeast(1L)
        val audioStartMs =
            (sliceStartMs + offsetMs.toLong()).coerceIn(0L, sourceLastMs - 1L)
        val audioEndMs =
            (sliceEndMs + offsetMs.toLong()).coerceIn(
                audioStartMs + 1L,
                sourceLastMs
            )

        val videoItem = EditedMediaItem.Builder(
            MediaItem.fromUri(Uri.fromFile(videoFile))
        ).setRemoveAudio(true).build()

        val audioClip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(audioStartMs)
            .setEndPositionMs(audioEndMs)
            .build()
        val audioItem = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(audioFile))
                .setClippingConfiguration(audioClip)
                .build()
        ).setRemoveVideo(true).build()

        val composition = Composition.Builder(
            EditedMediaItemSequence(listOf(videoItem)),
            EditedMediaItemSequence(listOf(audioItem))
        )
            .experimentalSetForceAudioTrack(true)
            .build()

        val tmp = File(
            context.cacheDir,
            "rk_mux_${System.currentTimeMillis()}.mp4"
        )
        if (tmp.exists()) tmp.delete()

        activeTransformer?.cancel()
        val transformer = Transformer.Builder(context.applicationContext)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    activeTransformer = null
                    val tracks = inspectTracks(tmp)
                    if (!tracks.hasVideo || !tracks.hasAudio) {
                        tmp.delete()
                        onFailure(
                            "hasil export tidak valid: video=${tracks.hasVideo}, audio=${tracks.hasAudio}"
                        )
                        return
                    }

                    val uri = copyToGallery(context, tmp, displayName)
                    tmp.delete()
                    if (uri != null) onSuccess(uri)
                    else onFailure("mux selesai tetapi gagal menyimpan ke Gallery")
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    activeTransformer = null
                    tmp.delete()
                    onFailure(exportException.message ?: "gagal mux video + audio")
                }
            })
            .build()

        activeTransformer = transformer
        try {
            transformer.start(composition, tmp.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null
            tmp.delete()
            onFailure(e.message ?: "gagal memulai export")
        }
    }

    private data class TrackPresence(
        val hasVideo: Boolean,
        val hasAudio: Boolean
    )

    private fun inspectTracks(file: File): TrackPresence {
        if (!file.exists() || file.length() <= 0L) {
            return TrackPresence(hasVideo = false, hasAudio = false)
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
            TrackPresence(hasVideo = hasVideo, hasAudio = hasAudio)
        } catch (_: Exception) {
            TrackPresence(hasVideo = false, hasAudio = false)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun copyToGallery(
        context: Context,
        src: File,
        displayName: String
    ): String? {
        if (!src.exists() || src.length() <= 0L) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + "/RKStudio"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("tidak bisa menulis Gallery")

            val done = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            uri.toString()
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
