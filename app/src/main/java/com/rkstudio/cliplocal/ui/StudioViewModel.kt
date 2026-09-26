package com.rkstudio.cliplocal.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import com.rkstudio.cliplocal.data.SliceHelper
import com.rkstudio.cliplocal.export.Exporter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rkstudio.cliplocal.data.AppDatabase
import com.rkstudio.cliplocal.data.FileStore
import com.rkstudio.cliplocal.data.Project
import com.rkstudio.cliplocal.data.Take
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    var exporting by mutableStateOf(false); private set
    var exportError by mutableStateOf<String?>(null); private set
    val exportedUris = mutableStateMapOf<String, String>()

    fun exportReference(project: Project, index: Int, videoPath: String) {
        if (exporting) return
        val key = "${project.id}:$index"
        val (start, end) = SliceHelper.range(index, project.sliceDurationSec, project.audioDurationMs)
        exporting = true
        exportError = null
        exportedUris.remove(key)
        Exporter.exportTakeWithAudioToGallery(
            context = getApplication(), videoPath = videoPath, audioPath = project.audioPathInternal,
            sliceStartMs = start, sliceEndMs = end, audioDurationMs = project.audioDurationMs,
            offsetMs = project.globalOffsetMs,
            displayName = "RKStudio_P${project.id}_S${(index + 1).toString().padStart(3, '0')}_${start}-${end}.mp4",
            onSuccess = { uri -> exportedUris[key] = uri; exporting = false },
            onFailure = { message -> exportError = message; exporting = false }
        )
    }

    var projects by mutableStateOf(emptyList<Project>()); private set
    var current by mutableStateOf<Project?>(null); private set
    var takes by mutableStateOf(emptyList<Take>()); private set
    var recordedCounts by mutableStateOf<Map<Long, Int>>(emptyMap()); private set

    fun refreshProjects() = viewModelScope.launch {
        val list = db.projectDao().all()
        projects = list
        recordedCounts = list.associate { project ->
            project.id to db.takeDao().byProject(project.id)
                .map { it.sliceIndex }
                .distinct()
                .size
        }
    }

    suspend fun loadProject(pid: Long): Project? {
        val p = db.projectDao().byId(pid)
        current = p
        takes = if (p != null) db.takeDao().byProject(pid) else emptyList()
        return p
    }

    fun refreshTakes(pid: Long) = viewModelScope.launch {
        takes = db.takeDao().byProject(pid)
    }

    /** Insert project dulu (dapat id), lalu import lagu ke folder project. */
    suspend fun createProject(
        videoTitle: String, musicTitle: String, sliceSec: Int, audioUri: Uri
    ): Long {
        val pid = db.projectDao().insert(
            Project(
                videoTitle = videoTitle.ifBlank { "Tanpa judul" },
                musicTitle = musicTitle.ifBlank { "Tanpa judul" },
                audioPathInternal = "",
                sliceDurationSec = sliceSec.coerceIn(5, 30)
            )
        )
        try {
            val duration = withContext(Dispatchers.IO) {
                FileStore.importAudio(getApplication(), audioUri, pid)
            }
            require(duration > 0) { "Audio tidak valid atau durasinya tidak terbaca" }
            db.projectDao().update(
                db.projectDao().byId(pid)!!.copy(
                    audioPathInternal = FileStore.audioFile(getApplication(), pid).absolutePath,
                    audioDurationMs = duration
                )
            )
            return pid
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                db.projectDao().deleteById(pid)
                FileStore.projectDir(getApplication(), pid).deleteRecursively()
            }
            throw error
        }
    }

    fun setSliceDuration(p: Project, sec: Int) = viewModelScope.launch {
        val np = p.copy(sliceDurationSec = sec.coerceIn(5, 30))
        db.projectDao().update(np)
        current = np
    }

    suspend fun setGlobalOffset(p: Project, offsetMs: Int) {
        exportedUris.keys.filter { it.startsWith("${p.id}:") }.toList().forEach { exportedUris.remove(it) }
        val np = p.copy(globalOffsetMs = offsetMs.coerceIn(-1000, 1000))
        db.projectDao().update(np)
        current = np
    }

    suspend fun saveTake(
        pid: Long, idx: Int, startMs: Long, endMs: Long, videoPath: String
    ) {
        exportedUris.remove("$pid:$idx")
        db.takeDao().insert(
            Take(
                projectId = pid,
                sliceIndex = idx,
                sliceStartMs = startMs,
                sliceEndMs = endMs,
                videoPathInternal = videoPath
            )
        )
    }

    suspend fun latestTake(pid: Long, idx: Int): Take? =
        db.takeDao().latest(pid, idx)
}