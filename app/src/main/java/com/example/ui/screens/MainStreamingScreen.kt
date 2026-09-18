package com.example.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.CameraManager
import com.example.settings.SettingsRepository
import com.example.streaming.StreamingManager
import com.example.streaming.StreamingService
import com.example.streaming.audio.AudioState
import com.example.streaming.encoder.VideoEncoderState
import com.example.ui.components.AudioLevelMeter
import com.example.ui.components.ControlBar
import com.example.ui.components.StatusBadge
import com.example.ui.components.TelemetryHud
import com.example.ui.components.VideoEncoderOverlay
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.youtube.YouTubeStreamConfig
import com.example.youtube.YouTubeStreamValidator

@Composable
fun MainStreamingScreen(
    cameraManager: CameraManager?,
    streamingManager: StreamingManager,
    settingsRepository: SettingsRepository,
    hasCameraPermission: Boolean,
    hasAudioPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestAudioPermission: () -> Unit = onRequestPermissions
) {
    val context = LocalContext.current
    val streamStatus by streamingManager.statusFlow.collectAsState()
    val streamStats by streamingManager.statsFlow.collectAsState()
    val streamConfig by settingsRepository.configFlow.collectAsState()
    val streamError by streamingManager.errorMessageFlow.collectAsState()

    val audioState by streamingManager.audioCaptureManager.stateFlow.collectAsState()
    val audioLevel by streamingManager.audioCaptureManager.audioLevelFlow.collectAsState()
    val peakLevel by streamingManager.audioCaptureManager.peakLevelFlow.collectAsState()
    val audioError by streamingManager.audioCaptureManager.errorMessageFlow.collectAsState()

    val encoderState by streamingManager.encoderStateFlow.collectAsState()
    val encoderStats by streamingManager.encoderStatsFlow.collectAsState()
    val audioEncoderState by streamingManager.audioEncoderStateFlow.collectAsState()
    var isEncoderTesting by remember { mutableStateOf(false) }

    LaunchedEffect(streamStatus) {
        if (streamStatus.isStreaming) {
            isEncoderTesting = false
        }
    }

    LaunchedEffect(encoderState) {
        if (encoderState == VideoEncoderState.IDLE || encoderState == VideoEncoderState.ERROR) {
            if (!streamStatus.isStreaming) {
                isEncoderTesting = false
            }
        }
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var isMicMuted by remember { mutableStateOf(!streamConfig.audioConfig.enabled) }

    // Screen wake lock handling
    DisposableEffect(streamConfig.keepScreenAwake) {
        val window = (context as? Activity)?.window
        if (streamConfig.keepScreenAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Foreground service sync while live
    LaunchedEffect(streamStatus, streamStats.durationFormatted) {
        if (streamStatus.isStreaming) {
            StreamingService.update(context, streamStats.durationFormatted)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("main_streaming_screen")
    ) {
        // Main Landscape Layout
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Brand
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(StreamRed, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VJStream",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "OBS MOBILE",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }

                // Center status message / reconnect notice
                Text(
                    text = streamStats.statusMessage,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                // Right: Status Badge
                StatusBadge(
                    status = streamStatus,
                    durationText = streamStats.durationFormatted
                )
            }

            // 2. Center Camera Viewport with telemetry overlay
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (hasCameraPermission && cameraManager != null) {
                    // CameraX PreviewView
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                cameraManager.setSurfaceProvider(this.surfaceProvider)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("camera_preview_view")
                    )
                } else {
                    // Camera Permission Missing Placeholder
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Camera & Microphone Access Required",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "VJStream needs camera and audio access to broadcast to YouTube Live.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onRequestPermissions,
                            colors = ButtonDefaults.buttonColors(containerColor = StreamRed),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("grant_permissions_button")
                        ) {
                            Text("Grant Permissions", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // OBS Viewfinder Corner Overlays (aesthetic framing)
                ViewfinderCorners()

                // Floating Real-Time Microphone Level VU Meter
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    AudioLevelMeter(
                        audioState = audioState,
                        level = audioLevel,
                        peak = peakLevel,
                        sampleRate = streamingManager.audioCaptureManager.actualSampleRate,
                        channelCount = streamingManager.audioCaptureManager.actualChannelCount
                    )
                }

                // Floating Hardware H.264 Video Encoder Diagnostics Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    VideoEncoderOverlay(
                        encoderState = encoderState,
                        encoderStats = encoderStats,
                        isTestActive = isEncoderTesting,
                        onToggleTest = {
                            if (isEncoderTesting) {
                                streamingManager.stopEncoderTest()
                                isEncoderTesting = false
                            } else {
                                streamingManager.startEncoderTest(streamConfig)
                                isEncoderTesting = true
                            }
                        }
                    )
                }

                // Microphone Permission Warning Banner (if denied / not granted)
                if (!hasAudioPermission) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 40.dp)
                            .background(Color(0xE67F1D1D), RoundedCornerShape(8.dp))
                            .border(1.dp, StreamRed, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MicOff,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Microphone permission is required for live audio.",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Button(
                                onClick = onRequestAudioPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = StreamRed),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("grant_mic_permission_button")
                            ) {
                                Text(
                                    text = "GRANT MICROPHONE PERMISSION",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Telemetry HUD anchored to the bottom of the camera preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    TelemetryHud(
                        stats = streamStats,
                        preset = streamConfig.videoPreset,
                        audioState = audioState,
                        audioLevel = audioLevel,
                        encoderState = encoderState,
                        audioEncoderState = audioEncoderState
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3. Bottom Control Bar
            ControlBar(
                status = streamStatus,
                isFrontCamera = streamConfig.isFrontCamera,
                isMicMuted = isMicMuted,
                isEncoderTesting = isEncoderTesting,
                onSwitchCamera = {
                    cameraManager?.switchCamera()
                    val newFacing = cameraManager?.isFrontCamera ?: false
                    settingsRepository.updateCameraFacing(newFacing)
                },
                onToggleMic = {
                    isMicMuted = !isMicMuted
                    streamingManager.setMicrophoneMuted(isMicMuted)
                    settingsRepository.updateMicEnabled(!isMicMuted)
                },
                onToggleEncoderTest = {
                    if (isEncoderTesting) {
                        streamingManager.stopEncoderTest()
                        isEncoderTesting = false
                    } else {
                        streamingManager.startEncoderTest(streamConfig)
                        isEncoderTesting = true
                    }
                },
                onOpenSettings = {
                    showSettingsDialog = true
                },
                onOpenYouTubeConfig = {
                    showSettingsDialog = true
                },
                onStartStream = {
                    if (isEncoderTesting) {
                        streamingManager.stopEncoderTest()
                        isEncoderTesting = false
                    }
                    val validation = YouTubeStreamValidator.validate(streamConfig)
                    if (!validation.isValid) {
                        val errMsg = validation.errorMessage ?: "Invalid YouTube configuration"
                        streamingManager.setErrorMessage(errMsg)
                        if (streamConfig.streamKey.isBlank()) {
                            showSettingsDialog = true
                        }
                    } else if (!hasCameraPermission || !hasAudioPermission) {
                        onRequestPermissions()
                    } else {
                        streamingManager.startStream(streamConfig)
                        StreamingService.start(context, "00:00")
                    }
                },
                onStopStream = {
                    streamingManager.stopStream()
                    StreamingService.stop(context)
                }
            )
        }

        // Error Banner
        AnimatedVisibility(
            visible = streamError != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
        ) {
            streamError?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .background(Color(0xE67F1D1D), RoundedCornerShape(8.dp))
                        .border(1.dp, StreamRed, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = err,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = { streamingManager.clearError() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Dismiss", fontSize = 10.sp)
                    }
                }
            }
        }

        // Settings Dialog Modal
        if (showSettingsDialog) {
            SettingsDialog(
                currentConfig = streamConfig,
                onSaveConfig = { newConfig ->
                    settingsRepository.updateServerUrl(newConfig.serverUrl)
                    settingsRepository.updateStreamKey(newConfig.streamKey)
                    settingsRepository.updateKeepScreenAwake(newConfig.keepScreenAwake)
                    settingsRepository.updateAutoStartCamera(newConfig.autoStartCamera)
                    settingsRepository.updateMicEnabled(newConfig.audioConfig.enabled)
                    isMicMuted = !newConfig.audioConfig.enabled
                    streamingManager.setMicrophoneMuted(isMicMuted)

                    val presetIndex = com.example.youtube.VideoPresets.ALL.indexOf(newConfig.videoPreset)
                    settingsRepository.updateVideoPreset(presetIndex)
                    cameraManager?.setTargetResolution(
                        newConfig.videoPreset.width,
                        newConfig.videoPreset.height
                    )
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}

@Composable
private fun ViewfinderCorners() {
    // Elegant OBS Studio camera target guide
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Left
        Box(
            modifier = Modifier
                .size(14.dp)
                .align(Alignment.TopStart)
                .border(width = 2.dp, color = Color(0x66FFFFFF), shape = RoundedCornerShape(topStart = 4.dp))
        )
        // Top Right
        Box(
            modifier = Modifier
                .size(14.dp)
                .align(Alignment.TopEnd)
                .border(width = 2.dp, color = Color(0x66FFFFFF), shape = RoundedCornerShape(topEnd = 4.dp))
        )
        // Bottom Left
        Box(
            modifier = Modifier
                .size(14.dp)
                .align(Alignment.BottomStart)
                .border(width = 2.dp, color = Color(0x66FFFFFF), shape = RoundedCornerShape(bottomStart = 4.dp))
        )
        // Bottom Right
        Box(
            modifier = Modifier
                .size(14.dp)
                .align(Alignment.BottomEnd)
                .border(width = 2.dp, color = Color(0x66FFFFFF), shape = RoundedCornerShape(bottomEnd = 4.dp))
        )
    }
}
