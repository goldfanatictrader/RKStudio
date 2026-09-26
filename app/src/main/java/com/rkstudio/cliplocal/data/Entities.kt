package com.rkstudio.cliplocal.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

// Project: satu lagu + setting slice + offset global. Semua file di internal storage.
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoTitle: String,
    val musicTitle: String,
    val audioPathInternal: String, // filesDir/projects/{id}/source.*
    val audioDurationMs: Long = 0,
    val sliceDurationSec: Int = 10, // slider 5-30, default 10
    val globalOffsetMs: Int = 0,    // -1000..+1000, diterapkan ke semua slice
    val createdAt: Long = System.currentTimeMillis()
)

// Take: satu hasil rekaman video mute per slice.
@Entity(tableName = "takes")
data class Take(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val sliceIndex: Int,
    val sliceStartMs: Long,
    val sliceEndMs: Long,
    val videoPathInternal: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ProjectDao {
    @Insert suspend fun insert(p: Project): Long
    @Update suspend fun update(p: Project)
    @Query("SELECT * FROM projects ORDER BY createdAt DESC") suspend fun all(): List<Project>
    @Query("SELECT * FROM projects WHERE id=:id") suspend fun byId(id: Long): Project?
}

@Dao
interface TakeDao {
    @Insert suspend fun insert(t: Take): Long
    @Query("SELECT * FROM takes WHERE projectId=:pid AND sliceIndex=:idx ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(pid: Long, idx: Int): Take?
    @Query("SELECT * FROM takes WHERE projectId=:pid ORDER BY sliceIndex, createdAt")
    suspend fun byProject(pid: Long): List<Take>
}

// Helper murni (tanpa Android framework) agar mudah di-test.
object SliceHelper {
    fun count(audioDurationMs: Long, sliceSec: Int): Int {
        if (audioDurationMs <= 0 || sliceSec <= 0) return 0
        return ((audioDurationMs + sliceSec * 1000L - 1) / (sliceSec * 1000L)).toInt()
    }
    fun range(index: Int, sliceSec: Int, totalMs: Long): Pair<Long, Long> {
        val s = index.toLong() * sliceSec * 1000L
        val e = minOf(s + sliceSec * 1000L, totalMs)
        return s to e
    }
    fun applyOffset(startMs: Long, offsetMs: Int): Long = (startMs + offsetMs).coerceAtLeast(0)
}
