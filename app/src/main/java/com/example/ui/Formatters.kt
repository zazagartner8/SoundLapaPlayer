package com.example.ui

import java.util.Locale
import kotlin.math.roundToInt

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

fun formatBytesMb(bytes: Long): String {
    if (bytes <= 0L) return "0.0 МБ"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 0.1) {
        val kb = (bytes / 1024.0).roundToInt().coerceAtLeast(1)
        "$kb КБ"
    } else {
        String.format(Locale.getDefault(), "%.1f МБ", mb)
    }
}

fun formatCount(count: Int): String {
    return when {
        count >= 1000 -> String.format(Locale.getDefault(), "%.1fK", count / 1000f)
        else -> count.toString()
    }
}
