[Reading 882 lines from start (total: 882 lines, 0 remaining)]

package com.rkstudio.cliplocal.ui

import android.Manifest
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

@Composable
fun AppNav(vm: StudioViewModel = viewModel()) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "projects") {
        composable("projects") {
            ProjectListScreen(vm, { nav.navigate("create") }, { nav.navigate("slices/$it") })
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
                onPreview = { idx -> nav.navigate("preview/$pid/$idx") }
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
                onRetake = { nav.navigate("record/$p/$i") }
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
        Text(title, style = MaterialTheme.typography.headlineMedium)
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

// ---------- 1. PROJECT LIST ----------

@Composable
fun ProjectListScreen(vm: StudioViewModel, onCreate: () -> Unit, onOpen: (Long) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshProjects() }

    Scaffold(
        floatingActionButton = {
            Button(onClick = onCreate) { Text("+ Project Baru") }
        }
    ) { p ->
        LazyColumn(
            Modifier.padding(p).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenHeader(
                    title = "RK Studio",
                    subtitle = "Rekam clip reference yang sinkron dengan potongan audio untuk AI video generation."
                )
                Spacer(Modifier.height(8.dp))
            }

            if (vm.projects.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Belum ada project", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Import satu lagu, tentukan durasi slice, lalu rekam reference clip satu per satu.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onCreate) { Text("Buat Project Pertama") }
                        }
                    }
                }
            }

            items(vm.projects, key = { it.id }) { pr ->
                val total = SliceHelper.count(pr.audioDurationMs, pr.sliceDurationSec)
                val recorded = vm.recordedCounts[pr.id] ?: 0
                Card(onClick = { onOpen(pr.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(pr.videoTitle, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    pr.musicTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("›", style = MaterialTheme.typography.headlineMedium)
                        }
                        Text(
                            "${FileStore.fmt(pr.audioDurationMs)} • $total slice • ${pr.sliceDurationSec} dtk/slice",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (recorded > 0 && total > 0) {
                            LinearProgressIndicator(
                                progress = recorded.toFloat() / total.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("$recorded/$total slice sudah direkam", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

// ---------- 2. CREATE PROJECT ----------

@Composable
fun CreateProjectScreen(
    vm: StudioViewModel,
    onBack: () -> Unit,
    onDone: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf("") }
    var music by remember { mutableStateOf("") }
    var sliceSec by remember { mutableFloatStateOf(8f) }
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picked = uri
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader(
            title = "Project Baru",
            subtitle = "Siapkan lagu master yang akan dibagi menjadi reference clip.",
            onBack = onBack
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("1. Identitas project", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = video,
                    onValueChange = { video = it },
                    label = { Text("Nama project / video") },
                    placeholder = { Text("Contoh: Belum Padam MV") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = music,
                    onValueChange = { music = it },
                    label = { Text("Judul lagu") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("2. Lagu master", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (picked == null) "Belum ada audio dipilih."
                    else "Audio siap: ${picked!!.lastPathSegment?.takeLast(36) ?: "file audio"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { picker.launch("audio/*") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (picked == null) "Pilih Audio" else "Ganti Audio") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("3. Durasi reference", style = MaterialTheme.typography.titleMedium)
                    Text("${sliceSec.toInt()} detik", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Untuk generator video AI, 8 detik adalah titik awal yang praktis. Bisa diubah 5–30 detik.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = sliceSec,
                    onValueChange = { sliceSec = it },
                    valueRange = 5f..30f,
                    steps = 24
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            enabled = picked != null && !saving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                saving = true
                scope.launch {
                    try {
                        val pid = vm.createProject(video, music, sliceSec.toInt(), picked!!)
                        onDone(pid)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Gagal membuat project: ${e.message}", Toast.LENGTH_LONG).show()
                        saving = false
                    }
                }
            }
        ) { Text(if (saving) "Menyiapkan project…" else "Buat Project & Generate Slice") }
    }
}

// ---------- 3. SLICE LIST ----------

@Composable
fun SlicesScreen(
    vm: StudioViewModel,
    pid: Long,
    onBack: () -> Unit,
    onRecord: (Int) -> Unit,
    onPreview: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val sliceAudio = remember { SliceAudioPlayer(ctx) }
    var playingSlice by remember { mutableIntStateOf(-1) }

    LaunchedEffect(pid) { vm.loadProject(pid) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.refreshTakes(pid)
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose {
            lifecycle.lifecycle.removeObserver(obs)
            sliceAudio.release()
        }
    }
    val pr = vm.current
    if (pr == null || pr.id != pid) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var slider by remember(pr.sliceDurationSec) { mutableFloatStateOf(pr.sliceDurationSec.toFloat()) }
    val n = SliceHelper.count(pr.audioDurationMs, pr.sliceDurationSec)
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ScreenHeader(
                title = pr.videoTitle,
                subtitle = "${pr.musicTitle} • ${FileStore.fmt(pr.audioDurationMs)}",
                onBack = onBack
            )
            Spacer(Modifier.height(12.dp))

            val recordedCount = vm.takes.map { it.sliceIndex }.distinct().size
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Progress", style = MaterialTheme.typography.titleMedium)
                        Text("$recordedCount/$n slice", style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(
                        progress = if (n > 0) recordedCount.toFloat() / n.toFloat() else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Audio offset ${pr.globalOffsetMs} ms • ${slider.toInt()} detik per reference",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Durasi reference: ${slider.toInt()} detik", style = MaterialTheme.typography.titleSmall)
            Slider(
                slider,
                { slider = it },
                valueRange = 5f..30f,
                steps = 24,
                onValueChangeFinished = {
                    scope.launch { vm.setSliceDuration(pr, slider.toInt()) }
                }
            )
            Text(
                "Pilih Audio untuk cek potongan, Record untuk membuat reference, lalu Review sebelum export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
        items((0 until n).toList()) { i ->
            val (s, e) = SliceHelper.range(i, pr.sliceDurationSec, pr.audioDurationMs)
            val hasTake = vm.takes.any { it.sliceIndex == i }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Slice ${(i + 1).toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${FileStore.fmt(s)} – ${FileStore.fmt(e)} • ${(e - s) / 1000.0}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusBadge(
                            text = when {
                                playingSlice == i -> "PLAYING"
                                hasTake -> "RECORDED"
                                else -> "READY"
                            },
                            active = hasTake || playingSlice == i
                        )
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (playingSlice == i) {
                                sliceAudio.stop()
                                playingSlice = -1
                            } else {
                                sliceAudio.stop()
                                playingSlice = i
                                sliceAudio.playSlice(
                                    audioPath = pr.audioPathInternal,
                                    startMs = s,
                                    endMs = e,
                                    offsetMs = pr.globalOffsetMs,
                                    onEnded = { playingSlice = -1 }
                                )
                            }
                        }
                    ) { Text(if (playingSlice == i) "■ Stop Audio" else "▶ Dengarkan Audio Slice") }

                    if (hasTake) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                sliceAudio.stop()
                                playingSlice = -1
                                onPreview(i)
                            }
                        ) { Text("Review & Export AI Reference") }

                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                sliceAudio.stop()
                                playingSlice = -1
                                onRecord(i)
                            }
                        ) { Text("Retake Slice") }
                    } else {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                sliceAudio.stop()
                                playingSlice = -1
                                onRecord(i)
                            }
                        ) { Text("● Record Reference") }
                    }
                }
            }
        }
    }
}

// ---------- 4. RECORD ----------

@Composable
fun RecordScreen(
    vm: StudioViewModel,
    pid: Long,
    idx: Int,
    onBack: () -> Unit,
    onRecorded: (Long, Int) -> Unit
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(pid) { vm.loadProject(pid) }
    val pr = vm.current?.takeIf { it.id == pid }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    val recorder = remember { StudioRecorder(ctx) }
    val audio = remember { SliceAudioPlayer(ctx) }
    var pv by remember { mutableStateOf<PreviewView?>(null) }
    var phase by remember { mutableStateOf("idle") }
    var count by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var err by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { audio.release() } catch (_: Exception) {}
            BeepPlayer.release()
            recorder.stop()
            recorder.unbind()
        }
    }

    LaunchedEffect(phase) {
        if (phase == "rec") {
            val t0 = System.currentTimeMillis()
            while (phase == "rec") {
                elapsed = System.currentTimeMillis() - t0
                delay(100)
            }
        } else elapsed = 0
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val range = pr?.let { SliceHelper.range(idx, it.sliceDurationSec, it.audioDurationMs) }
        ScreenHeader(
            title = "Record Slice ${(idx + 1).toString().padStart(2, '0')}",
            subtitle = range?.let { (s, e) ->
                "${FileStore.fmt(s)} – ${FileStore.fmt(e)} • ${(e - s) / 1000.0}s"
            } ?: "Memuat…",
            onBack = if (phase == "idle") onBack else null
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "Gunakan headphone. Kamera direkam tanpa mic; musik master akan dimasukkan saat export.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!granted) {
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Izinkan Kamera")
                    }
                } else {
                    AndroidView(
                        factory = { c ->
                            PreviewView(c).also { v ->
                                pv = v
                                recorder.bind(v, lifecycle) { m -> err = m }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (phase == "count") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)
                    ) {
                        Text(
                            "$count",
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.displayLarge
                        )
                    }
                }

                if (phase == "rec" && range != null) {
                    val duration = (range.second - range.first).coerceAtLeast(1)
                    val prog = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        prog,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    )
                    StatusBadge("● RECORDING", true)
                }
            }
        }

        OutlinedButton(
            enabled = pv != null && phase == "idle",
            modifier = Modifier.fillMaxWidth(),
            onClick = { pv?.let { recorder.flip(it, lifecycle) { m -> err = m } } }
        ) { Text("⇄ Ganti Kamera") }

        Button(
            enabled = granted && pr != null && pr.audioPathInternal.isNotEmpty() && phase == "idle",
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                val p = pr ?: return@Button
                val (s, e) = SliceHelper.range(idx, p.sliceDurationSec, p.audioDurationMs)
                scope.launch {
                    phase = "count"
                    for (c in 3 downTo 1) {
                        count = c
                        BeepPlayer.tick()
                        delay(1000)
                    }
                    BeepPlayer.go()
                    val out = FileStore.newTakeFile(ctx, pid, idx)
                    phase = "rec"
                    recorder.start(
                        out,
                        onStarted = {},
                        onDone = { file ->
                            scope.launch {
                                audio.stop()
                                vm.saveTake(pid, idx, s, e, file.absolutePath)
                                onRecorded(pid, idx)
                            }
                        },
                        onError = { m -> err = m; phase = "idle" }
                    )
                    audio.playSlice(p.audioPathInternal, s, e, p.globalOffsetMs)
                    delay(e - s)
                    audio.stop()
                    recorder.stop()
                    phase = "done"
                }
            }
        ) {
            Text(
                when (phase) {
                    "count" -> "Bersiap…"
                    "rec" -> "Merekam…"
                    "done" -> "Memproses…"
                    else -> "● Mulai Record"
                }
            )
        }
    }
}

// ---------- 5. PREVIEW + OFFSET + DOWNLOAD ----------

@Composable
fun PreviewScreen(
    vm: StudioViewModel,
    pid: Long,
    idx: Int,
    onBack: () -> Unit,
    onRetake: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(pid to idx) { vm.loadProject(pid) }
    val pr = vm.current?.takeIf { it.id == pid }
    var takePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pr, idx) { takePath = vm.latestTake(pid, idx)?.videoPathInternal }

    val video = remember { ExoPlayer.Builder(ctx).build().apply { volume = 0f } }
    val audio = remember { SliceAudioPlayer(ctx) }
    var playing by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    var exporting by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf(false) }

    LaunchedEffect(pr) { if (pr != null) offset = pr.globalOffsetMs.toFloat() }
    DisposableEffect(Unit) {
        onDispose {
            try { video.release() } catch (_: Exception) {}
            audio.release()
        }
    }
    LaunchedEffect(takePath) {
        takePath?.let {
            video.setMediaItem(
                androidx.media3.common.MediaItem.fromUri(
                    android.net.Uri.fromFile(java.io.File(it))
                )
            )
            video.prepare()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(
            title = "Review Slice ${(idx + 1).toString().padStart(2, '0')}",
            subtitle = "Cek sync sebelum dijadikan input video reference AI.",
            onBack = onBack
        )

        if (pr == null || takePath == null) {
            Text(if (pr == null) "Memuat…" else "Belum ada rekaman untuk slice ini.")
            Button(onClick = onRetake) { Text("Record Slice") }
            return@Column
        }

        val (s, e) = SliceHelper.range(idx, pr.sliceDurationSec, pr.audioDurationMs)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${FileStore.fmt(s)} – ${FileStore.fmt(e)} • ${(e - s) / 1000.0}s",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusBadge(if (exported) "EXPORTED" else "RECORDED", true)
        }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        player = video
                        useController = false
                    }
                },
                update = {},
                modifier = Modifier.fillMaxSize()
            )
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!playing) {
                    video.seekTo(0)
                    video.play()
                    audio.playSlice(
                        pr.audioPathInternal,
                        s,
                        e,
                        offset.toInt()
                    ) { scope.launch { playing = false } }
                    playing = true
                } else {
                    video.pause()
                    audio.stop()
                    playing = false
                }
            }
        ) { Text(if (playing) "❚❚ Pause Preview" else "▶ Preview Video + Audio") }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sync audio", style = MaterialTheme.typography.titleMedium)
                    Text("${offset.toInt()} ms", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "Geser hanya bila gerakan dan musik belum tepat. Nilai ini dipakai ke seluruh slice project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(offset, { offset = it }, valueRange = -1000f..1000f)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            vm.setGlobalOffset(pr, offset.toInt())
                            Toast.makeText(
                                ctx,
                                "Offset global disimpan: ${offset.toInt()} ms",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text("Simpan Sync Global") }
            }
        }

        Button(
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                exporting = true
                exported = false
                video.pause()
                audio.stop()
                playing = false

                val sliceNo = (idx + 1).toString().padStart(3, '0')
                val fileName = "RKStudio_S${sliceNo}_${s}-${e}.mp4"

                Exporter.exportTakeWithAudioToGallery(
                    context = ctx,
                    videoPath = takePath!!,
                    audioPath = pr.audioPathInternal,
                    sliceStartMs = s,
                    sliceEndMs = e,
                    audioDurationMs = pr.audioDurationMs,
                    offsetMs = offset.toInt(),
                    displayName = fileName,
                    onSuccess = {
                        exporting = false
                        exported = true
                        Toast.makeText(
                            ctx,
                            "AI reference tersimpan: $fileName",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = { message ->
                        exporting = false
                        Toast.makeText(
                            ctx,
                            "Export gagal: $message",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        ) { Text(if (exporting) "Menyiapkan AI Reference…" else "⬇ Export AI Reference") }

        TextButton(
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRetake
        ) { Text("Retake Slice") }
    }
}
