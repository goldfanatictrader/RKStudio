package com.rkstudio.cliplocal.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rkstudio.cliplocal.data.AppDatabase
import com.rkstudio.cliplocal.data.FileStore
import com.rkstudio.cliplocal.data.Project
import com.rkstudio.cliplocal.data.Take
import kotlinx.coroutines.launch

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    var projects by mutableStateOf(emptyList<Project>()); private set
    var current by mutableStateOf<Project?>(null); private set
    var takes by mutableStateOf(emptyList<Take>()); private set

    fun refreshProjects() = viewModelScope.launch {
        projects = db.projectDao().all()
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
        val dur = FileStore.importAudio(getApplication(), audioUri, pid)
        db.projectDao().update(
            db.projectDao().byId(pid)!!.copy(
                audioPathInternal = FileStore.audioFile(getApplication(), pid).absolutePath,
                audioDurationMs = dur
            )
        )
        return pid
    }

    fun setSliceDuration(p: Project, sec: Int) = viewModelScope.launch {
        val np = p.copy(sliceDurationSec = sec.coerceIn(5, 30))
        db.projectDao().update(np)
        current = np
    }

    fun setGlobalOffset(p: Project, offsetMs: Int) = viewModelScope.launch {
        val np = p.copy(globalOffsetMs = offsetMs.coerceIn(-1000, 1000))
        db.projectDao().update(np)
        current = np
    }

    suspend fun saveTake(
        pid: Long, idx: Int, startMs: Long, endMs: Long, videoPath: String
    ) {
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
