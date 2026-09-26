package com.rkstudio.cliplocal.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rkstudio.cliplocal.audio.BeepPlayer
import com.rkstudio.cliplocal.audio.SliceAudioPlayer
import com.rkstudio.cliplocal.camera.StudioRecorder
import com.rkstudio.cliplocal.data.FileStore
import com.rkstudio.cliplocal.data.SliceHelper
import com.rkstudio.cliplocal.export.Exporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rkstudio.cliplocal.ai.StudioAiViewModel

@Composable
fun AppNav(vm: StudioViewModel = viewModel(), ai: StudioAiViewModel = viewModel()) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "projects") {
        composable("ai") {
            AiStudioScreen(ai, null, { nav.popBackStack() }, { nav.navigate("ai-settings") })
        }
        composable("ai/{pid}", listOf(navArgument("pid") { type = NavType.LongType })) { back ->
            val pid = back.arguments!!.getLong("pid")
            LaunchedEffect(pid) { vm.loadProject(pid) }
            AiStudioScreen(ai, vm.current?.takeIf { it.id == pid },
                { nav.popBackStack() }, { nav.navigate("ai-settings") })
        }
        composable("ai-settings") {
            AiSettingsScreen(ai) { nav.popBackStack() }
        }
        composable("projects") {
            ProjectListScreen(vm, { nav.navigate("create") }, { nav.navigate("slices/$it") }, { nav.navigate("ai") })
        }
        composable("create") {
            CreateProjectScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onDone = { pid -> nav.navigate("slices/$pid") { popUpTo("projects") } }
            )
        }
        composable(
            "slices/{pid}", listOf(navArgument("pid") { type = NavType.LongType })
        ) { back ->
            val pid = back.arguments!!.getLong("pid")
            SlicesScreen(
                vm = vm,
                pid = pid,
                onBack = { nav.popBackStack() },
                onRecord = { idx -> nav.navigate("record/$pid/$idx") },
                onPreview = { idx -> nav.navigate("preview/$pid/$idx") },
                onAi = { nav.navigate("ai/$pid") }
            )
        }
        composable(
            "record/{pid}/{idx}",
            listOf(navArgument("pid") { type = NavType.LongType }, navArgument("idx") { type = NavType.IntType })
        ) { back ->
            val p = back.arguments!!.getLong("pid")
            val i = back.arguments!!.getInt("idx")
            RecordScreen(
                vm = vm,
                pid = p,
                idx = i,
                onBack = { nav.popBackStack() },
                onRecorded = { projectId, sliceIndex ->
                    nav.navigate("preview/$projectId/$sliceIndex") {
                        popUpTo("record/$projectId/$sliceIndex") { inclusive = true }
                    }
                }
            )
        }
        composable(
            "preview/{pid}/{idx}",
            listOf(navArgument("pid") { type = NavType.LongType }, navArgument("idx") { type = NavType.IntType })
        ) { back ->
            val p = back.arguments!!.getLong("pid")
            val i = back.arguments!!.getInt("idx")
            PreviewScreen(
                vm = vm,
                pid = p,
                idx = i,
                onBack = { nav.popBackStack() },
                onRetake = { nav.navigate("record/$p/$i") { popUpTo("preview/$p/$i") { inclusive = true } } }
            )
        }
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack != null) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) { Text("← Kembali") }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusBadge(text: String, active: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StudioNotice(title: String, body: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.semantics { heading() })
}

