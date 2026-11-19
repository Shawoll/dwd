package app.detectorlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.detectorlink.model.ThreatLevel

// Same palette as the detector's web UI, so switching to the WebView doesn't jar.
private val Scheme = darkColorScheme(
    primary = Color(0xFF5FD3A6),
    onPrimary = Color(0xFF00281A),
    background = Color(0xFF0B0F0E),
    surface = Color(0xFF121816),
    surfaceVariant = Color(0xFF1A2320),
    onBackground = Color(0xFFD7E3DE),
    onSurface = Color(0xFFD7E3DE),
    onSurfaceVariant = Color(0xFF7D8F88),
    outline = Color(0xFF22302B),
    error = Color(0xFFFF5D52),
)

@Composable
fun DetectorTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

fun ThreatLevel.color(): Color = when (this) {
    ThreatLevel.HIGH -> Color(0xFFFF5D52)
    ThreatLevel.MEDIUM -> Color(0xFFF2B84B)
    ThreatLevel.LOW -> Color(0xFF5FD3A6)
    ThreatLevel.UNKNOWN -> Color(0xFF7D8F88)
}
