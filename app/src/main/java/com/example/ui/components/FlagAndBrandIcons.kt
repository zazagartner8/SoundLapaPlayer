package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Crisp multicolor Google "G" vector logo rendered natively via Canvas.
 */
@Composable
fun GoogleGLogoIcon(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = w * 0.20f
        val pad = strokeWidth / 2f + w * 0.04f
        val arcSize = Size(w - pad * 2f, h - pad * 2f)
        val topLeft = Offset(pad, pad)

        // Red top-left / top arc
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 212f,
            sweepAngle = 104f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
        )
        // Yellow left arc
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 148f,
            sweepAngle = 66f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
        )
        // Green bottom arc
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 44f,
            sweepAngle = 106f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
        )
        // Blue right-bottom arc
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -10f,
            sweepAngle = 58f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
        )
        // Blue horizontal bar from center rightwards
        val barTop = h * 0.5f - strokeWidth / 2f
        drawRect(
            color = Color(0xFF4285F4),
            topLeft = Offset(w * 0.50f, barTop),
            size = Size(w - pad - w * 0.50f, strokeWidth),
        )
    }
}

/**
 * Clean iOS-style rounded vector country flag icon (RU, EN/UK, IT).
 */
@Composable
fun CountryFlagIcon(
    languageCode: String,
    modifier: Modifier = Modifier,
    width: Dp = 26.dp,
    height: Dp = 18.dp,
) {
    Canvas(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        val w = size.width
        val h = size.height
        when (languageCode.lowercase()) {
            "it" -> {
                val third = w / 3f
                drawRect(Color(0xFF009246), Offset.Zero, Size(third + 0.5f, h))
                drawRect(Color(0xFFF6F5F0), Offset(third, 0f), Size(third + 0.5f, h))
                drawRect(Color(0xFFCE2B37), Offset(third * 2f, 0f), Size(third + 0.5f, h))
            }
            "en" -> {
                // Crisp Union Jack vector flag
                drawRect(Color(0xFF012169), Offset.Zero, Size(w, h))
                val whiteDiag = Path().apply {
                    moveTo(0f, 0f); lineTo(w * 0.14f, 0f); lineTo(w, h * 0.86f); lineTo(w, h)
                    lineTo(w * 0.86f, h); lineTo(0f, h * 0.14f); close()
                    moveTo(w, 0f); lineTo(w, h * 0.14f); lineTo(w * 0.14f, h); lineTo(0f, h)
                    lineTo(0f, h * 0.86f); lineTo(w * 0.86f, 0f); close()
                }
                drawPath(whiteDiag, Color(0xFFF8F8FA))
                val redDiag = Path().apply {
                    moveTo(0f, 0f); lineTo(w * 0.08f, 0f); lineTo(w, h * 0.92f); lineTo(w, h)
                    lineTo(w * 0.92f, h); lineTo(0f, h * 0.08f); close()
                    moveTo(w, 0f); lineTo(w, h * 0.08f); lineTo(w * 0.08f, h); lineTo(0f, h)
                    lineTo(0f, h * 0.92f); lineTo(w * 0.92f, 0f); close()
                }
                drawPath(redDiag, Color(0xFFC8102E))
                drawRect(Color(0xFFF8F8FA), Offset(0f, h * 0.36f), Size(w, h * 0.28f))
                drawRect(Color(0xFFF8F8FA), Offset(w * 0.39f, 0f), Size(w * 0.22f, h))
                drawRect(Color(0xFFC8102E), Offset(0f, h * 0.42f), Size(w, h * 0.16f))
                drawRect(Color(0xFFC8102E), Offset(w * 0.43f, 0f), Size(w * 0.14f, h))
            }
            else -> {
                // Russian tricolor
                val third = h / 3f
                drawRect(Color(0xFFF8F7F4), Offset.Zero, Size(w, third + 0.5f))
                drawRect(Color(0xFF0039A6), Offset(0f, third), Size(w, third + 0.5f))
                drawRect(Color(0xFFD52B1E), Offset(0f, third * 2f), Size(w, third + 0.5f))
            }
        }
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.18f),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}
