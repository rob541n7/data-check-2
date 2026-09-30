package com.umurpaket.datacheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umurpaket.datacheck.data.Level

val Level.color: Color
    get() = when (this) {
        Level.OK -> Color(0xFF2E7D32)
        Level.WARN -> Color(0xFFF9A825)
        Level.CRIT -> Color(0xFFC62828)
        Level.UNKNOWN -> Color(0xFF607D8B)
        Level.NOT_FOUND -> Color(0xFF6A1B9A)
    }

val Level.onColor: Color get() = if (this == Level.WARN) Color(0xFF231A00) else Color.White

val DupColor = Color(0xFF1565C0)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1F3A93),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE3FF),
            onPrimaryContainer = Color(0xFF0B1A4F),
            secondaryContainer = Color(0xFFDDE3FF),
            surface = Color(0xFFFBFBFE),
            background = Color(0xFFF3F4F9),
        ),
        content = content,
    )
}

/** Badge umur, mis. "H+5" dengan warna level. */
@Composable
fun AgeBadge(age: Int?, level: Level, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(level.color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            if (level == Level.NOT_FOUND) "N/A" else age?.let { "H+$it" } ?: "H+?",
            color = level.onColor, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun Mono(text: String, size: Int = 15, weight: FontWeight = FontWeight.Medium, color: Color = Color.Unspecified) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = weight, color = color)
}

/** Baris bar horizontal: label, bar proporsional, angka. */
@Composable
fun BarRow(label: String, value: Int, max: Int, color: Color, sub: Int? = null, subColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), fontSize = 13.sp)
        Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFE6E8F0))) {
            val f = if (max > 0) value.toFloat() / max else 0f
            if (f > 0) Box(Modifier.fillMaxWidth(f.coerceIn(0.01f, 1f)).height(16.dp).background(color.copy(alpha = if (sub != null) 0.35f else 1f)))
            if (sub != null && max > 0 && sub > 0) {
                Box(Modifier.fillMaxWidth((sub.toFloat() / max).coerceIn(0.01f, 1f)).height(16.dp).background(color))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
            Text(com.umurpaket.datacheck.fmt(value), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text("discan ${com.umurpaket.datacheck.fmt(sub)}", fontSize = 10.sp, color = subColor)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
}

val gapSmall = Arrangement.spacedBy(8.dp)
