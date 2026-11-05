package io.github.l0gic0l.roulade.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.l0gic0l.roulade.analysis.AnalysisSettings
import io.github.l0gic0l.roulade.dsp.Spectrum
import kotlin.math.log10
import kotlin.math.max

/**
 * Motion spectrum (log power vs. frequency) with the current band shaded and peaks marked.
 * Tapping the chart reports the tapped frequency.
 */
@Composable
fun SpectrumChart(
    spectrum: Spectrum,
    lowHz: Double,
    highHz: Double,
    maxHz: Double,
    onTapFrequency: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp)
    val peakStyle = TextStyle(color = colors.tertiary, fontSize = 11.sp)
    val bottomPad = 18.dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(maxHz) {
                detectTapGestures { p -> onTapFrequency((p.x / size.width * maxHz).coerceIn(0.0, maxHz)) }
            },
    ) {
        val plotH = size.height - bottomPad.toPx()
        fun x(hz: Double) = (hz / maxHz * size.width).toFloat()
        fun y(power: Float): Float {
            val db = log10(max(power, 1e-5f)) // -5 .. 0
            return plotH * (-db / 5f)
        }

        // Band
        drawRect(
            color = colors.primary.copy(alpha = 0.14f),
            topLeft = Offset(x(lowHz), 0f),
            size = Size(max(2f, x(highHz) - x(lowHz)), plotH),
        )
        // Grid: one line per decade of power.
        for (d in 0..5) {
            val gy = plotH * d / 5f
            drawLine(colors.outlineVariant, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        // Curve
        val path = Path()
        var started = false
        for (i in spectrum.frequencies.indices) {
            val f = spectrum.frequencies[i].toDouble()
            if (f > maxHz) break
            val px = x(f)
            val py = y(spectrum.power[i])
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        }
        drawPath(path, colors.primary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        // Peaks
        for (p in spectrum.peaks) {
            val idx = spectrum.frequencies.indexOfFirst { it >= p }.coerceAtLeast(0)
            val c = Offset(x(p.toDouble()), y(spectrum.power[idx]))
            drawCircle(colors.tertiary, radius = 4.dp.toPx(), center = c)
            val label = measurer.measure("${AnalysisSettings.fmt(p.toDouble())} Hz", peakStyle)
            drawText(
                label,
                topLeft = Offset(
                    (c.x - label.size.width / 2).coerceIn(0f, size.width - label.size.width),
                    max(0f, c.y - label.size.height - 4.dp.toPx()),
                ),
            )
        }
        // Frequency axis labels
        for (k in 0..4) {
            val hz = maxHz * k / 4
            val label = measurer.measure("${AnalysisSettings.fmt(Math.round(hz * 10) / 10.0)}", labelStyle)
            val lx = (x(hz) - label.size.width / 2).coerceIn(0f, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, plotH + 2.dp.toPx()))
        }
    }
}
