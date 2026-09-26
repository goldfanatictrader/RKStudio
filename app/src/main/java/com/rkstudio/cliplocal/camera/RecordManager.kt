package com.rkstudio.cliplocal.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

// CameraX VideoCapture TANPA audio (mute mic): prepareRecording tanpa withAudioEnabled().
// Alur: bind(view) sekali -> start(file) + SliceAudioPlayer bersamaan -> stop() -> Finalize.
class StudioRecorder(private val context: Context) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null
    var front: Boolean = false

    fun bind(view: PreviewView, lifecycle: LifecycleOwner, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(view.surfaceProvider) }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.HD, Quality.SD, Quality.LOWEST)
                        )
                    ).build()
                videoCapture = VideoCapture.withOutput(recorder)
                val vc = videoCapture!!
                p.unbindAll()
                p.bindToLifecycle(
                    lifecycle,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, vc
                )
            } catch (e: Exception) {
                onError(e.message ?: "kamera error")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun flip(view: PreviewView, lifecycle: LifecycleOwner, onError: (String) -> Unit) {
        front = !front
        bind(view, lifecycle, onError)
    }

    fun start(
        output: File,
        onStarted: () -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val vc = videoCapture ?: run { onError("kamera belum siap"); return }
        try {
            recording = vc.output
                .prepareRecording(context, FileOutputOptions.Builder(output).build())
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> onStarted()
                        is VideoRecordEvent.Finalize ->
                            if (!event.hasError()) onDone(output)
                            else onError("rekam gagal (${event.error})")
                    }
                }
        } catch (e: Exception) {
            onError(e.message ?: "rekam error")
        }
    }

    fun stop() {
        try { recording?.stop() } catch (_: Exception) {}
        recording = null
    }

    fun unbind() {
        try { provider?.unbindAll() } catch (_: Exception) {}
    }
}
