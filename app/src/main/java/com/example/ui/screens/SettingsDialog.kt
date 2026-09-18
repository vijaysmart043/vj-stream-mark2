package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.youtube.VideoPreset
import com.example.youtube.VideoPresets
import com.example.youtube.YouTubeStreamConfig
import com.example.youtube.YouTubeStreamValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

@Composable
fun SettingsDialog(
    currentConfig: YouTubeStreamConfig,
    onSaveConfig: (YouTubeStreamConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(currentConfig.serverUrl) }
    var streamKey by remember { mutableStateOf(currentConfig.streamKey) }
    var showStreamKey by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(currentConfig.videoPreset) }
    var micEnabled by remember { mutableStateOf(currentConfig.audioConfig.enabled) }
    var keepScreenAwake by remember { mutableStateOf(currentConfig.keepScreenAwake) }
    var autoStartCamera by remember { mutableStateOf(currentConfig.autoStartCamera) }
    var youtubeValidationError by remember { mutableStateOf<String?>(null) }
    var youtubeSaveMessage by remember { mutableStateOf<String?>(null) }

    // Test connection state
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(ObsidianSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VJStream Studio Settings",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Settings",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Landscape 2-column layout
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Column: YouTube RTMP Setup
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(ObsidianSurfaceVariant, RoundedCornerShape(8.dp))
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "YOUTUBE LIVE CONFIGURATION",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StreamCyan,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Server URL (RTMP/RTMPS)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8)
                        )
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .testTag("server_url_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StreamCyan,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Stream Key (from YouTube Studio)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8)
                        )
                        OutlinedTextField(
                            value = streamKey,
                            onValueChange = { streamKey = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .testTag("stream_key_input"),
                            visualTransformation = if (showStreamKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showStreamKey = !showStreamKey }) {
                                    Icon(
                                        imageVector = if (showStreamKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showStreamKey) "Hide key" else "Show key",
                                        tint = Color.White
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StreamCyan,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // YouTube Stream Configuration Actions: Save and Clear
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val cleanUrl = serverUrl.trim()
                                    val cleanKey = streamKey.trim()
                                    val candidate = currentConfig.copy(
                                        serverUrl = cleanUrl,
                                        streamKey = cleanKey
                                    )
                                    val validation = YouTubeStreamValidator.validate(candidate)
                                    if (!validation.isValid) {
                                        youtubeValidationError = validation.errorMessage
                                        youtubeSaveMessage = null
                                    } else {
                                        youtubeValidationError = null
                                        serverUrl = cleanUrl
                                        streamKey = cleanKey
                                        onSaveConfig(candidate)
                                        youtubeSaveMessage = "Configuration Saved"
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("youtube_save_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StreamCyan,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    serverUrl = YouTubeStreamConfig.DEFAULT_SERVER_URL
                                    streamKey = ""
                                    youtubeValidationError = null
                                    youtubeSaveMessage = "Cleared"
                                    val candidate = currentConfig.copy(
                                        serverUrl = YouTubeStreamConfig.DEFAULT_SERVER_URL,
                                        streamKey = ""
                                    )
                                    onSaveConfig(candidate)
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("youtube_clear_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFEF4444))
                                )
                            ) {
                                Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (youtubeValidationError != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = youtubeValidationError!!,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StreamRed
                            )
                        } else if (youtubeSaveMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = youtubeSaveMessage!!,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StreamGreen
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Test Connection Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isTestingConnection = true
                                    testResult = null
                                    testSuccess = null
                                    scope.launch {
                                        val (success, msg) = testSocketConnectivity(serverUrl)
                                        isTestingConnection = false
                                        testSuccess = success
                                        testResult = msg
                                    }
                                },
                                enabled = !isTestingConnection && serverUrl.isNotBlank(),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("test_connection_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                                )
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Testing...", fontSize = 12.sp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Wifi,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Test Connection", fontSize = 12.sp)
                                }
                            }

                            if (testResult != null) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = testResult!!,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (testSuccess == true) StreamGreen else StreamRed
                                )
                            }
                        }
                    }

                    // Right Column: Video Presets, Audio & App Options
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(ObsidianSurfaceVariant, RoundedCornerShape(8.dp))
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "VIDEO PRESETS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StreamCyan,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Preset chips / cards
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            VideoPresets.ALL.forEach { preset ->
                                val isSelected = preset == selectedPreset
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) Color(0x33EF4444) else Color(0x220F172A),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) StreamRed else Color(0xFF334155),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { selectedPreset = preset }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = preset.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                                        )
                                        Text(
                                            text = "${preset.resolutionLabel} • ${preset.fps} FPS • ${preset.bitrateLabel}",
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    if (isSelected) {
                                        Text(
                                            text = "ACTIVE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StreamRed
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "AUDIO & HARDWARE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StreamCyan,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Capture Microphone", fontSize = 12.sp, color = Color.White)
                            Switch(
                                checked = micEnabled,
                                onCheckedChange = { micEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = StreamGreen
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Keep Screen Awake", fontSize = 12.sp, color = Color.White)
                            Switch(
                                checked = keepScreenAwake,
                                onCheckedChange = { keepScreenAwake = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = StreamCyan
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Auto-Start Camera", fontSize = 12.sp, color = Color.White)
                            Switch(
                                checked = autoStartCamera,
                                onCheckedChange = { autoStartCamera = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = StreamCyan
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Footer Save Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                        )
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            val cleanUrl = serverUrl.trim()
                            val cleanKey = streamKey.trim()
                            if (cleanKey.isNotEmpty() || cleanUrl.isNotEmpty()) {
                                val urlCheck = YouTubeStreamValidator.validateServerUrl(cleanUrl)
                                if (!urlCheck.isValid) {
                                    youtubeValidationError = urlCheck.errorMessage
                                    return@Button
                                }
                            }
                            val newConfig = currentConfig.copy(
                                serverUrl = cleanUrl,
                                streamKey = cleanKey,
                                videoPreset = selectedPreset,
                                audioConfig = currentConfig.audioConfig.copy(enabled = micEnabled),
                                keepScreenAwake = keepScreenAwake,
                                autoStartCamera = autoStartCamera
                            )
                            onSaveConfig(newConfig)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("save_settings_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StreamRed,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Save Settings", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private suspend fun testSocketConnectivity(url: String): Pair<Boolean, String> {
    return withContext(Dispatchers.IO) {
        try {
            val clean = url.trim()
            val isSsl = clean.startsWith("rtmps://", ignoreCase = true)
            val withoutScheme = clean.substringAfter("://")
            val hostPart = withoutScheme.substringBefore("/")
            val host = if (hostPart.contains(":")) hostPart.substringBefore(":") else hostPart
            val defaultPort = if (isSsl) 443 else 1935
            val port = if (hostPart.contains(":")) hostPart.substringAfter(":").toIntOrNull() ?: defaultPort else defaultPort

            if (isSsl) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val socket = factory.createSocket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.close()
            } else {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.close()
            }
            Pair(true, "Server reachable!")
        } catch (e: Exception) {
            Pair(false, "Failed: ${e.message ?: "Unreachable"}")
        }
    }
}
