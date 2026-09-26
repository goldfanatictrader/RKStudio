package com.rkstudio.cliplocal.camera

import android.content.Context

// RecordManager: CameraX VideoCapture withAudioEnabled=false (mute mic).
// Alur: startRecording(slice) + SlicePlayer.playSlice() bersamaan, auto-stop setelah durasi.
class RecordManager(private val context: Context) {
    fun startSliceRecording(outputPath: String) {
        // TODO: VideoCapture.OutputFileOptions -> startRecording (mute) + catat SystemClock.elapsedRealtime()
    }
    fun stop() { /* TODO */ }
}
