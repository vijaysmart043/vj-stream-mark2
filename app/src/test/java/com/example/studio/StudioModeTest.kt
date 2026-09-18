package com.example.studio

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StudioModeTest {

    // 1. INITIAL STATE TEST
    @Test
    fun testInitialStudioStateDefaults() {
        val studioState = StudioState()
        assertEquals(StudioSource.Camera, studioState.previewSource)
        assertEquals(StudioSource.Camera, studioState.programSource)
        assertFalse(studioState.isTransitioning)
        assertEquals(0f, studioState.transitionProgress, 0.001f)
        assertEquals(TransitionType.FADE, studioState.transitionType)
    }

    // 2. CAMERA AS SOURCE TEST
    @Test
    fun testCameraSourceModel() {
        val cameraSource = StudioSource.Camera
        assertEquals("Camera", cameraSource.name)
        assertEquals(StudioSourceType.CAMERA, cameraSource.type)
        assertEquals("StudioSource.Camera", cameraSource.toString())
    }

    // 3. IMAGE AS SOURCE TEST
    @Test
    fun testImageSourceModel() {
        val testUri = Uri.parse("content://media/external/images/media/100")
        val testBitmap = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
        val imageSource = StudioSource.Image(
            uri = testUri,
            bitmap = testBitmap,
            name = "overlay_banner.png",
            nv21Cache = ByteArray(128 * 72 * 3 / 2)
        )

        assertEquals("overlay_banner.png", imageSource.name)
        assertEquals(StudioSourceType.IMAGE, imageSource.type)
        assertEquals(testUri, imageSource.uri)
        assertNotNull(imageSource.bitmap)
        assertNotNull(imageSource.nv21Cache)
        assertEquals(128 * 72 * 3 / 2, imageSource.nv21Cache!!.size)
    }

    // 4. PREVIEW SOURCE SELECTION TEST
    @Test
    fun testPreviewSourceSelection() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val studioManager = StudioManager(testScope)

        assertEquals(StudioSource.Camera, studioManager.currentState.previewSource)

        val imageSource = StudioSource.Image(
            uri = Uri.parse("file:///dummy.jpg"),
            name = "dummy.jpg"
        )
        studioManager.setPreviewSource(imageSource)

        assertEquals(imageSource, studioManager.currentState.previewSource)
        // Program must remain unchanged
        assertEquals(StudioSource.Camera, studioManager.currentState.programSource)

        // Switch back to Camera
        studioManager.setPreviewCamera()
        assertEquals(StudioSource.Camera, studioManager.currentState.previewSource)
    }

    // 5. PROGRAM SOURCE SELECTION TEST
    @Test
    fun testProgramSourceSelection() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val studioManager = StudioManager(testScope)

        val imageSource = StudioSource.Image(
            uri = Uri.parse("file:///program.png"),
            name = "program.png"
        )
        studioManager.setProgramSource(imageSource)

        assertEquals(imageSource, studioManager.currentState.programSource)
        // Preview remains Camera
        assertEquals(StudioSource.Camera, studioManager.currentState.previewSource)
    }

    // 6. FADE TRANSITION PROGRESS & COMPLETION TEST
    @Test
    fun testFadeTransitionLifecycle() = runTest {
        val studioManager = StudioManager(this)

        val imageSource = StudioSource.Image(
            uri = Uri.parse("file:///intro.png"),
            name = "intro.png"
        )
        studioManager.setPreviewSource(imageSource)
        assertEquals(StudioSource.Camera, studioManager.currentState.programSource)
        assertEquals(imageSource, studioManager.currentState.previewSource)

        var completed = false
        val started = studioManager.startFadeTransition(durationMs = 1000L) {
            completed = true
        }

        assertTrue(started)
        testScheduler.advanceTimeBy(50L)
        testScheduler.runCurrent()
        assertTrue(studioManager.currentState.isTransitioning)
        assertTrue(studioManager.currentState.transitionProgress > 0f)

        // Advance past 1000ms
        testScheduler.advanceTimeBy(1100L)
        testScheduler.runCurrent()

        assertTrue(completed)
        assertFalse(studioManager.currentState.isTransitioning)
        assertEquals(0f, studioManager.currentState.transitionProgress, 0.001f)

        // Swapped sources
        assertEquals(imageSource, studioManager.currentState.programSource)
        assertEquals(StudioSource.Camera, studioManager.currentState.previewSource)
    }

    // 7. TRANSITION LOCK TEST (SAFETY)
    @Test
    fun testTransitionLockPreventsOverlappingFades() = runTest {
        val studioManager = StudioManager(this)

        val firstStarted = studioManager.startFadeTransition(durationMs = 1000L)
        assertTrue(firstStarted)
        testScheduler.advanceTimeBy(100L)
        testScheduler.runCurrent()
        assertTrue(studioManager.currentState.isTransitioning)

        // Attempting to trigger second fade while transitioning MUST return false
        val secondStarted = studioManager.startFadeTransition(durationMs = 1000L)
        assertFalse(secondStarted)

        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()
        assertFalse(studioManager.currentState.isTransitioning)

        // Once completed, new fade can be triggered
        val thirdStarted = studioManager.startFadeTransition(durationMs = 1000L)
        assertTrue(thirdStarted)
    }

    // 8. COMPOSITOR FRAME ROUTING TEST (CAMERA PROGRAM)
    @Test
    fun testCompositorRoutesCameraFramesWhenProgramIsCamera() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val studioManager = StudioManager(testScope)

        val framesReceived = mutableListOf<ByteArray>()
        val compositor = StudioCompositor(studioManager.studioStateFlow) { data, _, _ ->
            framesReceived.add(data.clone())
        }

        val dummyCameraNv21 = ByteArray(128 * 72 * 3 / 2) { 42.toByte() }
        compositor.onCameraFrame(dummyCameraNv21, 128, 72)

        assertEquals(1, framesReceived.size)
        assertArrayEquals(dummyCameraNv21, framesReceived[0])
    }

    // 9. COMPOSITOR FRAME ROUTING TEST (IMAGE PROGRAM)
    @Test
    fun testCompositorRoutesImageFramesWhenProgramIsImage() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val studioManager = StudioManager(testScope)

        val imageNv21 = ByteArray(128 * 72 * 3 / 2) { 99.toByte() }
        val imageSource = StudioSource.Image(
            uri = Uri.parse("file:///slide.png"),
            name = "slide.png",
            nv21Cache = imageNv21
        )
        studioManager.setProgramSource(imageSource)

        val framesReceived = mutableListOf<ByteArray>()
        val compositor = StudioCompositor(studioManager.studioStateFlow) { data, _, _ ->
            framesReceived.add(data.clone())
        }

        val dummyCameraNv21 = ByteArray(128 * 72 * 3 / 2) { 42.toByte() }
        compositor.onCameraFrame(dummyCameraNv21, 128, 72)

        assertEquals(1, framesReceived.size)
        assertArrayEquals(imageNv21, framesReceived[0])
    }

    // 10. IMAGE HELPER BITMAP TO NV21 AND BLEND TESTS
    @Test
    fun testBitmapToNv21AndBlend() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val nv21 = ImageSourceHelper.bitmapToNv21(bitmap, 16, 16)
        assertEquals(16 * 16 * 3 / 2, nv21.size)

        // Test alpha blend: 50% between sourceA (all 0) and sourceB (all 100)
        val srcA = ByteArray(16 * 16 * 3 / 2) { 0 }
        val srcB = ByteArray(16 * 16 * 3 / 2) { 100 }
        val out = ByteArray(16 * 16 * 3 / 2)

        ImageSourceHelper.blendNv21(srcA, srcB, 0.5f, out, 16, 16)
        assertEquals(50.toByte(), out[0])

        // 100% blend -> should match srcB
        ImageSourceHelper.blendNv21(srcA, srcB, 1.0f, out, 16, 16)
        assertEquals(100.toByte(), out[0])

        // 0% blend -> should match srcA
        ImageSourceHelper.blendNv21(srcA, srcB, 0.0f, out, 16, 16)
        assertEquals(0.toByte(), out[0])
    }
}
