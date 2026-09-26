package com.rkstudio.cliplocal.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

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

// SlicePlayer: putar potongan lagu via Media3 ExoPlayer.
// Kontrak: seek ke (sliceStartMs + offset), main tepat sliceDuration, stop otomatis.
// Implementasi penuh di tahap berikutnya (butuh device untuk ukur latensi).
class SlicePlayer(private val context: Context) {
    fun playSlice(audioPath: String, startMs: Long, durationMs: Long, offsetMs: Int = 0) {
        // TODO: ExoPlayer setMediaItem -> seekTo(startMs + offset) -> play -> delay(duration) -> pause
    }
    fun stop() { /* TODO */ }
    fun release() { /* TODO */ }
}
