package com.rkstudio.cliplocal.ai

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rkstudio.cliplocal.data.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StudioTurn(val role: String, val text: String)

class StudioAiViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AiConfigStore(app)
    var config by mutableStateOf(store.read()); private set
    var models by mutableStateOf(emptyList<AiModel>()); private set
    var images by mutableStateOf(emptyList<GenerationModel>()); private set
    var videos by mutableStateOf(emptyList<GenerationModel>()); private set
    var credits by mutableStateOf<String?>(null); private set
    var creditCost by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var analysis by mutableStateOf(""); private set
    var prompt by mutableStateOf(""); private set
    var generationDraft by mutableStateOf(""); private set
    fun generationPromptText(): String = Regex("(?s)```(?:[a-zA-Z0-9_-]+)?\\s*(.*?)```")
        .find(prompt)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: prompt

    fun usePromptForGeneration() { generationDraft = generationPromptText() }
    val chat = mutableStateListOf<StudioTurn>()
    var imageUrls by mutableStateOf(store.outputs("image")); private set
    var videoUrls by mutableStateOf(store.outputs("video")); private set
    var jobState by mutableStateOf(""); private set
    private var polling = false

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        error = null
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                error = when (e) {
                    is java.net.SocketTimeoutException -> "Server terlalu lama merespons. Coba lagi; periksa riwayat job sebelum membuat ulang."
                    else -> e.message?.take(220) ?: "Terjadi kesalahan. Coba lagi."
                }
            } finally { busy = false }
        }
    }

    fun configureGemini(url: String, key: String, selected: String, onDone: () -> Unit) = work {
        val available = withContext(Dispatchers.IO) { AiGateway.models(url, key) }
        require(available.isNotEmpty()) { "Endpoint tidak memiliki model." }
        require(selected in available.map { it.id }) { "Pilih model yang tersedia pada endpoint." }
        store.saveGemini(url, selected, key)
        config = store.read()
        models = available
        onDone()
    }

    fun configureGoogle(key: String, selected: String, search: Boolean, onDone: () -> Unit) = work {
        require(selected.matches(Regex("^[A-Za-z0-9._-]{1,128}$"))) { "Masukkan ID model Google yang valid." }
        withContext(Dispatchers.IO) { GoogleGeminiGateway.testKey(key, selected) }
        store.saveGoogle(selected, key, search)
        config = store.read()
        models = listOf(AiModel(selected, true, true, true))
        onDone()
    }

    fun discoverGoogle(key: String, selected: String) = work {
        withContext(Dispatchers.IO) { GoogleGeminiGateway.testKey(key, selected) }
        models = listOf(AiModel(selected, true, true, true))
    }

    fun discoverGemini(url: String, key: String) = work {
        models = withContext(Dispatchers.IO) { AiGateway.models(url, key) }
    }

    fun selectProvider(provider: String) {
        store.selectProvider(provider)
        config = store.read()
        models = if (provider == "google" && config.googleModel.isNotBlank())
            listOf(AiModel(config.googleModel, true, true, true)) else emptyList()
    }

    fun clearGemini() { store.clearGemini(); config = store.read(); models = emptyList(); analysis = ""; prompt = ""; chat.clear() }
    fun clearGoogle() { store.clearGoogle(); config = store.read(); models = emptyList(); analysis = ""; prompt = ""; chat.clear() }

    fun configureSimple(key: String, onDone: () -> Unit) = work {
        val response = withContext(Dispatchers.IO) { SimpleNgatClient.account(key) to SimpleNgatClient.models(key) }
        store.saveSimple(key)
        config = store.read()
        credits = response.first.opt("credits")?.toString()
        images = response.second.first
        videos = response.second.second
        onDone()
    }

    fun clearSimple() {
        store.clearSimple(); store.clearJob(); config = store.read()
        images = emptyList(); videos = emptyList(); credits = null
        jobState = ""; imageUrls = emptyList(); videoUrls = emptyList()
    }

    fun loadProviders() {
        if (busy) { resumeJob(); return }
        if ((config.ready && models.isEmpty()) || (config.simpleReady && images.isEmpty())) work {
            if (config.ready && models.isEmpty()) {
                if (config.provider == "google") models = GoogleGeminiGateway.models
                else runCatching { withContext(Dispatchers.IO) {
                    AiGateway.models(config.endpoint, config.apiKey)
                } }.onSuccess { models = it }.onFailure { error = "Model analisis: ${it.message}" }
            }
            if (config.simpleReady && images.isEmpty()) {
                runCatching { withContext(Dispatchers.IO) {
                    SimpleNgatClient.account(config.simpleKey) to SimpleNgatClient.models(config.simpleKey)
                } }.onSuccess {
                    credits = it.first.opt("credits")?.toString()
                    images = it.second.first
                    videos = it.second.second
                }.onFailure { error = "SimpleNGAT: ${it.message}" }
            }
        }
        resumeJob()
    }

    fun analyzeAudio(project: Project, goal: String) = work {
        require(config.ready) { "Konfigurasikan model analisis dahulu." }
        val instruction = """
            Anda adalah music-video creative director. Analisis AUDIO NYATA yang dilampirkan, bukan hanya judulnya.
            Proyek: ${project.videoTitle}. Lagu: ${project.musicTitle}. Arahan pengguna: $goal.
            Jawab dalam Bahasa Indonesia dengan: (1) observasi yang benar-benar terdengar
            (energi, tekstur, instrumen, perubahan, vokal jika ada), (2) struktur dan penanda waktu
            hanya jika dapat didengar; jangan mengarang BPM, lirik, atau timestamp presisi,
            (3) konsep video yang menanggapi musik, (4) hook visual, wardrobe, lokasi,
            shot plan 8 detik dan gerakan kamera, (5) continuity dan risiko AI, (6) hal yang harus diverifikasi.
            Bedakan fakta audio dari usulan kreatif. Jika suara tidak jelas, nyatakan ketidakpastian.
        """.trimIndent()
        val message = withContext(Dispatchers.IO) { AiGateway.audioMessage(project.audioPathInternal, instruction) }
        analysis = withContext(Dispatchers.IO) {
            AiGateway.ask(config, JSONArray().put(message))
        }
    }

    fun analyzeVideo(uri: Uri, goal: String) = work {
        require(config.ready) { "Konfigurasikan model analisis dahulu." }
        val instruction = """
            Analisis video referensi untuk produksi video musik. Arahan: $goal.
            Ada frame terpilih beserta timestamp perkiraan, dan mungkin video pendek terlampir.
            Pisahkan bukti yang terlihat dari inferensi. Jelaskan subjek, komposisi, framing,
            cahaya, warna, continuity, potensi glitch, dan ide shot 8 detik yang bisa dieksekusi.
            Jangan mengklaim suara atau gerak kontinu jika tidak dapat dipastikan dari input.
            Jawab dalam Bahasa Indonesia dengan rekomendasi konkret.
        """.trimIndent()
        val message = withContext(Dispatchers.IO) { AiGateway.videoMessage(getApplication(), uri, instruction) }
        analysis = withContext(Dispatchers.IO) { AiGateway.ask(config, JSONArray().put(message)) }
    }

    fun buildPrompt(direction: String) = work {
        require(config.ready) { "Konfigurasikan model analisis dahulu." }
        val request = """
            Buat prompt produksi video AI yang siap ditempel ke generator. Bahasa prompt Inggris,
            penjelasan pendek dalam Bahasa Indonesia. Target shot 8 detik.
            Brief: $direction
            Analisis konteks (boleh kosong): ${analysis.take(12000)}
            Format wajib Markdown yang rapi dan ringkas dengan heading ##, paragraf pendek, dan bullet sederhana; jangan gunakan tabel. Susun: Ringkasan konsep;
            Prompt generator (English); Negative prompt; Continuity & asumsi. Letakkan SELURUH teks
            siap-tempel untuk generator dalam tepat satu fenced code block di bawah heading Prompt generator.
            Prompt visual konkret mencakup subject, action, setting, framing/lens, camera motion,
            lighting, color, timing beat; audio/sfx hanya jika diinginkan; negative constraints;
            continuity anchor. Hindari klaim fitur generator yang belum diketahui. Jangan mengarang
            referensi karakter bila belum diberikan.
        """.trimIndent()
        prompt = withContext(Dispatchers.IO) {
            AiGateway.ask(config, JSONArray().put(AiGateway.text("user", request)))
        }
    }

    fun sendChat(input: String) = work {
        require(config.ready) { "Konfigurasikan model analisis dahulu." }
        val history = chat.toList().takeLast(12)
        val messages = JSONArray().put(AiGateway.text("system",
            "Anda agent composer dan creative director RKStudio. Bantu konsep video musik, storyboard 8 detik, " +
                "prompt generator, continuity dan timing berdasarkan data yang tersedia. " +
                "Jangan mengaku sudah mendengar atau melihat media bila hanya ringkasan teks. " +
                "Pisahkan observasi dan ide. Jawab ringkas, konkret, Bahasa Indonesia. " +
                "Konteks analisis: ${analysis.take(10000)}"))
        history.forEach { messages.put(AiGateway.text(it.role, it.text)) }
        messages.put(AiGateway.text("user", input))
        val output = withContext(Dispatchers.IO) { AiGateway.ask(config, messages, useGoogleSearch = true) }
        chat.add(StudioTurn("user", input))
        chat.add(StudioTurn("assistant", output))
    }

    fun generateImage(prompt: String, model: String, ratio: String, refs: List<String>) = work {
        require(config.simpleReady) { "Konfigurasikan SimpleNGAT dahulu." }
        val data = withContext(Dispatchers.IO) {
            SimpleNgatClient.image(config.simpleKey, prompt, model, ratio, refs)
        }
        val list = data.optJSONArray("images") ?: JSONArray()
        imageUrls = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank) }
        require(imageUrls.isNotEmpty()) { "Server belum memberikan gambar. Periksa kredit dan coba lagi." }
        store.saveOutputs("image", imageUrls)
        credits = data.opt("remainingCredits")?.toString() ?: credits
    }

    fun generateVideo(prompt: String, model: String, mode: String, ratio: String,
                      quality: String, duration: Int?, startImage: String, endImage: String,
                      refs: List<String>, audio: Boolean) = work {
        require(config.simpleReady) { "Konfigurasikan SimpleNGAT dahulu." }
        require(store.job().isBlank() || jobState in listOf("completed", "failed", "cancelled")) {
            "Selesaikan job video yang masih berjalan."
        }
        val data = withContext(Dispatchers.IO) {
            SimpleNgatClient.video(config.simpleKey, prompt, model, mode, ratio, quality,
                duration, startImage, endImage, refs, audio)
        }
        val id = data.optString("jobId")
        require(id.isNotBlank()) { "Server tidak memberikan job ID." }
        val record = JSONObject().put("id", id).put("resolution", if (model.startsWith("veo-")) quality else "")
        store.saveJob(record.toString())
        jobState = data.optString("status", "created")
        credits = data.opt("remainingCredits")?.toString() ?: credits
        creditCost = data.opt("creditCost")?.toString()
        viewModelScope.launch { pollVideo(record) }
    }

    fun resumeJob() {
        if (polling || !config.simpleReady) return
        val saved = store.job()
        if (saved.isBlank()) return
        runCatching { JSONObject(saved) }.getOrNull()?.let { record ->
            viewModelScope.launch { pollVideo(record) }
        }
    }

    private suspend fun pollVideo(record: JSONObject) {
        if (polling) return
        polling = true
        try {
            repeat(120) {
                val data = withContext(Dispatchers.IO) {
                    SimpleNgatClient.status(config.simpleKey, record.getString("id"),
                        record.optString("resolution"), record.optString("upscale"))
                }
                jobState = data.optString("status", "started")
                credits = data.opt("remainingCredits")?.toString() ?: credits
                data.optString("upscaleJobId").takeIf { it.isNotBlank() }?.let {
                    record.put("upscale", it); store.saveJob(record.toString())
                }
                if (jobState == "completed") {
                    val items = data.optJSONArray("videos") ?: JSONArray()
                    videoUrls = (0 until items.length()).mapNotNull { index ->
                        items.optJSONObject(index)?.let { video ->
                            video.optString("url").ifBlank { video.optString("fifeUrl") }.takeIf(String::isNotBlank)
                        }
                    }
                    store.saveOutputs("video", videoUrls)
                    store.clearJob()
                    return
                }
                if (jobState in listOf("failed", "cancelled", "error")) {
                    store.clearJob(); error = "Job video berakhir: $jobState"; return
                }
                delay(7000)
            }
            error = "Polling berhenti sementara. Buka studio lagi untuk mengecek job."
        } catch (e: Exception) {
            error = e.message?.take(160) ?: "Status video gagal diperiksa."
        } finally { polling = false }
    }
}
