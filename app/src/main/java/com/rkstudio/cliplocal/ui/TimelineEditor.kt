package com.rkstudio.cliplocal.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.rkstudio.cliplocal.ai.StudioAiViewModel
import kotlinx.coroutines.*
import java.net.URL
import java.util.Locale

private fun timecode(ms: Long): String = String.format(Locale.US,"%02d:%02d.%01d",ms/60000,(ms/1000)%60,(ms%1000)/100)
private fun kindLabel(kind: String) = when(kind) { "image" -> "GAMBAR"; "video" -> "VIDEO"; else -> "NASKAH" }
private val EditorAccent = Color(0xFFF4AA73)

@Composable
fun TimelineHome(vm: TimelineViewModel, onOpen: (String) -> Unit, onMusic: () -> Unit, onSettings: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var script by rememberSaveable { mutableStateOf("") }
    var ratio by rememberSaveable { mutableStateOf("9:16") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RK STUDIO",Modifier.weight(1f),style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSettings) { Text("Pengaturan") }
        }
        Text("Ruang produksi",style = MaterialTheme.typography.headlineLarge)
        Text("Mulai dari naskah. Tonton setiap tahapnya.",color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { creating = true },Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("+ Proyek video") }
        TextButton(onClick = onMusic) { Text("Buka proyek potong lagu & rekam →") }
        HorizontalDivider()
        if (vm.projects.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("01  Tulis adegan\n02  Bangun gambar\n03  Hidupkan jadi video",
                style = MaterialTheme.typography.headlineSmall)
            Text("Semua berada dalam satu timeline. Pisahkan paragraf naskah untuk membuat beberapa shot.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.projects.reversed().forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(vertical=14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center) { Text(p.ratio,fontFamily = FontFamily.Monospace) }
                Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                    Text(p.title,style = MaterialTheme.typography.titleMedium)
                    Text("${p.shots.size} shot · ${timecode(p.durationMs)}",style = MaterialTheme.typography.bodySmall)
                }
                Text("→")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        vm.error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false },title = { Text("Proyek video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title,{title=it},label={Text("Nama proyek")},singleLine=true)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("9:16","16:9","1:1").forEach { r ->
                        FilterChip(selected=ratio==r,onClick={ratio=r},label={Text(r)})
                    }
                }
                OutlinedTextField(script,{script=it},label={Text("Naskah awal (opsional)")},minLines=4,maxLines=8)
                Text("Satu paragraf = satu shot. Durasi awal 8 detik, bisa diubah.",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton = { TextButton(enabled=title.isNotBlank() && !vm.working,onClick={
            val id=vm.create(title,ratio,script); creating=false; title="";script="";onOpen(id)
        }) { Text("Buat timeline") } },dismissButton={TextButton(onClick={creating=false}){Text("Batal")}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineEditor(vm: TimelineViewModel, ai: StudioAiViewModel, pid: String, onBack: () -> Unit, onSettings: () -> Unit) {
    val project = vm.projects.firstOrNull { it.id == pid } ?: return
    var position by rememberSaveable(pid) { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var busyMedia by remember { mutableStateOf(false) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    val index = project.indexAt(position)
    val shot = project.shots.getOrNull(index) ?: return
    val offset = (position - project.startAt(index)).coerceAtLeast(0)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pid) { vm.resume() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_STOP) playing=false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playing,project.durationMs,busyMedia) {
        if (playing && !busyMedia) {
            var last = SystemClock.elapsedRealtime()
            while (playing) {
                delay(32)
                val now=SystemClock.elapsedRealtime()
                position=(position + now-last).coerceAtMost(project.durationMs)
                last=now
                if (position>=project.durationMs) playing=false
            }
        }
    }
    LaunchedEffect(shot.activeId) { mediaError=null; busyMedia=shot.active.kind=="video" }
    fun stop() { playing=false }
    var importTarget by remember { mutableStateOf<Pair<String,String>?>(null) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target=importTarget
        if(uri!=null && target!=null) vm.importMedia(pid,target.first,uri,target.second)
        importTarget=null
    }
    val previewHeight=if(LocalConfiguration.current.screenHeightDp < 700) 180.dp else 260.dp
    Scaffold(bottomBar = {
        Surface(tonalElevation=3.dp) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={stop();sheet=if(shot.active.kind=="script") "image" else "video"},
                    enabled=!vm.working && vm.job==null) {
                    Text(when(shot.active.kind) {"script"->"Generate gambar";"image"->"Generate video";else->"Regenerate video"})
                }
                OutlinedButton(onClick={stop();sheet="versions"}) {Text("Pilih versi")}
                OutlinedButton(onClick={stop();sheet="edit"}) {Text("Edit naskah")}
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onBack){Text("←")}
                Column(Modifier.weight(1f)) {
                    Text(project.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                    Text("TIMELINE  /  ${project.ratio}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick={stop();onSettings()}){Text("API")}
            }
            Box(Modifier.fillMaxWidth().height(previewHeight).background(Color.Black),contentAlignment=Alignment.Center) {
                val aspect=when(project.ratio){"16:9"->16f/9f;"1:1"->1f;else->9f/16f}
                Box(Modifier.aspectRatio(aspect,matchHeightConstraintsFirst=true).fillMaxHeight()
                    .background(Color(0xFF202124)),contentAlignment=Alignment.Center) {
                    when(shot.active.kind) {
                        "image" -> TimelineImage(shot.active.uri,Modifier.fillMaxSize())
                        "video" -> TimelineVideo(shot.active.uri,offset,playing,
                            {busyMedia=it},{mediaError=it;playing=false;busyMedia=false})
                        else -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("SHOT ${index+1}",color=EditorAccent,fontFamily=FontFamily.Monospace,
                                style=MaterialTheme.typography.labelSmall)
                            Text(shot.active.text.ifBlank{"Ketuk Edit naskah untuk mulai."},
                                style=MaterialTheme.typography.bodyMedium,color=Color.White)
                        }
                    }
                }
                Text(kindLabel(shot.active.kind),Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color.Black.copy(alpha=.7f)).padding(6.dp),
                    style=MaterialTheme.typography.labelSmall,color=Color.White)
                if(busyMedia) CircularProgressIndicator(Modifier.size(28.dp))
            }
            mediaError?.let { Text(it,Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(timecode(position),Modifier.weight(1f),fontFamily=FontFamily.Monospace)
                TextButton(onClick={stop();position=0}) {Text("Awal")}
                FilledTonalButton(onClick={
                    if(position>=project.durationMs) position=0
                    playing=!playing
                },enabled=mediaError==null) {Text(if(playing) "Jeda" else "Play")}
                Text(timecode(project.durationMs),Modifier.padding(start=12.dp),fontFamily=FontFamily.Monospace)
            }
            Slider(value=position.toFloat().coerceIn(0f,project.durationMs.toFloat()),
                onValueChange={stop();position=it.toLong()},valueRange=0f..project.durationMs.toFloat().coerceAtLeast(1f),
                modifier=Modifier.padding(horizontal=16.dp))
            LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                itemsIndexed(project.shots,key={_,s->s.id}) { i,s ->
                    val selected=s.id==shot.id
                    Column(Modifier.width((s.durationMs/1000f*14).coerceIn(100f,240f).dp)
                        .border(if(selected) 2.dp else 1.dp,if(selected) EditorAccent else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(4.dp))
                        .clickable { stop();position=project.startAt(i) }.padding(8.dp)) {
                        Text("${(i+1).toString().padStart(2,'0')}  ${kindLabel(s.active.kind)}",
                            color=if(selected) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            style=MaterialTheme.typography.labelSmall)
                        if(s.active.kind=="image") TimelineImage(s.active.uri,Modifier.fillMaxWidth().height(48.dp))
                        else Text(s.active.text.ifBlank{"Tanpa naskah"},maxLines=2,
                            overflow=TextOverflow.Ellipsis,modifier=Modifier.heightIn(min=48.dp),style=MaterialTheme.typography.bodySmall)
                        Text(timecode(s.durationMs),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)
                    }
                }
                item { TextButton(onClick={stop();vm.add(pid);position=project.durationMs}){Text("+ Shot")} }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Shot ${index+1}",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={stop();sheet="versions"}){Text("Pilih versi (${shot.versions.size})")}
                }
                Text("${shot.durationMs/1000} detik · ${kindLabel(shot.active.kind).lowercase()}",
                    style=MaterialTheme.typography.bodySmall)
                if(shot.active.kind=="video" && shot.versions.any {it.kind=="image"} && shot.versions.lastOrNull {it.kind=="image"}?.id != shot.active.sourceId) {
                    Text("Video ini memakai gambar acuan sebelumnya. Ganti versi video atau regenerate untuk memakai gambar baru.",
                        color=EditorAccent,style=MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(shot.active.kind!="script") OutlinedButton(onClick={stop();sheet=shot.active.kind}){Text("Regenerate")}
                    OutlinedButton(onClick={stop();sheet="idea"}){Text("Coba ide baru")}
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick={stop();importTarget=shot.id to "image";importer.launch(arrayOf("image/*"))}){Text("Impor gambar")}
                    TextButton(onClick={stop();importTarget=shot.id to "video";importer.launch(arrayOf("video/*"))}){Text("Impor video")}
                    TextButton(enabled=index>0,onClick={
                        stop();val target=project.startAt(index-1);vm.move(pid,shot.id,-1);position=target
                    }){Text("← Geser")}
                    TextButton(enabled=index<project.shots.lastIndex,onClick={
                        stop();val target=project.startAt(index)+project.shots[index+1].durationMs;vm.move(pid,shot.id,1);position=target
                    }){Text("Geser →")}
                }
                if(vm.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(vm.status.isNotBlank()) Text(vm.status,style=MaterialTheme.typography.bodySmall)
                vm.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                if(vm.job!=null) {
                    Text("Permintaan tersimpan untuk shot asal. Hasil aktif tetap dipertahankan.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={vm.resume()}){Text("Cek status")}
                    if(vm.job?.optString("id").isNullOrBlank() && !vm.working)
                        TextButton(onClick={sheet="uncertain"}){Text("Saya sudah memeriksa riwayat provider")}
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if(sheet.isNotBlank()) ModalBottomSheet(onDismissRequest={sheet=""},
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            when(sheet) {
                "edit","idea" -> {
                    var text by remember(shot.id,sheet) {mutableStateOf(if(sheet=="idea") "" else shot.active.text)}
                    var seconds by remember(shot.id) {mutableStateOf((shot.durationMs/1000).toString())}
                    Text(if(sheet=="idea") "Arah baru untuk shot ini" else "Naskah & durasi",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(text,{text=it},label={Text("Aksi, dialog, dan arahan visual")},minLines=4,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(seconds,{seconds=it},label={Text("Durasi (1–300 detik)")},singleLine=true)
                    Text("Disimpan sebagai versi naskah baru. Gambar dan video sebelumnya tetap tersedia.",style=MaterialTheme.typography.bodySmall)
                    Button(enabled=text.isNotBlank() && seconds.toIntOrNull() in 1..300,onClick={
                        vm.script(pid,shot.id,text,seconds.toInt());position=project.startAt(index);sheet=""
                    }){Text("Simpan versi naskah")}
                }
                "versions" -> {
                    Text("Versi shot ${index+1}",style=MaterialTheme.typography.titleLarge)
                    shot.versions.reversed().forEach { v ->
                        Column(Modifier.fillMaxWidth().border(1.dp,if(v.id==shot.activeId) EditorAccent else MaterialTheme.colorScheme.outlineVariant)
                            .padding(12.dp)) {
                            Text("${kindLabel(v.kind)} · ${if(v.id==shot.activeId) "AKTIF" else "KANDIDAT"}",
                                style=MaterialTheme.typography.labelMedium)
                            if(v.kind=="image") TimelineImage(v.uri,Modifier.fillMaxWidth().height(140.dp))
                            Text(v.text,maxLines=4,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                            if(v.sourceId.isNotBlank()) Text("Dari versi ${shot.versions.indexOfFirst { it.id==v.sourceId }+1}",
                                style=MaterialTheme.typography.labelSmall)
                            if(v.id!=shot.activeId) TextButton(onClick={
                                vm.activate(pid,shot.id,v.id);position=project.startAt(index);sheet=""
                            }){Text("Gunakan versi ini")}
                        }
                    }
                }
                "uncertain" -> {
                    Text("Jangan kirim ulang sebelum status jelas",style=MaterialTheme.typography.titleLarge)
                    Text("Permintaan mungkin sudah diterima dan memakai kredit. Setelah memeriksa riwayat SimpleNGAT, hapus penanda ini untuk mengizinkan permintaan baru.")
                    Button(onClick={vm.clearUncertainJob();sheet=""}){Text("Hapus penanda permintaan")}
                }
                else -> ShotGeneration(vm,ai,project,shot,sheet,onSettings) {sheet=""}
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShotGeneration(vm: TimelineViewModel, ai: StudioAiViewModel, p: TimelineProject,
                           s: TimelineShot, kind: String, settings: () -> Unit, done: () -> Unit) {
    LaunchedEffect(Unit) {ai.loadProviders()}
    Text(if(kind=="image") "Generate gambar shot" else "Generate video shot",style=MaterialTheme.typography.titleLarge)
    if(!ai.config.simpleReady) {
        Text("Konfigurasikan API key SimpleNGAT di perangkat ini.")
        Button(onClick={done();settings()}){Text("Buka pengaturan")};return
    }
    val models=if(kind=="image") ai.images else ai.videos.filter {"i2v" in it.options("modes")}
    var selected by remember {mutableStateOf("")}
    val model=models.firstOrNull {it.id==selected} ?: models.firstOrNull()
    if(model==null) {
        Text("Daftar model belum tersedia.")
        TextButton(onClick={ai.loadProviders()}){Text("Muat model")}
        ai.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        return
    }
    var prompt by remember(s.activeId) {mutableStateOf(s.active.text)}
    var ratio by remember(model.id) {mutableStateOf(model.options("aspectRatios").firstOrNull {
        it==p.ratio || (p.ratio=="9:16" && it=="portrait") || (p.ratio=="16:9" && it=="landscape")
    } ?: model.options("aspectRatios").firstOrNull().orEmpty())}
    var quality by remember(model.id) {mutableStateOf(model.options("qualities").firstOrNull() ?: "720p")}
    var duration by remember(model.id) {mutableStateOf(model.options("durations").firstOrNull().orEmpty())}
    Text("Model",style=MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        models.forEach {m->FilterChip(selected=m.id==model.id,onClick={selected=m.id},label={Text(m.label)})}
    }
    OutlinedTextField(prompt,{prompt=it},label={Text("Arahan shot")},minLines=3,modifier=Modifier.fillMaxWidth())
    @Composable fun options(label: String, values: List<String>, active: String, update: (String)->Unit) {
        if(values.isNotEmpty()) {
            Text(label,style=MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                values.forEach {v->FilterChip(selected=active==v,onClick={update(v)},label={Text(v)})}
            }
        }
    }
    options("Rasio",model.options("aspectRatios"),ratio){ratio=it}
    if(kind=="video") {
        options("Kualitas",model.options("qualities").ifEmpty{listOf("720p","1080p")},quality){quality=it}
        options("Durasi generator",model.options("durations"),duration){duration=it}
        Text("Gambar aktif menjadi acuan. Durasi timeline tetap ${s.durationMs/1000} detik; klip panjang berhenti di batas shot, klip pendek menahan frame terakhir.",
            style=MaterialTheme.typography.bodySmall)
    }
    val sourceImage=if(s.active.kind=="image") s.active else s.versions.firstOrNull{it.id==s.active.sourceId && it.kind=="image"}
    val valid=kind=="image" || (sourceImage?.uri?.startsWith("https://")==true)
    if(!valid) Text("Pilih versi gambar hasil generate terlebih dahulu untuk image-to-video.",
        color=MaterialTheme.colorScheme.error)
    Text(if(kind=="image") "Biaya: ${model.imageCost()} kredit / gambar" else model.videoPrice(),
        style=MaterialTheme.typography.bodySmall)
    Text("Hasil menjadi kandidat. Versi aktif tidak diganti otomatis.",style=MaterialTheme.typography.bodySmall)
    Button(enabled=valid && prompt.isNotBlank() && !vm.working && vm.job==null,onClick={
        vm.generate(p.id,s.id,kind,prompt,model,ratio,quality,duration.toIntOrNull());done()
    },modifier=Modifier.fillMaxWidth()){Text("Generate · gunakan kredit")}
}

@Composable
private fun TimelineImage(uri: String, modifier: Modifier=Modifier) {
    val context=LocalContext.current
    var bitmap by remember(uri){mutableStateOf<android.graphics.Bitmap?>(null)}
    var failed by remember(uri){mutableStateOf(false)}
    LaunchedEffect(uri) {
        bitmap=withContext(Dispatchers.IO) {runCatching {
            val opts=BitmapFactory.Options().apply{inSampleSize=2}
            if(uri.startsWith("https://")) {
                val c=URL(uri).openConnection().apply{connectTimeout=15000;readTimeout=20000}
                c.getInputStream().use{BitmapFactory.decodeStream(it,null,opts)}
            } else context.contentResolver.openInputStream(Uri.parse(uri))?.use{BitmapFactory.decodeStream(it,null,opts)}
        }.getOrNull()}
        failed=bitmap==null
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        bitmap?.let{Image(it.asImageBitmap(),"Gambar shot",Modifier.fillMaxSize())}
            ?: Text(if(failed) "Gambar tidak tersedia" else "Memuat gambar…",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TimelineVideo(uri: String, offset: Long, playing: Boolean, buffering: (Boolean)->Unit, error: (String)->Unit) {
    val context=LocalContext.current
    val onBuffer by rememberUpdatedState(buffering)
    val onError by rememberUpdatedState(error)
    val player=remember(uri){ExoPlayer.Builder(context).build().apply{
        setMediaItem(MediaItem.fromUri(uri));prepare()
    }}
    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onPlaybackStateChanged(state:Int){onBuffer(state==Player.STATE_BUFFERING)}
            override fun onPlayerError(e:androidx.media3.common.PlaybackException){onError("Video tidak dapat diputar. Periksa media atau koneksi.")}
        }
        player.addListener(listener)
        onDispose{player.removeListener(listener);player.release()}
    }
    AndroidView(factory={PlayerView(it).apply{useController=false;this.player=player}},
        update={it.player=player},modifier=Modifier.fillMaxSize())
    LaunchedEffect(offset,playing) {
        val duration=player.duration
        val desired=if(duration>0) offset.coerceAtMost((duration-1).coerceAtLeast(0)) else offset
        if(kotlin.math.abs(player.currentPosition-desired)>300 || !playing) player.seekTo(desired)
        player.playWhenReady=playing && (player.duration<=0 || offset<player.duration)
    }
}
