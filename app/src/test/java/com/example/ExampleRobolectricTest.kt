package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.settings.SettingsRepository
import com.example.streaming.StreamStatistics
import com.example.streaming.rtmp.Amf0
import com.example.youtube.VideoPresets
import com.example.youtube.YouTubeStreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("VJStream", appName)
  }

  @Test
  fun `settings repository stores and loads config`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = SettingsRepository(context)

    repo.updateServerUrl("rtmps://a.rtmp.youtube.com/live2")
    repo.updateStreamKey("test-live-key-1234")
    repo.updateVideoPreset(1) // MEDIUM
    repo.updateMicEnabled(true)

    val config = repo.getConfig()
    assertEquals("rtmps://a.rtmp.youtube.com/live2", config.serverUrl)
    assertEquals("test-live-key-1234", config.streamKey)
    assertTrue(config.isValidForStreaming)
    assertEquals(1280, config.videoPreset.width)
    assertEquals(720, config.videoPreset.height)
    assertEquals(30, config.videoPreset.fps)
  }

  @Test
  fun `amf0 writes string and number correctly`() {
    val baos = ByteArrayOutputStream()
    Amf0.writeString(baos, "connect")
    Amf0.writeNumber(baos, 1.0)
    val bytes = baos.toByteArray()

    // First byte is AMF0 type string (0x02)
    assertEquals(0x02.toByte(), bytes[0])
    // Next 2 bytes: string length (7)
    assertEquals(0.toByte(), bytes[1])
    assertEquals(7.toByte(), bytes[2])
    // Number type 0x00 follows at index 10
    assertEquals(0x00.toByte(), bytes[10])
  }

  @Test
  fun `video presets provide valid configurations`() {
    assertEquals(4, VideoPresets.ALL.size)
    val low = VideoPresets.LOW
    assertEquals(1280, low.width)
    assertEquals(720, low.height)
    assertEquals(2500, low.bitrateKbps)

    val high = VideoPresets.HIGH
    assertEquals(1920, high.width)
    assertEquals(1080, high.height)
    assertEquals(6000, high.bitrateKbps)
  }

  @Test
  fun `stream statistics formatting works correctly`() {
    val stats = StreamStatistics(
      durationSeconds = 125,
      fps = 29.8,
      videoBitrateKbps = 3800,
      audioBitrateKbps = 128
    )

    assertEquals("02:05", stats.durationFormatted)
    assertEquals("3.9 Mbps", stats.totalBitrateLabel)

    val hourStats = StreamStatistics(durationSeconds = 3661)
    assertEquals("01:01:01", hourStats.durationFormatted)
  }
}

