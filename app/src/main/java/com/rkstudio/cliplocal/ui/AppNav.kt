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
            CreateProjectScreen(vm, { pid -> nav.navigate("slices/$pid") { popUpTo("projects") } })
        }
        composable(
            "slices/{pid}", listOf(navArgument("pid") { type = NavType.LongType })
        ) { back ->
            val pid = back.arguments!!.getLong("pid")
            SlicesScreen(vm, pid,
                { idx -> nav.navigate("record/$pid/$idx") },
                { idx -> nav.navigate("preview/$pid/$idx") })
        }
        composable(
            "record/{pid}/{idx}",
            listOf(navArgument("pid") { type = NavType.LongType }, navArgument("idx") { type = NavType.IntType })
        ) { back ->
            RecordScreen(vm, back.arguments!!.getLong("pid"), back.arguments!!.getInt("idx")) { p, i ->
                nav.navigate("preview/$p/$i") { popUpTo("record/$p/$i") { inclusive = true } }
            }
        }
        composable(
            "preview/{pid}/{idx}",
            listOf(navArgument("pid") { type = NavType.LongType }, navArgument("idx") { type = NavType.IntType })
        ) { back ->
            PreviewScreen(vm, back.arguments!!.getLong("pid"), back.arguments!!.getInt("idx"))
        }
    }
}

// ---------- 1. PROJECT LIST ----------

