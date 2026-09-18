package com.example.ui.components

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.CameraManager
import com.example.studio.StudioSource
import com.example.studio.StudioState
import com.example.ui.theme.StreamCyan
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed

@Composable
fun StudioPanes(
    studioState: StudioState,
    cameraManager: CameraManager?,
    hasCameraPermission: Boolean,
    onSelectCameraSource: () -> Unit,
    onSelectImageSource: () -> Unit,
    onTriggerFade: () -> Unit,
    modifier: Modifier = Modifier,
    programOverlayContent: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. LEFT: PREVIEW PANE
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Preview Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(Color(0xFF0F172A), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(StreamCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, StreamCyan, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "PREVIEW",
                            color = StreamCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (studioState.previewSource) {
                            is StudioSource.Camera -> "Camera"
                            is StudioSource.Image -> (studioState.previewSource as StudioSource.Image).name
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Text(
                    text = "QUEUED",
                    color = Color(0xFF64748B),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Preview Viewport Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .border(1.dp, StreamCyan.copy(alpha = 0.4f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(Color(0xFF05080F))
                    .testTag("studio_preview_pane"),
                contentAlignment = Alignment.Center
            ) {
                when (val src = studioState.previewSource) {
                    is StudioSource.Camera -> {
                        if (hasCameraPermission && cameraManager != null) {
                            if (studioState.programSource !is StudioSource.Camera) {
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
                                        .testTag("preview_camera_view")
                                )
                            } else {
                                // Program already holds the primary CameraX PreviewView
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = StreamCyan,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "LIVE CAMERA READY",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Active on Program",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Camera Permission Required",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    is StudioSource.Image -> {
                        if (src.bitmap != null) {
                            Image(
                                bitmap = src.bitmap.asImageBitmap(),
                                contentDescription = "Preview Image Source",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("preview_image_view")
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "No Image Loaded",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Decorative Viewfinder Corners
                ViewfinderCorners(tint = StreamCyan.copy(alpha = 0.5f))
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Preview Source Selection Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Camera Source Button
                OutlinedButton(
                    onClick = onSelectCameraSource,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("select_camera_source_button"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (studioState.previewSource is StudioSource.Camera) StreamCyan.copy(alpha = 0.2f) else Color(0x221E293B),
                        contentColor = if (studioState.previewSource is StudioSource.Camera) StreamCyan else Color.White
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (studioState.previewSource is StudioSource.Camera) StreamCyan else Color(0xFF334155)
                        )
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Select Camera",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Camera", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Gallery Image Button
                OutlinedButton(
                    onClick = onSelectImageSource,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("select_image_source_button"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (studioState.previewSource is StudioSource.Image) StreamCyan.copy(alpha = 0.2f) else Color(0x221E293B),
                        contentColor = if (studioState.previewSource is StudioSource.Image) StreamCyan else Color.White
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (studioState.previewSource is StudioSource.Image) StreamCyan else Color(0xFF334155)
                        )
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Select Image from Gallery",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Gallery", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 2. CENTER: FADE TRANSITION CONTROLS
        Column(
            modifier = Modifier
                .width(88.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val isTransitioning = studioState.isTransitioning

            Button(
                onClick = onTriggerFade,
                enabled = !isTransitioning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("fade_transition_button"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTransitioning) Color(0xFF334155) else StreamRed,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF334155),
                    disabledContentColor = Color(0xFF94A3B8)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Fade Transition",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isTransitioning) "FADING" else "FADE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "1000ms",
                color = Color(0xFF64748B),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )

            if (isTransitioning) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { studioState.transitionProgress },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = StreamRed,
                    trackColor = Color(0xFF1E293B)
                )
            }
        }

        // 3. RIGHT: PROGRAM PANE
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Program Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(Color(0xFF0F172A), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(StreamRed.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, StreamRed, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "PROGRAM",
                            color = StreamRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (studioState.programSource) {
                            is StudioSource.Camera -> "Camera"
                            is StudioSource.Image -> (studioState.programSource as StudioSource.Image).name
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(StreamRed, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ON AIR",
                        color = StreamRed,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Program Viewport Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .border(1.dp, StreamRed.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(Color(0xFF05080F))
                    .testTag("studio_program_pane"),
                contentAlignment = Alignment.Center
            ) {
                // Program Outgoing Source View
                val outgoingAlpha = if (studioState.isTransitioning) (1f - studioState.transitionProgress) else 1f
                when (val program = studioState.programSource) {
                    is StudioSource.Camera -> {
                        if (hasCameraPermission && cameraManager != null) {
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
                                    .alpha(outgoingAlpha)
                                    .testTag("camera_preview_view")
                            )
                        } else {
                            Text(
                                text = "Camera Permission Required",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    is StudioSource.Image -> {
                        if (program.bitmap != null) {
                            Image(
                                bitmap = program.bitmap.asImageBitmap(),
                                contentDescription = "Program Image Source",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(outgoingAlpha)
                                    .testTag("program_image_view")
                            )
                        }
                    }
                }

                // Smooth Crossfade Overlay during active transition
                if (studioState.isTransitioning) {
                    val incoming = studioState.previewSource
                    if (incoming is StudioSource.Image && incoming.bitmap != null) {
                        Image(
                            bitmap = incoming.bitmap.asImageBitmap(),
                            contentDescription = "Transitioning Incoming Image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(studioState.transitionProgress)
                        )
                    } else if (incoming is StudioSource.Camera) {
                        // Fade in camera placeholder / live feed representation
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 1f - studioState.transitionProgress))
                        )
                    }
                }

                // Decorative Viewfinder Corners
                ViewfinderCorners(tint = StreamRed.copy(alpha = 0.6f))

                // Custom Overlays (Meters, Telemetry HUD, Diagnostics)
                programOverlayContent()
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Program Status Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Color(0x221E293B), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Output Encoder Source",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (studioState.isTransitioning) "TRANSITIONING..." else "ACTIVE",
                    color = if (studioState.isTransitioning) StreamCyan else StreamGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ViewfinderCorners(tint: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.TopStart)
                .border(width = 1.5.dp, color = tint, shape = RoundedCornerShape(topStart = 2.dp))
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.TopEnd)
                .border(width = 1.5.dp, color = tint, shape = RoundedCornerShape(topEnd = 2.dp))
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.BottomStart)
                .border(width = 1.5.dp, color = tint, shape = RoundedCornerShape(bottomStart = 2.dp))
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.BottomEnd)
                .border(width = 1.5.dp, color = tint, shape = RoundedCornerShape(bottomEnd = 2.dp))
        )
    }
}
