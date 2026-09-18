package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.streaming.StreamStatus
import com.example.ui.theme.StreamGreen
import com.example.ui.theme.StreamRed
import com.example.ui.theme.StreamYellow

@Composable
fun StatusBadge(
    status: StreamStatus,
    durationText: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AlphaPulsing"
    )

    val (badgeBg, badgeBorder, dotColor, labelText) = when (status) {
        StreamStatus.LIVE -> Quad(
            Color(0x33EF4444),
            StreamRed,
            StreamRed,
            "LIVE"
        )
        StreamStatus.PUBLISHING -> Quad(
            Color(0x3310B981),
            StreamGreen,
            StreamGreen,
            "PUBLISHING"
        )
        StreamStatus.CONNECTED -> Quad(
            Color(0x3338BDF8),
            Color(0xFF38BDF8),
            Color(0xFF38BDF8),
            "CONNECTED"
        )
        StreamStatus.CONNECTING -> Quad(
            Color(0x33EAB308),
            StreamYellow,
            StreamYellow,
            "CONNECTING"
        )
        StreamStatus.RECONNECTING -> Quad(
            Color(0x33EAB308),
            StreamYellow,
            StreamYellow,
            "RECONNECTING"
        )
        StreamStatus.INITIALIZING -> Quad(
            Color(0x3338BDF8),
            Color(0xFF38BDF8),
            Color(0xFF38BDF8),
            "INIT"
        )
        StreamStatus.STOPPING -> Quad(
            Color(0x33EF4444),
            StreamRed,
            StreamRed,
            "STOPPING"
        )
        StreamStatus.STOPPED -> Quad(
            Color(0x2264748B),
            Color(0xFF475569),
            Color(0xFF94A3B8),
            "STOPPED"
        )
        StreamStatus.ERROR -> Quad(
            Color(0x33EF4444),
            StreamRed,
            StreamRed,
            "ERROR"
        )
        StreamStatus.OFFLINE -> Quad(
            Color(0x2264748B),
            Color(0xFF475569),
            Color(0xFF94A3B8),
            "OFFLINE"
        )
    }

    Row(
        modifier = modifier
            .background(badgeBg, RoundedCornerShape(6.dp))
            .border(1.dp, badgeBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(if (status.isStreaming) alphaAnim else 1.0f)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = labelText,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.5.sp,
            letterSpacing = 0.6.sp
        )

        if (status == StreamStatus.LIVE && durationText.isNotBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = durationText,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.5.sp
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
