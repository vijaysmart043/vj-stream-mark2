package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.streaming.encoder.VideoEncoderState
import com.example.streaming.encoder.VideoEncoderStats
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.ui.theme.StreamYellow

@Composable
fun VideoEncoderOverlay(
    encoderState: VideoEncoderState,
    encoderStats: VideoEncoderStats,
    isTestActive: Boolean,
    onToggleTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stateColor = when (encoderState) {
        VideoEncoderState.ENCODING -> StreamGreen
        VideoEncoderState.INITIALIZING -> StreamYellow
        VideoEncoderState.ERROR -> StreamRed
        else -> Color(0xFF94A3B8)
    }

    Box(
        modifier = modifier
            .background(Color(0xD90A0E17), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x33475569), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .testTag("video_encoder_overlay")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(stateColor, CircleShape)
            )

            // Diagnostics info
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "AVC/H.264",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    if (encoderStats.isHardwareAccelerated) {
                        Box(
                            modifier = Modifier
                                .background(Color(0x3310B981), RoundedCornerShape(2.dp))
                                .border(1.dp, StreamGreen.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                                .padding(horizontal = 3.dp, vertical = 0.5.dp)
                        ) {
                            Text(
                                text = "HW",
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StreamGreen
                            )
                        }
                    }

                    Text(
                        text = encoderState.name,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = stateColor
                    )
                }

                if (encoderState == VideoEncoderState.ENCODING) {
                    Text(
                        text = "${String.format("%.1f", encoderStats.currentFps)} fps • ${encoderStats.bitrateLabel} • ${encoderStats.keyframeCount} keys",
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                } else {
                    Text(
                        text = if (encoderStats.encoderName != "None") encoderStats.encoderName else "H.264 Hardware Encoder",
                        fontSize = 8.5.sp,
                        color = Color(0xFF94A3B8),
                        maxLines = 1
                    )
                }
            }

            // Quick toggle button for encoder test mode
            IconButton(
                onClick = onToggleTest,
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        if (isTestActive) Color(0x33EF4444) else Color(0x22334155),
                        RoundedCornerShape(4.dp)
                    )
                    .testTag("toggle_encoder_test_button")
            ) {
                Icon(
                    imageVector = if (isTestActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isTestActive) "Stop Encoder Test" else "Test Encoder",
                    tint = if (isTestActive) StreamRed else Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}
