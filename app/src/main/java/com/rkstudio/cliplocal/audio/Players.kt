package com.rkstudio.cliplocal.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

// Beep countdown 3-2-1 lokal, tanpa file eksternal.
object BeepPlayer {
    private var tone: ToneGenerator? = null
    fun tick() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        } catch (_: Exception) {}
    }
    fun go() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        } catch (_: Exception) {}
    }
    fun release() { try { tone?.release() } catch (_: Exception) {}; tone = null }
}

// Pemutar potongan lagu via Media3 ExoPlayer (clipping presisi ms).
// startMs/endMs = batas slice lagu; offsetMs = kalibrasi global project.
class SliceAudioPlayer(private val context: Context) {
    private var player: ExoPlayer? = null

    fun playSlice(
        audioPath: String,
        startMs: Long,
        endMs: Long,
        offsetMs: Int = 0,
        onEnded: (() -> Unit)? = null
    ) {
        stop()
        val s = (startMs + offsetMs).coerceAtLeast(0)
        val e = (endMs + offsetMs).coerceAtLeast(s + 200)
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(s)
            .setEndPositionMs(e)
            .build()
        val item = MediaItem.fromUri(Uri.fromFile(File(audioPath)))
            .buildUpon().setClippingConfiguration(clip).build()
        player = ExoPlayer.Builder(context).build().also { p ->
            p.setMediaItem(item)
            if (onEnded != null) {
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) onEnded()
                    }
                })
            }
            p.prepare()
            p.play()
        }
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun release() = stop()
}
