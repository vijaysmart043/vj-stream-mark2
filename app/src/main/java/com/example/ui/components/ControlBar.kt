package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.streaming.StreamStatus
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.ui.theme.StreamRedDark

@Composable
fun ControlBar(
    status: StreamStatus,
    isFrontCamera: Boolean,
    isMicMuted: Boolean,
    isEncoderTesting: Boolean = false,
    onSwitchCamera: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleEncoderTest: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenYouTubeConfig: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE60E121B), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x33475569), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side quick action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera switch button
            OutlinedButton(
                onClick = onSwitchCamera,
                modifier = Modifier
                    .height(42.dp)
                    .testTag("switch_camera_button"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x221E293B),
                    contentColor = Color.White
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isFrontCamera) "Front" else "Rear",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Mic mute/unmute button
            OutlinedButton(
                onClick = onToggleMic,
                modifier = Modifier
                    .height(42.dp)
                    .testTag("toggle_mic_button"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isMicMuted) Color(0x33EF4444) else Color(0x221E293B),
                    contentColor = if (isMicMuted) StreamRed else StreamGreen
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (isMicMuted) StreamRed.copy(alpha = 0.5f) else Color(0xFF334155)
                    )
                )
            ) {
                Icon(
                    imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Toggle Mic",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isMicMuted) "MIC OFF" else "MIC ON",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Standalone H.264 Encoder Test button (Phase 4 requirement)
            if (status != StreamStatus.LIVE && status != StreamStatus.CONNECTING) {
                OutlinedButton(
                    onClick = onToggleEncoderTest,
                    modifier = Modifier
                        .height(42.dp)
                        .testTag("toggle_encoder_test_bar_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isEncoderTesting) Color(0x3310B981) else Color(0x221E293B),
                        contentColor = if (isEncoderTesting) StreamGreen else Color.White
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (isEncoderTesting) StreamGreen.copy(alpha = 0.6f) else Color(0xFF334155)
                        )
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Hardware,
                        contentDescription = "Test H.264 Video Encoder",
                        modifier = Modifier.size(18.dp),
                        tint = if (isEncoderTesting) StreamGreen else StreamCyan
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isEncoderTesting) "ENC ON" else "ENC TEST",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // YouTube Setup quick button
            OutlinedButton(
                onClick = onOpenYouTubeConfig,
                modifier = Modifier
                    .height(42.dp)
                    .testTag("youtube_setup_button"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x221E293B),
                    contentColor = Color.White
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                )
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = "YouTube Setup",
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFFFF4E45)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "YouTube",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Settings button
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .height(42.dp)
                    .testTag("settings_button"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x221E293B),
                    contentColor = Color.White
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Settings",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Right side Main Start/Stop action
        when {
            status == StreamStatus.LIVE || status == StreamStatus.RECONNECTING -> {
                Button(
                    onClick = onStopStream,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("stop_stream_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StreamRedDark,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Streaming",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STOP STREAM",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
            status == StreamStatus.CONNECTING || status == StreamStatus.INITIALIZING || status == StreamStatus.STOPPING -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("connecting_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = Color(0xFF334155),
                        disabledContentColor = Color.White
                    )
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (status == StreamStatus.STOPPING) "STOPPING..." else "CONNECTING...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
            else -> {
                Button(
                    onClick = onStartStream,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("start_stream_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StreamRed,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start Stream",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "START STREAM",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}