@Composable
fun ProjectListScreen(vm: StudioViewModel, onCreate: () -> Unit, onOpen: (Long) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshProjects() }
    Scaffold(floatingActionButton = { Button(onClick = onCreate) { Text("+ Project") } }) { p ->
        LazyColumn(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("RK Studio", style = MaterialTheme.typography.headlineMedium)
                Text("Semua proses lokal, tanpa internet.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            items(vm.projects, key = { it.id }) { pr ->
                val n = SliceHelper.count(pr.audioDurationMs, pr.sliceDurationSec)
                Card(onClick = { onOpen(pr.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(pr.videoTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${pr.musicTitle} • ${FileStore.fmt(pr.audioDurationMs)} • $n slice @${pr.sliceDurationSec} dtk",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

// ---------- 2. CREATE PROJECT ----------

@Composable
fun CreateProjectScreen(vm: StudioViewModel, onDone: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf("") }
    var music by remember { mutableStateOf("") }
    var sliceSec by remember { mutableFloatStateOf(10f) }
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picked = uri
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Create Project", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(video, { video = it }, label = { Text("Judul Video") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(music, { music = it }, label = { Text("Judul Music") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { picker.launch("audio/*") }) {
            Text(if (picked == null) "Upload Lagu" else "Lagu: ${picked!!.lastPathSegment?.takeLast(24)} ✓ (ganti)")
        }
        Text("Durasi slice: ${sliceSec.toInt()} detik (default 10)")
        Slider(sliceSec, { sliceSec = it }, valueRange = 5f..30f, steps = 24)
        Button(
            enabled = picked != null && !saving,
            onClick = {
                saving = true
                scope.launch {
                    try {
                        val pid = vm.createProject(video, music, sliceSec.toInt(), picked!!)
                        onDone(pid)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "gagal: ${e.message}", Toast.LENGTH_LONG).show()
                        saving = false
                    }
                }
            }
        ) { Text(if (saving) "Menyimpan..." else "Simpan Project") }
    }
}

// ---------- 3. SLICE LIST ----------

@Composable
fun SlicesScreen(vm: StudioViewModel, pid: Long, onRecord: (Int) -> Unit, onPreview: (Int) -> Unit) {
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
            Text(pr.videoTitle, style = MaterialTheme.typography.headlineSmall)
            Text("${pr.musicTitle} • ${FileStore.fmt(pr.audioDurationMs)} • offset ${pr.globalOffsetMs} ms",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("Durasi slice: ${slider.toInt()} detik")
            Slider(slider, { slider = it }, valueRange = 5f..30f, steps = 24,
                onValueChangeFinished = { scope.launch { vm.setSliceDuration(pr, slider.toInt()) } })
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
                            Text("Slice ${i + 1} ${if (hasTake) "●" else ""}")
                            Text(
                                "${FileStore.fmt(s)} - ${FileStore.fmt(e)} • ${(e - s) / 1000.0}s",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (playingSlice == i) {
                            Text("Playing…", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
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
                        ) { Text(if (playingSlice == i) "■ Stop" else "▶ Audio") }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                sliceAudio.stop()
                                playingSlice = -1
                                onRecord(i)
                            }
                        ) { Text("● Rec") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                sliceAudio.stop()
                                playingSlice = -1
                                onPreview(i)
                            }
                        ) { Text("▶ Prev") }
                    }
                }
            }
        }
    }
}

// ---------- 4. RECORD ----------

@Composable
fun RecordScreen(vm: StudioViewModel, pid: Long, idx: Int, onRecorded: (Long, Int) -> Unit) {
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
        ActivityResultContracts.RequestMultiplePermissions()
    ) { g -> granted = g[Manifest.permission.CAMERA] == true }
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    val recorder = remember { StudioRecorder(ctx) }
    val audio = remember { SliceAudioPlayer(ctx) }
    var pv by remember { mutableStateOf<PreviewView?>(null) }
    var phase by remember { mutableStateOf("idle") } // idle|count|rec|done
    var count by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var err by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { try { audio.release() } catch (_: Exception) {}; recorder.stop(); recorder.unbind() }
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

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val info = if (pr != null) {
            val (s, e) = SliceHelper.range(idx, pr.sliceDurationSec, pr.audioDurationMs)
            "Slice ${idx + 1} • ${FileStore.fmt(s)}-${FileStore.fmt(e)} (${(e - s) / 1000} dtk)"
        } else "memuat..."
        Text(info, style = MaterialTheme.typography.headlineSmall)
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Card(Modifier.fillMaxWidth().height(380.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!granted) {
                    Button(onClick = {
                        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }) { Text("Izinkan Kamera") }
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
                    Text("$count", style = MaterialTheme.typography.displayLarge)
                }
                if (phase == "rec" && pr != null) {
                    val (s, e) = SliceHelper.range(idx, pr.sliceDurationSec, pr.audioDurationMs)
                    val prog = (elapsed.toFloat() / (e - s).coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(prog, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = granted && pr != null && pr.audioPathInternal.isNotEmpty() && phase == "idle",
                onClick = {
                    val p = pr ?: return@Button
                    val (s, e) = SliceHelper.range(idx, p.sliceDurationSec, p.audioDurationMs)
                    scope.launch {
                        phase = "count"
                        for (c in 3 downTo 1) {
                            count = c; BeepPlayer.tick(); delay(1000)
                        }
                        BeepPlayer.go()
                        val out = FileStore.newTakeFile(ctx, pid, idx)
                        phase = "rec"
                        recorder.start(out, onStarted = {}, onDone = { file ->
                            scope.launch {
                                audio.stop()
                                vm.saveTake(pid, idx, s, e, file.absolutePath)
                                onRecorded(pid, idx)
                            }
                        }, onError = { m -> err = m; phase = "idle" })
                        audio.playSlice(p.audioPathInternal, s, e, p.globalOffsetMs)
                        delay(e - s)
                        audio.stop()
                        recorder.stop()
                        phase = "done" // menunggu Finalize -> onDone -> preview
                    }
                }
            ) { Text(if (phase == "idle") "● Mulai 3-2-1 + Record" else "Merekam...") }
            OutlinedButton(
                enabled = pv != null,
                onClick = { pv?.let { recorder.flip(it, lifecycle) { m -> err = m } } }
            ) { Text("⇄ Kamera") }
        }
    }
}

// ---------- 5. PREVIEW + OFFSET + DOWNLOAD ----------

@Composable
fun PreviewScreen(vm: StudioViewModel, pid: Long, idx: Int) {
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
    LaunchedEffect(pr) { if (pr != null) offset = pr.globalOffsetMs.toFloat() }
    DisposableEffect(Unit) {
        onDispose { try { video.release() } catch (_: Exception) {}; audio.release() }
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

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preview Slice ${idx + 1}", style = MaterialTheme.typography.headlineSmall)
        if (pr == null || takePath == null) {
            Text(if (pr == null) "memuat..." else "Belum ada rekaman untuk slice ini. Rekam dulu via ● Rec.")
            return@Column
        }
        val (s, e) = SliceHelper.range(idx, pr.sliceDurationSec, pr.audioDurationMs)
        Card(Modifier.fillMaxWidth().height(260.dp)) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        player = video
                        useController = true
                    }
                },
                update = {},
                modifier = Modifier.fillMaxSize()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!playing) {
                    video.seekTo(0); video.play()
                    audio.playSlice(pr.audioPathInternal, s, e, offset.toInt()) { scope.launch { playing = false } }
                    playing = true
                } else {
                    video.pause(); audio.stop(); playing = false
                }
            }) { Text(if (playing) "❚❚ Pause" else "▶ Play bareng") }
        }
        Text("Offset audio: ${offset.toInt()} ms")
        Slider(offset, { offset = it }, valueRange = -1000f..1000f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    vm.setGlobalOffset(pr, offset.toInt())
                    Toast.makeText(ctx, "offset ${offset.toInt()} ms dipakai global", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Simpan Offset Global") }
            OutlinedButton(
                enabled = !exporting,
                onClick = {
                    exporting = true
                    video.pause()
                    audio.stop()
                    playing = false
                    Exporter.exportTakeWithAudioToGallery(
                        context = ctx,
                        videoPath = takePath!!,
                        audioPath = pr.audioPathInternal,
                        sliceStartMs = s,
                        sliceEndMs = e,
                        audioDurationMs = pr.audioDurationMs,
                        offsetMs = offset.toInt(),
                        displayName = "RKStudio_slice${idx + 1}_${System.currentTimeMillis()}.mp4",
                        onSuccess = {
                            exporting = false
                            Toast.makeText(
                                ctx,
                                "tersimpan di Gallery: video + musik slice",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onFailure = { message ->
                            exporting = false
                            Toast.makeText(
                                ctx,
                                "export gagal: $message",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            ) { Text(if (exporting) "Exporting…" else "⬇ Gallery") }
        }
        Text(
            "Export Gallery = video take + musik slice + offset dalam satu MP4.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
