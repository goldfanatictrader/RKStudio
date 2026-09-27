package com.rkstudio.cliplocal

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.rkstudio.cliplocal.data.*
import com.rkstudio.cliplocal.ai.*
import org.junit.Assert.*
import com.rkstudio.cliplocal.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class StudioUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = AppDatabase.get(app)

    @Before fun reset() { db.clearAllTables() }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val dir = File(app.filesDir, "qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun seed(recorded: Boolean = false): Long = runBlocking {
        val id = db.projectDao().insert(Project(videoTitle = "Kota yang Sama",
            musicTitle = "Nyore · Master final", audioPathInternal = "", audioDurationMs = 65000,
            sliceDurationSec = 8))
        val audio = FileStore.audioFile(app, id)
        app.assets.open("reference.mp4").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val project = db.projectDao().byId(id)!!
        db.projectDao().update(project.copy(audioPathInternal = audio.absolutePath))
        if (recorded) {
            val video = FileStore.newTakeFile(app, id, 0)
            audio.copyTo(video, overwrite = true)
            db.takeDao().insert(Take(projectId = id, sliceIndex = 0,
                sliceStartMs = 0, sliceEndMs = 8000, videoPathInternal = video.absolutePath))
        }
        id
    }

    @Test fun emptyHomeAndCreateFlow() {
        compose.setContent { StudioTheme { AppNav() } }
        compose.onNodeWithText("Studio kamu").assertIsDisplayed()
        screenshot("01-empty-home")
        compose.onNodeWithText("+  Proyek baru").performClick()
        compose.onNodeWithText("Proyek baru").assertIsDisplayed()
        compose.onNodeWithText("Buat potongan audio").assertIsNotEnabled()
        compose.onNodeWithText("Nama proyek").performTextInput("Belum Padam")
        compose.onNodeWithText("Buat potongan audio").assertIsNotEnabled()
        screenshot("02-import")
        compose.onNodeWithText("Default 8 detik.", substring = true).performScrollTo()
        screenshot("03-import-duration")
    }

    @Test fun projectAndQueue() {
        seed(true)
        compose.setContent { StudioTheme { AppNav() } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Kota yang Sama").fetchSemanticsNodes().isNotEmpty() }
        screenshot("04-projects")
        compose.onNodeWithText("Kota yang Sama").performClick()
        compose.onNodeWithText("1 dari 9").assertIsDisplayed()
        screenshot("05-queue")
        compose.onNodeWithText("Direkam").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Potongan 01"))
        compose.onNodeWithText("Potongan 01").assertIsDisplayed()
        compose.onNodeWithText("Tinjau").assertIsDisplayed()
        screenshot("06-recorded-filter")
    }

    @Test fun reviewAndSyncSheet() {
        val id = seed(true)
        val vm = StudioViewModel(app)
        compose.setContent { StudioTheme { Surface { PreviewScreen(vm, id, 0, {}, {}) } } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Ekspor video referensi").fetchSemanticsNodes().isNotEmpty() }
        screenshot("07-review")
        compose.onNodeWithText("Sinkronisasi audio").performScrollTo().performClick()
        compose.waitForIdle()
        screenshot("08-sync-open")
        compose.onNodeWithText("Terapkan ke seluruh proyek").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+50 ms").performClick()
        compose.onNodeWithText("50 ms").assertExists()
        screenshot("08-sync")
        compose.onNodeWithText("Terapkan ke seluruh proyek").performClick()
        compose.waitUntil(10000) { vm.current?.globalOffsetMs == 50 }
    }

    @Test fun cameraPermissionExplainsPurpose() {
        val id = seed()
        val vm = StudioViewModel(app)
        compose.setContent { StudioTheme { Surface { RecordScreen(vm, id, 0, {}, { _, _ -> }) } } }
        compose.onNodeWithText("Akses kamera diperlukan").assertIsDisplayed()
        compose.onNodeWithText("Izinkan kamera").assertIsDisplayed()
        screenshot("09-camera-permission")
    }
    @Test fun composerMarkdownRendersAsReadableBlocks() {
        compose.setContent {
            StudioTheme { Surface {
                AiMarkdown("## Prompt video\n\nTampilkan **subjek utama** secara konsisten.\n\n- Kamera: dolly-in\n- Durasi: 8 detik\n\n```text\nCinematic music video, 8 seconds\n```")
            } }
        }
        compose.onNodeWithText("Prompt video").assertIsDisplayed()
        compose.onNodeWithText("Tampilkan subjek utama secara konsisten.").assertExists()
        compose.onNodeWithText("Kamera: dolly-in").assertExists()
        compose.onNodeWithText("Cinematic music video, 8 seconds").assertExists()
        compose.onNodeWithText("## Prompt video").assertDoesNotExist()
    }

    @Test fun aiSettingsOffersGoogleAndCustomProviders() {
        val vm = StudioAiViewModel(app)
        compose.setContent { StudioTheme { AiSettingsScreen(vm, {}) } }
        compose.onNodeWithText("Google resmi").assertIsDisplayed()
        compose.onNodeWithText("Custom endpoint").assertIsDisplayed()
        compose.onNodeWithText("Model ID Google").assertIsDisplayed()
        compose.onNodeWithText("Google AI API key").assertIsDisplayed()
        screenshot("11-ai-provider-settings")
        compose.onNodeWithText("Custom compatible endpoint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Base URL, akhiri dengan /v1").assertIsDisplayed()
        compose.onNodeWithText("SimpleNGAT API key baru").performScrollTo().assertIsDisplayed()
    }

    @Test fun providerGateAndLocalKeyEncryption() {
        val store = AiConfigStore(app)
        store.clearGemini()
        store.clearSimple()
        val vm = StudioAiViewModel(app)
        compose.setContent { StudioTheme { AiStudioScreen(vm, null, {}, {}) } }
        compose.onNodeWithText("Konfigurasi analisis").assertIsDisplayed()
        screenshot("10-ai-config-required")
        store.saveGemini("https://example.org/v1", "ag/gemini-3.8-flash", "test-secret-only")
        assertEquals("test-secret-only", store.read().apiKey)
        val raw = app.getSharedPreferences("rkstudio_ai_config", 0).getString("key", "")!!
        assertFalse(raw.contains("test-secret-only"))
        store.clearGemini()
        assertTrue(store.read().apiKey.isBlank())
        store.saveGoogle("gemini-3.8-flash", "google-secret-only", true)
        val googleRaw = app.getSharedPreferences("rkstudio_ai_config", 0).getString("google_key", "")!!
        assertFalse(googleRaw.contains("google-secret-only"))
        assertEquals("google-secret-only", store.read().googleApiKey)
        assertEquals("google", store.read().provider)
        assertTrue(store.read().googleSearch)
        store.selectProvider("custom")
        assertEquals("custom", store.read().provider)
        store.clearGoogle()
        assertTrue(store.read().googleApiKey.isBlank())
    }

}
