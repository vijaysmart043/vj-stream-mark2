package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.streaming.NetworkHealth
import com.example.streaming.StreamStatistics
import com.example.streaming.audio.AudioLevelCalculator
import com.example.streaming.audio.AudioState
import com.example.streaming.encoder.AudioEncoderState
import com.example.streaming.encoder.VideoEncoderState
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.ui.theme.StreamYellow
import com.example.youtube.VideoPreset

@Composable
fun TelemetryHud(
    stats: StreamStatistics,
    preset: VideoPreset,
    audioState: AudioState = AudioState.OFF,
    audioLevel: Float = 0f,
    encoderState: VideoEncoderState = VideoEncoderState.IDLE,
    audioEncoderState: AudioEncoderState = AudioEncoderState.IDLE,
    modifier: Modifier = Modifier
) {
    val netColor = when (stats.networkStatus) {
        NetworkHealth.EXCELLENT, NetworkHealth.GOOD -> StreamGreen
        NetworkHealth.FAIR -> StreamYellow
        NetworkHealth.POOR -> StreamRed
        NetworkHealth.OFFLINE -> Color(0xFF94A3B8)
    }

    val audioColor = when (audioState) {
        AudioState.CAPTURING, AudioState.READY -> StreamGreen
        AudioState.INITIALIZING -> StreamYellow
        AudioState.MUTED, AudioState.OFF, AudioState.STOPPING -> Color(0xFF94A3B8)
        AudioState.ERROR -> StreamRed
    }

    val audioValueText = when (audioState) {
        AudioState.CAPTURING, AudioState.READY -> AudioLevelCalculator.formatMeterBlocks(audioLevel, 6)
        AudioState.MUTED -> "MUTED"
        AudioState.OFF -> "OFF"
        AudioState.INITIALIZING -> "INIT"
        AudioState.ERROR -> "ERROR"
        AudioState.STOPPING -> "STOPPING"
    }

    val encoderColor = when (encoderState) {
        VideoEncoderState.ENCODING -> StreamGreen
        VideoEncoderState.INITIALIZING -> StreamYellow
        VideoEncoderState.ERROR -> StreamRed
        VideoEncoderState.READY, VideoEncoderState.IDLE, VideoEncoderState.STOPPING -> Color(0xFF94A3B8)
    }

    // Display real FPS or "--" when encoder is inactive
    val fpsDisplay = if (stats.fps > 0.0) {
        String.format("%.0f", stats.fps)
    } else {
        "--"
    }

    // Display real Bitrate or "--" when encoder is inactive
    val bitrateDisplay = if (stats.videoBitrateKbps > 0 || stats.audioBitrateKbps > 0) {
        stats.totalBitrateLabel
    } else {
        "--"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC0E121B), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x33475569), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TelemetryItem(
            label = "FPS",
            value = fpsDisplay,
            valueColor = StreamCyan
        )

        TelemetryItem(
            label = "BITRATE",
            value = bitrateDisplay,
            valueColor = Color.White
        )

        TelemetryItem(
            label = "RES",
            value = preset.resolutionLabel,
            valueColor = Color.White
        )

        TelemetryItem(
            label = "H.264",
            value = when (encoderState) {
                VideoEncoderState.ENCODING -> "ACTIVE"
                VideoEncoderState.INITIALIZING -> "INIT"
                VideoEncoderState.ERROR -> "ERROR"
                else -> if (stats.isEncoderActive) "ACTIVE" else "OFF"
            },
            valueColor = if (stats.isEncoderActive) StreamGreen else encoderColor
        )

        TelemetryItem(
            label = "AAC",
            value = when (audioEncoderState) {
                AudioEncoderState.ENCODING -> "ENC"
                AudioEncoderState.READY -> "READY"
                AudioEncoderState.INITIALIZING -> "INIT"
                AudioEncoderState.ERROR -> "ERR"
                AudioEncoderState.STOPPING -> "STOP"
                AudioEncoderState.IDLE -> "OFF"
            },
            valueColor = when (audioEncoderState) {
                AudioEncoderState.ENCODING, AudioEncoderState.READY -> StreamGreen
                AudioEncoderState.INITIALIZING -> StreamYellow
                AudioEncoderState.ERROR -> StreamRed
                else -> Color(0xFF94A3B8)
            }
        )

        TelemetryItem(
            label = "MIC",
            value = audioValueText,
            valueColor = audioColor
        )

        TelemetryItem(
            label = "DATA",
            value = if (stats.totalBytesSent > 0) stats.formattedDataSent else "--",
            valueColor = Color.White
        )

        TelemetryItem(
            label = "PACKETS",
            value = if (stats.videoPacketsSent > 0 || stats.audioPacketsSent > 0) {
                "V:${stats.videoPacketsSent} A:${stats.audioPacketsSent}"
            } else {
                "--"
            },
            valueColor = StreamCyan
        )

        TelemetryItem(
            label = "DROP",
            value = "${stats.droppedFrames}",
            valueColor = if (stats.droppedFrames > 10) StreamYellow else Color.White
        )

        TelemetryItem(
            label = "NET",
            value = stats.networkStatus.name,
            valueColor = netColor
        )
    }
}

@Composable
fun TelemetryItem(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF94A3B8),
            letterSpacing = 0.4.sp
        )
        Text(
            text = value,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}
