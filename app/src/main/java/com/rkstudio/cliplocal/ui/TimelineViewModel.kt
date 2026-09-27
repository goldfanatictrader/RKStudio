package com.rkstudio.cliplocal.ui

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rkstudio.cliplocal.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ShotVersion(
    val id: String = UUID.randomUUID().toString(), val kind: String = "script",
    val text: String = "", val uri: String = "", val sourceId: String = "",
    val model: String = "", val created: Long = System.currentTimeMillis()
)
data class TimelineShot(
    val id: String = UUID.randomUUID().toString(), val durationMs: Long = 8000,
    val versions: List<ShotVersion> = listOf(ShotVersion(text = "")),
    val activeId: String = versions.first().id
) {
    val active get() = versions.firstOrNull { it.id == activeId } ?: versions.first()
}
data class TimelineProject(
    val id: String = UUID.randomUUID().toString(), val title: String,
    val ratio: String = "9:16", val shots: List<TimelineShot> = emptyList()
) {
    val durationMs get() = shots.sumOf { it.durationMs }
    fun indexAt(ms: Long): Int {
        var end = 0L
        shots.forEachIndexed { i, s -> end += s.durationMs; if (ms < end) return i }
        return shots.lastIndex.coerceAtLeast(0)
    }
    fun startAt(index: Int) = shots.take(index).sumOf { it.durationMs }
}

