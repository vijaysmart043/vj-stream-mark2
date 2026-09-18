package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.streaming.audio.AudioLevelCalculator
import com.example.streaming.audio.AudioState
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.ui.theme.StreamYellow

/**
 * Real-time microphone audio level meter and status indicator.
 * Fulfills Section 9 & Section 10 of VJStream Phase 3:
 * - Real-time amplitude meter (e.g., "████████░░")
 * - Audio status display (MIC: ON, MIC: OFF, MIC: INITIALIZING, MIC: ERROR)
 */
@Composable
fun AudioLevelMeter(
    audioState: AudioState,
    level: Float, // Normalized RMS 0.0f..1.0f
    peak: Float = level,
    sampleRate: Int = 48000,
    channelCount: Int = 1,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when (audioState) {
            AudioState.CAPTURING, AudioState.READY -> StreamGreen
            AudioState.INITIALIZING -> StreamYellow
            AudioState.MUTED, AudioState.OFF, AudioState.STOPPING -> Color(0xFF94A3B8)
            AudioState.ERROR -> StreamRed
        },
        animationSpec = tween(200),
        label = "micStatusColor"
    )

    // Smooth level animation for high-refresh visual feedback
    val smoothLevel by animateFloatAsState(
        targetValue = if (audioState == AudioState.CAPTURING || audioState == AudioState.READY) level else 0f,
        animationSpec = tween(durationMillis = 60, easing = FastOutSlowInEasing),
        label = "smoothAudioLevel"
    )

    val meterBlocks = AudioLevelCalculator.formatMeterBlocks(
        level = if (audioState == AudioState.CAPTURING || audioState == AudioState.READY) level else 0f,
        totalBlocks = 10
    )

    Row(
        modifier = modifier
            .background(Color(0xE60E121B), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x33475569), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .testTag("audio_level_meter"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Microphone Icon
        Icon(
            imageVector = if (audioState.isMuted || audioState == AudioState.OFF) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = "Microphone Status",
            tint = statusColor,
            modifier = Modifier.width(13.dp).height(13.dp)
        )

        // Status Text (MIC: ON, MIC: OFF, MIC: INITIALIZING, MIC: ERROR)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = audioState.displayLabel,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.testTag("audio_status_label")
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${sampleRate / 1000}kHz ${if (channelCount == 1) "Mono" else "Stereo"}",
                    fontSize = 8.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // ASCII Meter String + Segmented Color Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Exact textual block representation matching Prompt requirement
                Text(
                    text = meterBlocks,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (smoothLevel > 0.85f) StreamRed else if (smoothLevel > 0.6f) StreamYellow else StreamGreen,
                    modifier = Modifier.testTag("audio_meter_blocks")
                )

                // High-polish segmented VU meter track
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    val progressWidth = (smoothLevel * 48).dp
                    Box(
                        modifier = Modifier
                            .width(progressWidth)
                            .fillMaxHeight()
                            .background(
                                when {
                                    smoothLevel > 0.85f -> StreamRed
                                    smoothLevel > 0.60f -> StreamYellow
                                    else -> StreamGreen
                                }
                            )
                    )
                }
            }
        }
    }
}