@Composable
fun ProjectListScreen(vm: StudioViewModel, onCreate: () -> Unit, onOpen: (Long) -> Unit, onAi: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshProjects() }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = vm.projects.filter { it.videoTitle.contains(query, true) || it.musicTitle.contains(query, true) }
    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 12.dp) {
                Button(onClick = onCreate,
                    modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 56.dp)) {
                    Text("+  Proyek baru")
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("RK / STUDIO")
                    StatusBadge("LOKAL", false)
                }
                Spacer(Modifier.height(24.dp))
                ScreenHeader("Studio kamu", "Dari lagu, jadi referensi gerak.")
            }
            item {
                OutlinedCard(onClick = onAi, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("CREATIVE LAB")
                            Text("Analisis lagu · Prompt · Generator", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Buka →", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (vm.projects.isNotEmpty()) {
                item {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Cari proyek atau lagu") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                }
                item { SectionLabel("PROYEK  /  ${vm.projects.size}") }
            }
            if (vm.projects.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            SectionLabel("MULAI DENGAN SATU LAGU")
                            Text("Gerakanmu.\nMusikmu.\nReferensi AI-mu.", style = MaterialTheme.typography.headlineLarge)
                            Text("Potong audio, rekam gerakan, lalu ekspor video dengan musik master.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            Text("01  Pilih audio\n02  Rekam per potongan\n03  Tinjau & ekspor MP4",
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                item { Text("Proyek disimpan di perangkat ini.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (visible.isEmpty()) {
                item { StudioNotice("Proyek tidak ditemukan", "Coba nama proyek atau judul lagu lain.") }
            }
            items(visible, key = { it.id }) { project ->
                val total = SliceHelper.count(project.audioDurationMs, project.sliceDurationSec)
                val done = (vm.recordedCounts[project.id] ?: 0).coerceAtMost(total)
                Card(onClick = { onOpen(project.id) }, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatusBadge(if (done == total && total > 0) "REKAMAN LENGKAP" else if (done > 0) "DALAM PROSES" else "BELUM DIREKAM", done > 0)
                            Text("${project.sliceDurationSec}s", fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(project.videoTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(project.musicTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = if (total > 0) done.toFloat() / total else 0f,
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("$done / $total direkam · ${FileStore.fmt(project.audioDurationMs)}", style = MaterialTheme.typography.bodySmall)
                            Text("Buka →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateProjectScreen(vm: StudioViewModel, onBack: () -> Unit, onDone: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by rememberSaveable { mutableStateOf("") }
    var music by rememberSaveable { mutableStateOf("") }
    var sliceSec by rememberSaveable { mutableFloatStateOf(8f) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var audioName by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            picked = it.toString()
            audioName = runCatching {
                ctx.contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else "Audio dipilih"
                }
            }.getOrNull() ?: "Audio dipilih"
            if (music.isBlank()) music = audioName.substringBeforeLast(".")
            error = null
        }
    }
    BackHandler(saving) {}
    Scaffold(bottomBar = {
        Surface(shadowElevation = 12.dp) {
            Column(Modifier.imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = picked != null && video.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            try { onDone(vm.createProject(video.trim(), music.trim(), sliceSec.toInt(), Uri.parse(picked!!))) }
                            catch (e: Exception) { error = "Audio tidak dapat diimpor. Pilih file audio yang valid dan coba lagi."; saving = false }
                        }
                    }) {
                    if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                    Text(if (saving) "Mengimpor audio…" else "Buat potongan audio")
                }
                if (picked == null || video.isBlank()) Text("Isi nama proyek dan pilih audio untuk lanjut.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScreenHeader("Proyek baru", "Siapkan bahan. Setelah itu, fokus merekam.", if (saving) null else onBack)
            error?.let { StudioNotice("Impor belum berhasil", it, true) }
            SectionLabel("01 / AUDIO MASTER")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (picked == null) "Pilih lagu untuk memulai" else audioName,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Audio ini yang terdengar pada hasil ekspor. Mikrofon kamera tidak direkam.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = !saving, onClick = { picker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (picked == null) "Pilih file audio" else "Ganti audio")
                    }
                }
            }
            SectionLabel("02 / NAMA PROYEK")
            OutlinedTextField(video, { video = it }, label = { Text("Nama proyek") },
                placeholder = { Text("Contoh: Belum Padam · MV") }, singleLine = true, enabled = !saving,
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            OutlinedTextField(music, { music = it }, label = { Text("Judul lagu") }, singleLine = true,
                enabled = !saving, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            SectionLabel("03 / DURASI POTONGAN")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 8, 10, 15).forEach { seconds ->
                    FilterChip(selected = sliceSec.toInt() == seconds, enabled = !saving,
                        onClick = { sliceSec = seconds.toFloat() }, label = { Text("${seconds}s") },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text("${sliceSec.toInt()} detik per potongan", style = MaterialTheme.typography.titleMedium)
            Slider(sliceSec, { sliceSec = it }, enabled = !saving, valueRange = 5f..30f, steps = 24,
                modifier = Modifier.semantics { contentDescription = "Durasi potongan dalam detik" })
            Text("Default 8 detik. Sesuaikan dengan kebutuhan generator. Potongan terakhir mengikuti sisa lagu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SlicesScreen(vm: StudioViewModel, pid: Long, onBack: () -> Unit,
    onRecord: (Int) -> Unit, onPreview: (Int) -> Unit, onAi: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val sliceAudio = remember { SliceAudioPlayer(ctx) }
    var playingSlice by remember { mutableIntStateOf(-1) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(pid) { vm.loadProject(pid) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshTakes(pid)
            if (event == Lifecycle.Event.ON_STOP) { sliceAudio.stop(); playingSlice = -1 }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); sliceAudio.release() }
    }
    val project = vm.current?.takeIf { it.id == pid }
    if (project == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val total = SliceHelper.count(project.audioDurationMs, project.sliceDurationSec)
    val recorded = vm.takes.map { it.sliceIndex }.filter { it in 0 until total }.toSet()
    val next = (0 until total).firstOrNull { it !in recorded }
    val indices = (0 until total).filter { filter == 0 || (filter == 1 && it !in recorded) || (filter == 2 && it in recorded) }
    fun stopAudio() { sliceAudio.stop(); playingSlice = -1 }
    Scaffold(bottomBar = {
        if (next != null) Surface(shadowElevation = 12.dp) {
            Button(onClick = { stopAudio(); onRecord(next) },
                modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 56.dp)) {
                Text("Rekam potongan ${(next + 1).toString().padStart(2, '0')} →")
            }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScreenHeader(project.videoTitle, project.musicTitle, onBack) }
            item { OutlinedButton(onClick = onAi, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("✦ Analisis lagu & konsep video")
            } }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionLabel("ANTREAN REKAMAN")
                        Text("${recorded.size} dari $total", style = MaterialTheme.typography.headlineLarge)
                        LinearProgressIndicator(progress = if (total > 0) recorded.size.toFloat() / total else 0f,
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                        Text("${project.sliceDurationSec}s per potongan · ${FileStore.fmt(project.audioDurationMs)} total",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Text(if (next == null) "Semua potongan direkam. Tinjau setiap video sebelum ekspor."
                            else "Dengarkan audio, rekam gerakan, lalu tinjau hasilnya.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Semua", "Belum", "Direkam").forEachIndexed { index, label ->
                        FilterChip(selected = filter == index, onClick = { filter = index },
                            label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            if (indices.isEmpty()) item { StudioNotice("Tidak ada potongan di sini", "Pilih filter lain untuk melihat antrean.") }
            items(indices, key = { it }) { index ->
                val (start, end) = SliceHelper.range(index, project.sliceDurationSec, project.audioDurationMs)
                val hasTake = index in recorded
                Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp,
                    if (playingSlice == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Potongan ${(index + 1).toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium)
                            StatusBadge(if (playingSlice == index) "DIPUTAR" else if (hasTake) "DIREKAM" else "BELUM", hasTake || playingSlice == index)
                        }
                        Text("${FileStore.fmt(start)} — ${FileStore.fmt(end)}  ·  ${(end - start) / 1000.0}s",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
                                if (playingSlice == index) stopAudio() else {
                                    stopAudio(); playingSlice = index
                                    sliceAudio.playSlice(project.audioPathInternal, start, end, project.globalOffsetMs) { playingSlice = -1 }
                                }
                            }) { Text(if (playingSlice == index) "Stop audio" else "Dengar") }
                            Button(modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
                                stopAudio()
                                if (hasTake) onPreview(index) else onRecord(index)
                            }) { Text(if (hasTake) "Tinjau" else "Rekam") }
                        }
                    }
                }
            }
            item { Text("Durasi potongan tetap untuk menjaga kecocokan rekaman dan audio. Buat proyek baru untuk pembagian berbeda.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// Camera start confirmation is the timing boundary for audio and duration.
@Composable
fun RecordScreen(vm: StudioViewModel, pid: Long, idx: Int, onBack: () -> Unit, onRecorded: (Long, Int) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(pid) { vm.loadProject(pid) }
    val project = vm.current?.takeIf { it.id == pid }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it; denied = !it
    }
    val recorder = remember { StudioRecorder(ctx) }
    val audio = remember { SliceAudioPlayer(ctx) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("idle") }
    var count by remember { mutableIntStateOf(3) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var cancelled by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    var captureId by remember { mutableIntStateOf(0) }
    fun cancelRecording() {
        cancelled = true
        captureId++
        job?.cancel()
        audio.stop()
        recorder.stop()
        phase = "idle"
        elapsed = 0
    }
    BackHandler(phase != "idle") { cancelRecording() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
            if (event == Lifecycle.Event.ON_STOP && phase != "idle") {
                cancelRecording()
                error = "Rekaman terhenti saat aplikasi ditinggalkan. Silakan rekam ulang."
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            active = false; cancelled = true; job?.cancel()
            lifecycle.lifecycle.removeObserver(observer)
            audio.release(); BeepPlayer.release(); recorder.stop(); recorder.unbind()
        }
    }
    LaunchedEffect(phase) {
        if (phase == "rec") {
            val start = android.os.SystemClock.elapsedRealtime()
            while (phase == "rec") { elapsed = android.os.SystemClock.elapsedRealtime() - start; delay(80) }
        } else elapsed = 0
    }
    val range = project?.let { SliceHelper.range(idx, it.sliceDurationSec, it.audioDurationMs) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Rekam ${(idx + 1).toString().padStart(2, '0')}",
            range?.let { "${FileStore.fmt(it.first)} — ${FileStore.fmt(it.second)} · ${(it.second - it.first) / 1000.0}s" } ?: "Memuat…",
            if (phase == "idle") onBack else null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("CAPTURE / REFERENCE")
            StatusBadge("MIC NONAKTIF", false)
        }
        error?.let { StudioNotice("Rekaman belum siap", it, true) }
        Card(Modifier.fillMaxWidth().height(360.dp)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (!granted) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Akses kamera diperlukan", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("Untuk merekam gerakanmu. Audio diambil dari lagu master, tanpa izin mikrofon.",
                            color = Color(0xFFCDCDCD), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Izinkan kamera") }
                        if (denied) TextButton(onClick = {
                            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${ctx.packageName}")))
                        }) { Text("Buka pengaturan izin") }
                    }
                } else AndroidView(factory = { c ->
                    PreviewView(c).also { view ->
                        view.scaleType = PreviewView.ScaleType.FIT_CENTER
                        preview = view
                        recorder.bind(view, lifecycle, onReady = { ready = true }) { message -> ready = false; error = message }
                    }
                }, modifier = Modifier.fillMaxSize())
                if (phase == "count") Text("$count", style = MaterialTheme.typography.displayLarge,
                    color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .65f),
                        RoundedCornerShape(24.dp)).padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite })
                if (phase == "rec" && range != null) {
                    Surface(Modifier.align(Alignment.TopCenter).padding(16.dp), color = Color(0xFF9F2424),
                        shape = RoundedCornerShape(50)) {
                        Text("REC  ·  ${FileStore.fmt(elapsed)}", color = Color.White,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    LinearProgressIndicator(progress = (elapsed.toFloat() / (range.second - range.first).coerceAtLeast(1)).coerceIn(0f, 1f),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
        }
        Text("Pakai headphone untuk mendengar musik saat bergerak. Rekaman berhenti otomatis di akhir potongan.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = ready && phase == "idle", modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = { preview?.let { ready = false; recorder.flip(it, lifecycle, onReady = { ready = true }) { message -> error = message } } }) {
            Text("Ganti kamera")
        }
        Button(enabled = ready && project != null && phase == "idle",
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                val p = project ?: return@Button
                val (start, end) = SliceHelper.range(idx, p.sliceDurationSec, p.audioDurationMs)
                error = null; cancelled = false
                captureId++
                val session = captureId
                job = scope.launch {
                    phase = "count"
                    for (number in 3 downTo 1) { count = number; BeepPlayer.tick(); delay(1000) }
                    BeepPlayer.go()
                    phase = "starting"
                    val output = FileStore.newTakeFile(ctx, pid, idx)
                    recorder.start(output,
                        onStarted = {
                            if (active && !cancelled && session == captureId) {
                                phase = "rec"
                                job = scope.launch {
                                    audio.playSlice(p.audioPathInternal, start, end, p.globalOffsetMs)
                                    delay(end - start)
                                    audio.stop(); phase = "done"; recorder.stop()
                                }
                            }
                        },
                        onDone = { file ->
                            if (!active || cancelled || session != captureId) file.delete() else scope.launch {
                                try { audio.stop(); vm.saveTake(pid, idx, start, end, file.absolutePath); onRecorded(pid, idx) }
                                catch (e: Exception) { error = "Rekaman tidak dapat disimpan. Coba lagi."; phase = "idle" }
                            }
                        },
                        onError = { message ->
                            output.delete()
                            if (active && session == captureId) { job?.cancel(); audio.stop(); error = message; phase = "idle" }
                        })
                }
            }) {
            Text(when (phase) {
                "count" -> "Bersiap…"
                "starting" -> "Menyiapkan kamera…"
                "rec" -> "Merekam…"
                "done" -> "Menyimpan rekaman…"
                else -> if (ready) "Mulai rekam · hitung mundur 3 detik" else "Menunggu kamera"
            })
        }
        if (phase == "count" || phase == "rec" || phase == "starting") TextButton(
            onClick = { cancelRecording() }, modifier = Modifier.fillMaxWidth()) { Text("Batalkan rekaman") }
    }
}

// Review keeps the image in focus; detailed sync controls live in a sheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: StudioViewModel, pid: Long, idx: Int, onBack: () -> Unit, onRetake: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(pid to idx) { vm.loadProject(pid) }
    val project = vm.current?.takeIf { it.id == pid }
    var takePath by remember(pid, idx) { mutableStateOf<String?>(null) }
    var loadingTake by remember(pid, idx) { mutableStateOf(true) }
    LaunchedEffect(pid, idx) {
        takePath = vm.latestTake(pid, idx)?.videoPathInternal
        loadingTake = false
    }
    val video = remember { ExoPlayer.Builder(ctx).build().apply { volume = 0f } }
    val audio = remember { SliceAudioPlayer(ctx) }
    var playing by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showRetake by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    val exporting = vm.exporting
    val exportedUri = vm.exportedUris["$pid:$idx"]
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(project?.globalOffsetMs) { project?.let { offset = it.globalOffsetMs.toFloat() } }
    fun stopPreview() { video.pause(); audio.stop(); playing = false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { video.pause(); audio.stop(); playing = false }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); video.release(); audio.release() }
    }
    LaunchedEffect(takePath) {
        takePath?.let {
            video.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(java.io.File(it))))
            video.prepare()
        }
    }
    BackHandler(exporting) {}
    if (showRetake) {
        AlertDialog(onDismissRequest = { showRetake = false },
            title = { Text("Rekam ulang potongan ini?") },
            text = { Text("Rekaman baru akan menjadi pilihan aktif. Rekaman lama tetap tersimpan di proyek.") },
            confirmButton = { TextButton(onClick = { showRetake = false; stopPreview(); onRetake() }) { Text("Rekam ulang") } },
            dismissButton = { TextButton(onClick = { showRetake = false }) { Text("Batal") } })
    }
    if (showSync && project != null) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = { showSync = false }
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionLabel("PENYESUAIAN LANJUTAN")
                Text("Sinkronisasi audio", style = MaterialTheme.typography.headlineMedium)
                Text("Berlaku untuk semua potongan dalam proyek ini.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${offset.toInt()} ms", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace)
                Text("Nilai positif mengambil bagian lagu lebih akhir; nilai negatif mengambil bagian lebih awal.",
                    style = MaterialTheme.typography.bodySmall)
                Slider(offset, { offset = it }, valueRange = -1000f..1000f, steps = 39,
                    modifier = Modifier.semantics { contentDescription = "Offset audio global dalam milidetik" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { offset = (offset - 50).coerceAtLeast(-1000f) }, modifier = Modifier.weight(1f)) { Text("−50 ms") }
                    TextButton(onClick = { offset = 0f }, modifier = Modifier.weight(1f)) { Text("Reset") }
                    OutlinedButton(onClick = { offset = (offset + 50).coerceAtMost(1000f) }, modifier = Modifier.weight(1f)) { Text("+50 ms") }
                }
                Button(onClick = {
                    scope.launch {
                        try {
                            vm.setGlobalOffset(project, offset.toInt())
                            showSync = false
                        } catch (e: Exception) { error = "Pengaturan sync belum tersimpan. Coba lagi." }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Terapkan ke seluruh proyek") }
            }
        }
    }
    Scaffold(bottomBar = {
        if (project != null && takePath != null) Surface(shadowElevation = 12.dp) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = !exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                    error = null; stopPreview()
                    vm.exportReference(project, idx, takePath!!)
                }) {
                    if (exporting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                    Text(if (exporting) "Menggabungkan video + audio…" else if (exportedUri != null) "Ekspor lagi" else "Ekspor video referensi")
                }
                TextButton(enabled = !exporting, onClick = { showRetake = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Rekam ulang")
                }
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Tinjau potongan ${(idx + 1).toString().padStart(2, '0')}",
                "Periksa gerakan dan musik sebelum ekspor.", if (exporting) null else onBack)
            if (project == null || loadingTake) {
                CircularProgressIndicator()
            } else if (takePath == null) {
                StudioNotice("Belum ada rekaman", "Rekam potongan ini untuk melihat preview.")
                Button(onClick = onRetake) { Text("Rekam sekarang") }
            } else {
                val (start, end) = SliceHelper.range(idx, project.sliceDurationSec, project.audioDurationMs)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${FileStore.fmt(start)} — ${FileStore.fmt(end)}", fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusBadge("${(end - start) / 1000.0}s", true)
                }
                Card(Modifier.fillMaxWidth().height(320.dp)) {
                    AndroidView(factory = { c -> PlayerView(c).apply { player = video; useController = false } },
                        modifier = Modifier.fillMaxSize())
                }
                OutlinedButton(enabled = !exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                    if (playing) stopPreview() else {
                        video.seekTo(0); video.play(); playing = true
                        audio.playSlice(project.audioPathInternal, start, end, project.globalOffsetMs) {
                            video.pause(); playing = false
                        }
                    }
                }) { Text(if (playing) "Stop preview" else "Putar video + musik") }
                OutlinedCard(onClick = { if (!exporting) { stopPreview(); offset = project.globalOffsetMs.toFloat(); showSync = true } },
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sinkronisasi audio", style = MaterialTheme.typography.titleSmall)
                            Text("Global · ${project.globalOffsetMs} ms", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Atur →", color = MaterialTheme.colorScheme.primary)
                    }
                }
                (error ?: vm.exportError)?.let { StudioNotice("Perlu dicoba lagi", it, true) }
                exportedUri?.let { uri ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Video referensi tersimpan", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Text("MP4 berisi video + audio master. Temukan di Galeri / RKStudio.",
                                style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(modifier = Modifier.weight(1f), onClick = {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    }.onFailure { error = "Tidak ada aplikasi pemutar video. Buka hasil melalui Galeri." }
                                }) { Text("Buka") }
                                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                                            clipData = android.content.ClipData.newRawUri("Video referensi", Uri.parse(uri))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        ctx.startActivity(Intent.createChooser(intent, "Bagikan video referensi"))
                                    }.onFailure { error = "Video belum dapat dibagikan. Coba melalui Galeri." }
                                }) { Text("Bagikan") }
                            }
                        }
                    }
                }
                Text("OUTPUT  /  MP4 + AUDIO MASTER", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Gunakan hasil ekspor sebagai video referensi pada generator yang mendukungnya.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}