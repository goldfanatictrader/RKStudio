package com.rkstudio.cliplocal

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.rkstudio.cliplocal.ui.*
import com.rkstudio.cliplocal.ai.StudioAiViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class TimelineTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    @Before fun reset() {
        File(app.filesDir,"timeline-projects-v1.json").delete()
        File(app.filesDir,"timeline-projects-v1.json.bak").delete()
    }
    @Test fun versionsSurviveRestartAndKeepShotIdentity() {
        val vm=TimelineViewModel(app)
        val id=vm.create("UGC serum","9:16","Buka kemasan.\n\nTunjukkan tekstur.")
        val original=vm.projects.single().shots.first()
        vm.script(id,original.id,"Coba ide lain.",5)
        val revised=vm.projects.single().shots.first()
        assertEquals(original.id,revised.id)
        assertEquals(2,revised.versions.size)
        assertEquals(5000L,revised.durationMs)
        vm.activate(id,original.id,original.activeId)
        val restored=TimelineViewModel(app).projects.single()
        assertEquals(original.activeId,restored.shots.first().activeId)
        assertEquals(2,restored.shots.first().versions.size)
        assertEquals("Buka kemasan.",restored.shots.first().active.text)
    }
    @Test fun mixedTimelineBoundariesAndReorder() {
        val vm=TimelineViewModel(app)
        val id=vm.create("Test","16:9","A\n\nB\n\nC")
        val shots=vm.projects.single().shots
        vm.change(id,shots[1].id) {s->
            val v=ShotVersion(kind="image",uri="https://example.com/image.jpg",text="B")
            s.copy(versions=s.versions+v,activeId=v.id)
        }
        var p=vm.projects.single()
        assertEquals(0,p.indexAt(7999));assertEquals(1,p.indexAt(8000))
        assertEquals(2,p.indexAt(24000))
        vm.move(id,shots[1].id,-1)
        p=vm.projects.single()
        assertEquals(shots[1].id,p.shots.first().id)
        assertEquals("image",p.shots.first().active.kind)
        assertEquals(24000L,p.durationMs)
    }
    @Test fun scriptPlaybackAndContextActions() {
        val vm=TimelineViewModel(app)
        val id=vm.create("Iklan kopi","9:16","Tuang kopi perlahan.\n\nTutup dengan produk.")
        compose.setContent {StudioTheme {TimelineEditor(vm,StudioAiViewModel(app),id,{},{})}}
        compose.waitForIdle()
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"timeline-editor.png")
            put(MediaStore.Downloads.MIME_TYPE,"image/png")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/RKStudio-QA")
        }
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        app.contentResolver.openOutputStream(uri)!!.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        compose.onNodeWithText("Play").assertIsDisplayed().performClick()
        compose.onNodeWithText("Jeda").assertIsDisplayed().performClick()
        compose.onNodeWithText("Generate gambar").assertIsDisplayed().performClick()
        compose.onNodeWithText("Buka pengaturan").performScrollTo().assertIsDisplayed()
    }
}