class TimelineViewModel(app: Application) : AndroidViewModel(app) {
    private val file = AtomicFile(File(app.filesDir, "timeline-projects-v1.json"))
    var projects by mutableStateOf<List<TimelineProject>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var job by mutableStateOf<JSONObject?>(null); private set
    var working by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    private var polling = false
    init {
        try {
            if (file.baseFile.exists()) {
                val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                projects = root.getJSONArray("projects").objects().map { p ->
                    TimelineProject(p.getString("id"), p.getString("title"), p.optString("ratio", "9:16"),
                        p.getJSONArray("shots").objects().map { s ->
                            TimelineShot(s.getString("id"), s.getLong("duration"), s.getJSONArray("versions").objects().map { v ->
                                ShotVersion(v.getString("id"), v.getString("kind"), v.optString("text"),
                                    v.optString("uri"), v.optString("source"), v.optString("model"), v.optLong("created"))
                            }, s.getString("active"))
                        })
                }
                job = root.optJSONObject("job")
            }
        } catch (e: Exception) { error = "Data timeline tidak dapat dibaca. File asli dipertahankan."; working = true }
    }
    private fun save() {
        val root = JSONObject().put("projects", JSONArray(projects.map { p ->
            JSONObject().put("id",p.id).put("title",p.title).put("ratio",p.ratio)
                .put("shots",JSONArray(p.shots.map { s ->
                    JSONObject().put("id",s.id).put("duration",s.durationMs).put("active",s.activeId)
                        .put("versions",JSONArray(s.versions.map { v ->
                            JSONObject().put("id",v.id).put("kind",v.kind).put("text",v.text)
                                .put("uri",v.uri).put("source",v.sourceId).put("model",v.model).put("created",v.created)
                        }))
                }))
        })).put("job",job ?: JSONObject.NULL)
        var out: java.io.FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(root.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            out?.let { file.failWrite(it) }
            error = "Perubahan belum tersimpan: ${e.message}"
        }
    }
    fun create(title: String, ratio: String, script: String): String {
        val shots = script.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.map {
            TimelineShot(versions = listOf(ShotVersion(text = it.trim())))
        }.ifEmpty { listOf(TimelineShot()) }
        val p = TimelineProject(title = title.trim(), ratio = ratio, shots = shots)
        projects = projects + p; save(); return p.id
    }
    fun change(pid: String, sid: String, edit: (TimelineShot) -> TimelineShot) {
        projects = projects.map { p -> if (p.id == pid) p.copy(shots = p.shots.map { if (it.id == sid) edit(it) else it }) else p }
        save()
    }
    fun script(pid: String, sid: String, text: String, seconds: Int) {
        change(pid,sid) { s ->
            val v = ShotVersion(text = text, sourceId = s.activeId)
            s.copy(durationMs = seconds.coerceIn(1,300) * 1000L, versions = s.versions + v, activeId = v.id)
        }
    }
    fun activate(pid: String, sid: String, vid: String) = change(pid,sid) { s ->
        if (s.versions.any { it.id == vid }) s.copy(activeId = vid) else s
    }
    fun add(pid: String) {
        projects = projects.map { if (it.id == pid) it.copy(shots = it.shots + TimelineShot()) else it }; save()
    }
    fun move(pid: String, sid: String, direction: Int) {
        projects = projects.map { p ->
            if (p.id != pid) p else {
                val list = p.shots.toMutableList()
                val index = list.indexOfFirst { it.id == sid }
                val target = index + direction
                if (index >= 0 && target in list.indices) { val s = list.removeAt(index); list.add(target,s) }
                p.copy(shots = list)
            }
        }; save()
    }
    fun importMedia(pid: String, sid: String, uri: Uri, kind: String) {
        viewModelScope.launch {
            working = true; error = null
            try {
                val target = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().filesDir,"timeline-media").apply { mkdirs() }
                    val dest = File(dir,UUID.randomUUID().toString() + if (kind == "video") ".mp4" else ".image")
                    try {
                        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                            dest.outputStream().use { output ->
                                val buffer = ByteArray(65536); var total = 0L
                                while (true) { val n = input.read(buffer); if (n < 0) break
                                    total += n; require(total <= 500L * 1024 * 1024) { "Maksimum media 500 MB." }
                                    output.write(buffer,0,n)
                                }
                            }
                        }; dest
                    } catch (e: Exception) { dest.delete(); throw e }
                }
                change(pid,sid) { s ->
                    val v = ShotVersion(kind = kind,text = s.active.text,uri = Uri.fromFile(target).toString(),sourceId = s.activeId)
                    s.copy(versions = s.versions + v)
                }
                status = "Media masuk ke Pilih versi. Durasi shot tetap."
            } catch (e: Exception) { error = e.message ?: "Impor gagal." }
            finally { working = false }
        }
    }
    fun generate(pid: String, sid: String, kind: String, prompt: String, model: GenerationModel,
                 ratio: String, quality: String, seconds: Int?) {
        if (working || job != null) return
        val shot = projects.first { it.id == pid }.shots.first { it.id == sid }
        val source = if (kind == "video" && shot.active.kind == "video")
            shot.versions.firstOrNull { it.id == shot.active.sourceId && it.kind == "image" } ?: shot.active
            else shot.active
        val key = AiConfigStore(getApplication()).read().simpleKey
        if (key.isBlank()) { error = "Konfigurasi SimpleNGAT terlebih dahulu."; return }
        // Persist the submission intent before networking: never silently resubmit a paid request.
        job = JSONObject().put("pid",pid).put("sid",sid).put("kind",kind).put("prompt",prompt)
            .put("source",source.id).put("model",model.id).put("resolution",quality).put("id","")
        save(); working = true; error = null; status = "Mengirim permintaan…"
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    if (kind == "image") SimpleNgatClient.image(key,prompt,model.id,ratio,
                        if (source.kind == "image" && source.uri.startsWith("https://")) listOf(source.uri) else emptyList())
                    else {
                        require(source.kind == "image" && source.uri.startsWith("https://")) {
                            "Image-to-video memerlukan gambar HTTPS. Gunakan gambar hasil generate."
                        }
                        require("i2v" in model.options("modes")) { "Model ini tidak mendukung image-to-video." }
                        SimpleNgatClient.video(key,prompt,model.id,"i2v",ratio,quality,seconds,source.uri,"",emptyList(),false)
                    }
                }
                if (kind == "image") {
                    val urls = (data.optJSONArray("images") ?: JSONArray()).objects().map { it.optString("url") }.filter { it.startsWith("https://") }
                    require(urls.isNotEmpty()) { "Respons tidak berisi gambar. Periksa riwayat provider." }
                    complete(urls)
                } else {
                    val id = data.optString("jobId")
                    require(id.isNotBlank()) { "Job ID tidak diterima. Periksa riwayat provider." }
                    job = JSONObject(job.toString()).put("id",id); save()
                }
            } catch (e: Exception) {
                error = "${e.message} Status permintaan perlu diperiksa sebelum mengulang."
                status = "Periksa permintaan"
            } finally { working = false }
            resume()
        }
    }
    private fun complete(urls: List<String>) {
        val j = job ?: return
        change(j.getString("pid"),j.getString("sid")) { s ->
            s.copy(versions = s.versions + urls.map { ShotVersion(kind = j.getString("kind"),
                text = j.getString("prompt"),uri = it,sourceId = j.getString("source"),model = j.getString("model")) })
        }
        job = null; save(); status = "Versi baru siap. Pilih versi untuk menggunakannya."
    }
    fun clearUncertainJob() { if (!working && !polling) { job = null; save(); status = "" } }
    fun resume() {
        val record = job ?: return
        if (record.optString("id").isBlank() || polling) return
        val key = AiConfigStore(getApplication()).read().simpleKey
        if (key.isBlank()) return
        polling = true
        viewModelScope.launch {
            try {
                repeat(120) {
                    val j = job ?: return@launch
                    val data = withContext(Dispatchers.IO) { SimpleNgatClient.status(key,j.getString("id"),
                        j.optString("resolution"),j.optString("upscale")) }
                    status = data.optString("status","processing")
                    data.optString("upscaleJobId").takeIf { it.isNotBlank() }?.let {
                        job = JSONObject(j.toString()).put("upscale",it); save()
                    }
                    if (status == "completed") {
                        val urls = (data.optJSONArray("videos") ?: JSONArray()).objects()
                            .map { it.optString("url").ifBlank { it.optString("fifeUrl") } }.filter { it.startsWith("https://") }
                        require(urls.isNotEmpty()) { "Video belum tersedia dari server." }
                        complete(urls); return@launch
                    }
                    if (status in listOf("failed","cancelled","error")) {
                        error = "Generate berakhir: $status"; job = null; save(); return@launch
                    }
                    delay(7000)
                }
                status = "Pemeriksaan dijeda. Ketuk Cek status."
            } catch (e: Exception) { error = e.message ?: "Gagal mengecek job; job tetap tersimpan." }
            finally { polling = false }
        }
    }
}
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
