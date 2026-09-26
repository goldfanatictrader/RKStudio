package com.rkstudio.cliplocal.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.rkstudio.cliplocal.ai.*
import com.rkstudio.cliplocal.data.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
private fun AiTitle(label: String, title: String, detail: String) {
    Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AiError(value: String?) {
    if (!value.isNullOrBlank()) Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(value, modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun AiChoice(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(selected.ifBlank { "Pilih $label" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("⌄")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option, maxLines = 2) }, onClick = {
                    onSelect(option); expanded = false
                })
            }
        }
    }
}

@Composable
private fun AiCopy(text: String, label: String = "Salin") {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("RKStudio", text))
    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
private fun AiResult(title: String, content: String) {
    if (content.isBlank()) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            AiMarkdown(content, Modifier.fillMaxWidth())
            AiCopy(content)
        }
    }
}

@Composable
fun AiSettingsScreen(vm: StudioAiViewModel, onBack: () -> Unit) {
    var endpoint by remember { mutableStateOf(vm.config.endpoint) }
    var geminiKey by remember { mutableStateOf("") }
    var simpleKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(vm.config.model) }
    var clearGemini by remember { mutableStateOf(false) }
    var clearSimple by remember { mutableStateOf(false) }
    LaunchedEffect(vm.models) {
        val ids = vm.models.map { it.id }
        if (ids.isNotEmpty() && model !in ids) model = if ("ag/gemini-3.8-flash" in ids) "ag/gemini-3.8-flash" else ids.first()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = onBack) { Text("← Kembali") }
        AiTitle("PROVIDER / LOKAL", "Konfigurasi AI",
            "Key disimpan terenkripsi di perangkat. Permintaan dikirim langsung ke provider saat fitur dipakai.")
        AiError(vm.error)
        Card {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Analisis · Gemini compatible", style = MaterialTheme.typography.titleLarge)
                Text(if (vm.config.ready) "Tersambung · ${vm.config.model}" else "Belum dikonfigurasi",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Base URL, akhiri dengan /v1") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(geminiKey, { geminiKey = it }, label = { Text("API key baru") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Key tersimpan tidak ditampilkan lagi. Isi ulang untuk mengganti.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = endpoint.isNotBlank() && geminiKey.isNotBlank() && !vm.busy,
                    modifier = Modifier.fillMaxWidth(), onClick = { vm.discoverGemini(endpoint, geminiKey) }) {
                    Text("Uji koneksi & muat model")
                }
                if (vm.models.isNotEmpty()) AiChoice("Model analisis",
                    vm.models.map { it.id }, model) { model = it }
                Button(enabled = model in vm.models.map { it.id } && geminiKey.isNotBlank() && !vm.busy,
                    onClick = { vm.configureGemini(endpoint, geminiKey, model) { geminiKey = "" } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Simpan provider analisis") }
                if (vm.config.ready) TextButton(onClick = { clearGemini = true }) { Text("Hapus konfigurasi analisis") }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Generator · SimpleNGAT", style = MaterialTheme.typography.titleLarge)
                Text(if (vm.config.simpleReady) "Tersambung · kredit ${vm.credits ?: "cek saat online"}"
                    else "Belum dikonfigurasi",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Image dan video memakai kredit SimpleNGAT. Key provider ini terpisah dari model analisis.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(simpleKey, { simpleKey = it }, label = { Text("SimpleNGAT API key baru") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Button(enabled = simpleKey.isNotBlank() && !vm.busy,
                    onClick = { vm.configureSimple(simpleKey) { simpleKey = "" } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Uji akun & simpan key") }
                if (vm.config.simpleReady) TextButton(onClick = { clearSimple = true }) { Text("Hapus konfigurasi SimpleNGAT") }
            }
        }
        Text("Pengaturan tersimpan lokal; media analisis dikirim ke endpoint yang Anda pilih. Jangan masukkan key ke prompt.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (clearGemini) AlertDialog(onDismissRequest = { clearGemini = false },
        title = { Text("Hapus provider analisis?") },
        text = { Text("Key dan endpoint lokal akan dihapus.") },
        confirmButton = { TextButton(onClick = { vm.clearGemini(); clearGemini = false; endpoint = ""; model = "" }) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = { clearGemini = false }) { Text("Batal") } })
    if (clearSimple) AlertDialog(onDismissRequest = { clearSimple = false },
        title = { Text("Hapus SimpleNGAT key?") },
        text = { Text("Key dan ID job yang tersimpan di perangkat ini akan dihapus.") },
        confirmButton = { TextButton(onClick = { vm.clearSimple(); clearSimple = false }) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = { clearSimple = false }) { Text("Batal") } })
}

@Composable
fun AiStudioScreen(vm: StudioAiViewModel, project: Project?, onBack: () -> Unit, onSettings: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var goal by remember { mutableStateOf("") }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var chatInput by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { videoUri = it }
    LaunchedEffect(Unit) { vm.loadProviders() }
    Scaffold(topBar = {
        Surface {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Studio") }
                TextButton(onClick = onSettings) { Text("Atur provider") }
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AiTitle("RK / CREATIVE LAB", "Konsep → produksi",
                project?.let { "Proyek: ${it.videoTitle}" } ?: "Analisis media, susun prompt, lalu buat visual.")
            AiError(vm.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Analisis", "Composer", "Generate").forEachIndexed { index, label ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
                }
            }
            when (tab) {
                0 -> {
                    if (!vm.config.ready) {
                        Text("Hubungkan model analisis dahulu untuk membaca audio atau video.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onSettings) { Text("Konfigurasi analisis") }
                    } else {
                        Text("Model: ${vm.config.model}", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(goal, { goal = it }, label = { Text("Arah konsep / brief opsional") },
                            minLines = 2, modifier = Modifier.fillMaxWidth())
                        if (project != null) Card {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Analisis lagu proyek", style = MaterialTheme.typography.titleMedium)
                                Text("MP3/WAV maksimal 8 MB. Audio dikirim ke provider analisis.",
                                    style = MaterialTheme.typography.bodySmall)
                                Button(enabled = !vm.busy, onClick = { vm.analyzeAudio(project, goal) }) {
                                    Text("Dengarkan & buat konsep video")
                                }
                            }
                        }
                        OutlinedButton(onClick = { picker.launch(arrayOf("video/mp4")) },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(videoUri?.lastPathSegment?.takeLast(30) ?: "Pilih video referensi MP4")
                        }
                        Button(enabled = videoUri != null && !vm.busy,
                            onClick = { videoUri?.let { vm.analyzeVideo(it, goal) } },
                            modifier = Modifier.fillMaxWidth()) { Text("Analisis video referensi") }
                        Text("Video dianalisis dari lima frame berwaktu; file pendek hingga 8 MB ikut dilampirkan. Periksa gerak dan audio secara manual.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AiResult("Observasi & konsep", vm.analysis)
                        if (vm.analysis.isNotBlank()) TextButton(onClick = { tab = 1 }) {
                            Text("Lanjut susun prompt →")
                        }
                    }
                }
                1 -> {
                    if (!vm.config.ready) {
                        Button(onClick = onSettings) { Text("Konfigurasi model dahulu") }
                    } else {
                        OutlinedTextField(goal, { goal = it }, label = { Text("Arah prompt / shot 8 detik") },
                            minLines = 3, modifier = Modifier.fillMaxWidth())
                        Button(enabled = goal.isNotBlank() && !vm.busy, onClick = { vm.buildPrompt(goal) },
                            modifier = Modifier.fillMaxWidth()) { Text("Bangun prompt video") }
                        AiResult("Prompt builder", vm.prompt)
                        if (vm.prompt.isNotBlank()) Button(onClick = {
                            vm.usePromptForGeneration(); tab = 2
                        }, modifier = Modifier.fillMaxWidth()) { Text("Pakai prompt di generator →") }
                        HorizontalDivider()
                        Text("Composer agent", style = MaterialTheme.typography.titleLarge)
                        vm.chat.forEach { turn ->
                            Card(colors = CardDefaults.cardColors(containerColor =
                                if (turn.role == "user") MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(if (turn.role == "user") "Kamu" else "Composer",
                                        style = MaterialTheme.typography.labelMedium)
                                    if (turn.role == "assistant") AiMarkdown(turn.text, Modifier.fillMaxWidth())
                                    else Text(turn.text)
                                }
                            }
                        }
                        OutlinedTextField(chatInput, { chatInput = it },
                            label = { Text("Tanya soal konsep, shot, atau prompt") },
                            minLines = 2, modifier = Modifier.fillMaxWidth())
                        Button(enabled = chatInput.isNotBlank() && !vm.busy, onClick = {
                            vm.sendChat(chatInput.trim()); chatInput = ""
                        }, modifier = Modifier.fillMaxWidth()) { Text("Kirim ke composer") }
                    }
                }
                else -> GenerationPane(vm, onSettings)
            }
            if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun RemoteImage(url: String) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                require(URL(url).protocol == "https")
                val conn = URL(url).openConnection().apply { connectTimeout = 12000; readTimeout = 20000 }
                conn.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(it.asImageBitmap(), contentDescription = "Hasil generasi gambar",
            modifier = Modifier.fillMaxWidth().height(260.dp))
    }
}

@Composable
private fun ResultLink(label: String, url: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember(url) { mutableStateOf(false) }
    var result by remember(url) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (label.startsWith("Gambar")) RemoteImage(url)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiCopy(url, "Salin tautan")
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text("Buka") }
            }
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    result = try {
                        withContext(Dispatchers.IO) {
                            AiMediaSaver.save(ctx, url, video = label.startsWith("Video"))
                        }
                        "Tersimpan di Galeri / RKStudio"
                    } catch (e: Exception) { e.message ?: "Gagal menyimpan file." }
                    saving = false
                }
            }) { Text(if (saving) "Mengunduh…" else "Simpan ke Galeri") }
            if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GenerationPane(vm: StudioAiViewModel, onSettings: () -> Unit) {
    if (!vm.config.simpleReady) {
        Text("Masukkan SimpleNGAT API key sebelum memakai generator.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSettings) { Text("Konfigurasi SimpleNGAT") }
        return
    }
    var kind by remember { mutableIntStateOf(0) }
    var description by remember(vm.generationDraft) { mutableStateOf(vm.generationDraft) }
    var selectedImage by remember { mutableStateOf("") }
    var selectedVideo by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("t2v") }
    var ratio by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var startUrl by remember { mutableStateOf("") }
    var endUrl by remember { mutableStateOf("") }
    var referenceUrls by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val model = if (kind == 0) vm.images.firstOrNull { it.id == selectedImage } ?: vm.images.firstOrNull()
        else vm.videos.firstOrNull { it.id == selectedVideo } ?: vm.videos.firstOrNull()
    val modes = model?.options("modes").orEmpty()
    val actualMode = if (mode in modes) mode else modes.firstOrNull() ?: "t2v"
    val ratios = model?.options("aspectRatios").orEmpty()
    val actualRatio = if (ratio in ratios) ratio else ratios.firstOrNull { it in listOf("portrait", "9:16") }
        ?: ratios.firstOrNull().orEmpty()
    val qualities = model?.options("qualities").orEmpty().ifEmpty { listOf("720p", "1080p") }
    val actualQuality = if (quality in qualities) quality else qualities.first()
    val durations = model?.options("durations").orEmpty().filter { it.toIntOrNull() != null }
    val actualDuration = if (duration in durations) duration else durations.firstOrNull().orEmpty()
    val refs = referenceUrls.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
    val validUrls = (refs + listOf(startUrl,endUrl).filter { it.isNotBlank() }).all {
        runCatching { URL(it).protocol == "https" }.getOrDefault(false)
    }
    val required = description.isNotBlank() && model != null && validUrls &&
        (kind == 0 || when (actualMode) {
            "i2v" -> startUrl.isNotBlank()
            "i2v-fl" -> startUrl.isNotBlank() && endUrl.isNotBlank()
            "r2v" -> refs.isNotEmpty()
            else -> true
        })
    Text("Kredit tersedia: ${vm.credits ?: "—"}", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = kind == 0, onClick = { kind = 0 }, label = { Text("Gambar") })
        FilterChip(selected = kind == 1, onClick = { kind = 1 }, label = { Text("Video") })
    }
    if (model == null) {
        Text("Daftar model belum termuat. Periksa koneksi atau konfigurasi.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.loadProviders() }) { Text("Muat ulang model") }
        return
    }
    AiChoice("Model ${if (kind == 0) "gambar" else "video"}",
        (if (kind == 0) vm.images else vm.videos).map { it.id }, model.id) {
        if (kind == 0) selectedImage = it else selectedVideo = it
        ratio = ""; quality = ""; duration = ""; mode = "t2v"
    }
    Text(model.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(description, { description = it }, label = { Text("Prompt visual") },
        minLines = 4, modifier = Modifier.fillMaxWidth())
    if (kind == 0) {
        if (ratios.isNotEmpty()) AiChoice("Rasio", ratios, actualRatio) { ratio = it }
        OutlinedTextField(referenceUrls, { referenceUrls = it },
            label = { Text("URL referensi gambar (opsional, pisah koma)") },
            minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("Referensi harus URL HTTPS yang dapat diakses SimpleNGAT.",
            style = MaterialTheme.typography.bodySmall)
        Text("Estimasi: ${model.imageCost()} kredit / gambar", style = MaterialTheme.typography.labelLarge)
    } else {
        if (modes.isNotEmpty()) AiChoice("Mode", modes, actualMode) { mode = it }
        if (actualMode in listOf("i2v", "i2v-fl")) {
            OutlinedTextField(startUrl, { startUrl = it },
                label = { Text("URL HTTPS gambar awal") }, modifier = Modifier.fillMaxWidth())
            vm.imageUrls.firstOrNull()?.let { generated ->
                TextButton(onClick = { startUrl = generated }) { Text("Pakai gambar hasil terakhir") }
            }
        }
        if (actualMode == "i2v-fl") OutlinedTextField(endUrl, { endUrl = it },
            label = { Text("URL HTTPS gambar akhir") }, modifier = Modifier.fillMaxWidth())
        if (actualMode == "r2v") OutlinedTextField(referenceUrls, { referenceUrls = it },
            label = { Text("URL referensi (pisah koma)") }, minLines = 2,
            modifier = Modifier.fillMaxWidth())
        if (ratios.isNotEmpty() && actualMode in listOf("t2v", "r2v"))
            AiChoice("Rasio", ratios, actualRatio) { ratio = it }
        AiChoice("Kualitas", qualities, actualQuality) { quality = it }
        if (!model.id.startsWith("veo-") && durations.isNotEmpty())
            AiChoice("Durasi detik", durations, actualDuration) { duration = it }
        if (!model.id.startsWith("veo-") && model.spec.optString("audio") == "toggle")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = audio, onCheckedChange = { audio = it })
                Spacer(Modifier.width(12.dp))
                Text("Audio dari generator")
            }
        Text("Harga: ${model.videoPrice().take(160)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(enabled = required && !vm.busy, onClick = { confirm = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(if (kind == 0) "Generate gambar" else "Kirim job video")
    }
    if (vm.jobState.isNotBlank()) {
        Text("Job video: ${vm.jobState}", style = MaterialTheme.typography.titleMedium)
        vm.creditCost?.let { Text("Biaya job: $it kredit", style = MaterialTheme.typography.bodySmall) }
        Text("ID job disimpan lokal dan status dicek kembali saat studio dibuka.",
            style = MaterialTheme.typography.bodySmall)
    }
    vm.imageUrls.forEachIndexed { i, url -> ResultLink("Gambar ${i+1}", url) }
    vm.videoUrls.forEachIndexed { i, url -> ResultLink("Video ${i+1}", url) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (kind == 0) "Buat gambar?" else "Kirim video ke generator?") },
        text = { Text("Model: ${model.id}\n" +
            (if (kind == 0) "Estimasi ${model.imageCost()} kredit untuk 1 gambar."
            else "Kualitas $actualQuality. Biaya aktual bergantung model/durasi dan dikembalikan server. " +
                "Kredit dipotong saat video berhasil.")) },
        confirmButton = { Button(onClick = {
            confirm = false
            if (kind == 0) vm.generateImage(description, model.id, actualRatio, refs)
            else vm.generateVideo(description, model.id, actualMode, actualRatio,
                actualQuality, actualDuration.toIntOrNull(), startUrl, endUrl, refs, audio)
        }) { Text("Lanjutkan") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } })
}
