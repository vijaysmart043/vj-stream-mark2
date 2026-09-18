package com.example.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.example.camera.CameraManager
import com.example.settings.SettingsRepository
import com.example.streaming.StreamingManager
import com.example.streaming.StreamingService
import com.example.streaming.encoder.VideoEncoderState
import com.example.studio.StudioManager
import com.example.ui.components.AudioLevelMeter
import com.example.ui.components.ControlBar
import com.example.ui.components.StatusBadge
import com.example.ui.components.StudioPanes
import com.example.ui.components.TelemetryHud
import com.example.ui.components.VideoEncoderOverlay
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.youtube.YouTubeStreamValidator

@Composable
fun MainStreamingScreen(
    cameraManager: CameraManager?,
    streamingManager: StreamingManager,
    settingsRepository: SettingsRepository,
    studioManager: StudioManager,
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
    val errorInfo by streamingManager.errorInfoFlow.collectAsState()
    val studioState by studioManager.studioStateFlow.collectAsState()

    val audioState by streamingManager.audioCaptureManager.stateFlow.collectAsState()
    val audioLevel by streamingManager.audioCaptureManager.audioLevelFlow.collectAsState()
    val peakLevel by streamingManager.audioCaptureManager.peakLevelFlow.collectAsState()

    val encoderState by streamingManager.encoderStateFlow.collectAsState()
    val encoderStats by streamingManager.encoderStatsFlow.collectAsState()
    val audioEncoderState by streamingManager.audioEncoderStateFlow.collectAsState()
    var isEncoderTesting by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            studioManager.setPreviewImage(
                context = context,
                uri = uri,
                targetWidth = streamConfig.videoPreset.width,
                targetHeight = streamConfig.videoPreset.height
            )
        }
    }

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
                    .height(36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Brand
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(StreamRed, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VJStream",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0x33475569), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "STUDIO MODE",
                            color = StreamCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
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

            Spacer(modifier = Modifier.height(4.dp))

            // 2. Center Studio Mode: Preview (Left) + Program (Right) Panes
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                StudioPanes(
                    studioState = studioState,
                    cameraManager = cameraManager,
                    hasCameraPermission = hasCameraPermission,
                    onSelectCameraSource = { studioManager.setPreviewCamera() },
                    onSelectImageSource = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onTriggerFade = { studioManager.startFadeTransition() },
                    programOverlayContent = {
                        // Floating Real-Time Microphone Level VU Meter
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
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
                                .padding(6.dp)
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

                        // Telemetry HUD anchored to the bottom of the Program preview
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
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
                )

                // Microphone Permission Warning Banner (if denied / not granted)
                if (!hasAudioPermission) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
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
            }

            Spacer(modifier = Modifier.height(4.dp))

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

        // Error Banner / Debug Diagnostic Card
        AnimatedVisibility(
            visible = errorInfo != null || streamError != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        ) {
            val stage = errorInfo?.stage ?: "RTMP"
            val errorType = errorInfo?.errorType ?: "CONNECTION_FAILED"
            val message = errorInfo?.message ?: streamError ?: "Unknown error"

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xF07F1D1D)),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, StreamRed),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .testTag("stream_error_card")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "STREAM ERROR",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    streamingManager.clearError()
                                    if (hasCameraPermission && hasAudioPermission) {
                                        streamingManager.startStream(streamConfig)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StreamCyan),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("manual_retry_button")
                            ) {
                                Text(
                                    "RETRY MANUALLY",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = { streamingManager.clearError() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x44FFFFFF)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("dismiss_error_button")
                            ) {
                                Text(
                                    "DISMISS",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Stage: $stage ($errorType)",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Reason: $message",
                        color = Color(0xFFF1F5F9),
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
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

