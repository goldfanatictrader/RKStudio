package com.rkstudio.cliplocal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// Opsi terbaik: Material3 dark custom (Opsi 3) + pola DAW/Mume/TikTok.
// 5 layar: ProjectList -> Create -> SliceList -> Record -> Preview(+offset+download)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "projects") {
        composable("projects") { ProjectListScreen({ nav.navigate("create") }, { nav.navigate("slices/$it") }) }
        composable("create") { CreateProjectScreen({ nav.popBackStack() }) }
        composable("slices/{pid}") { SlicesScreen({ nav.navigate("record/0/0") }, { nav.navigate("preview/0/0") }) }
        composable("record/{pid}/{idx}") { RecordScreen() }
        composable("preview/{pid}/{idx}") { PreviewScreen() }
    }
}

@Composable fun ProjectListScreen(onCreate: () -> Unit, onOpen: (Long) -> Unit) {
    Scaffold(floatingActionButton = { Button(onClick = onCreate) { Text("+ Project") } }) { p ->
        LazyColumn(Modifier.padding(p).padding(16.dp)) {
            item { Text("RK Studio", style = MaterialTheme.typography.headlineMedium) }
            item { Text("Pilih project untuk mulai shoot clip (semua lokal).", style = MaterialTheme.typography.bodyMedium) }
            // TODO: Room list
        }
    }
}

@Composable fun CreateProjectScreen(onDone: () -> Unit) {
    var video by remember { mutableStateOf("") }
    var music by remember { mutableStateOf("") }
    var sliceSec by remember { mutableFloatStateOf(10f) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Create Project", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(video, { video = it }, label = { Text("Judul Video") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(music, { music = it }, label = { Text("Judul Music") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {}) { Text("Upload Lagu (local picker)") }
        Text("Durasi slice: ${sliceSec.toInt()} detik (default 10)")
        Slider(sliceSec, {}, valueRange = 5f..30f, steps = 24)
        Button(onClick = onDone) { Text("Simpan Project") }
    }
}

@Composable fun SlicesScreen(onRecord: () -> Unit, onPreview: () -> Unit) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Slices (tiap 10 dtk)", style = MaterialTheme.typography.headlineSmall) }
        items((0 until 6).toList()) { i ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Slice ${i + 1}"); Text("00:${i * 10}-00:${(i + 1) * 10}", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRecord) { Text("● Rec") }
                        OutlinedButton(onClick = onPreview) { Text("▶ Prev") }
                    }
                }
            }
        }
    }
}

@Composable fun RecordScreen() {
    var count by remember { mutableIntStateOf(0) } // 3-2-1 overlay + beep
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Record Slice (kamera + lagu internal)", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth().height(320.dp)) { Box(Modifier.fillMaxSize()) { Text("Viewfinder CameraX (mute mic)", modifier = Modifier.padding(16.dp)) } }
        if (count > 0) Text("Countdown: $count + beep", style = MaterialTheme.typography.headlineLarge)
        Button(onClick = {}) { Text("Mulai 3-2-1 + Record") }
    }
}

@Composable fun PreviewScreen() {
    var offset by remember { mutableFloatStateOf(0f) } // -1000..+1000 ms
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preview (video + lagu slice bareng)", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth().height(240.dp)) { Box(Modifier.fillMaxSize()) { Text("Video take (mute) + audio slice", modifier = Modifier.padding(16.dp)) } }
        Text("Offset: ${offset.toInt()} ms")
        Slider(offset, { offset = it }, valueRange = -1000f..1000f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("Simpan Offset Global") }
            OutlinedButton(onClick = {}) { Text("Download ke Gallery") }
        }
    }
}
