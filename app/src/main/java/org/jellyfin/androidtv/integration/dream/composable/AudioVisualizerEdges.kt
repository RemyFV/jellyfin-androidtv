package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import org.jellyfin.playback.media3.exoplayer.AudioSpectrum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real-time audio spectrum overlay for the now-playing screensaver, driven by [AudioSpectrum].
 *
 * @param radial draw a mirrored arc on each side instead of straight edge bars.
 * @param centerOut mirror the spectrum around the middle (bass in the center) instead of running
 * top-to-bottom / along the arc.
 * @param color bar colour.
 */
@Composable
fun AudioVisualizer(
	modifier: Modifier = Modifier,
	radial: Boolean = false,
	centerOut: Boolean = false,
	color: Color = Color.White,
	topInset: Boolean = true,
) {
	val display = remember { mutableStateOf(FloatArray(AudioSpectrum.BAND_COUNT)) }

	LaunchedEffect(Unit) {
		val current = FloatArray(AudioSpectrum.BAND_COUNT)
		while (true) {
			withFrameNanos { }
			val target = AudioSpectrum.bands.value
			for (i in current.indices) {
				val t = target.getOrElse(i) { 0f }
				current[i] = if (t > current[i]) t else current[i] * 0.82f + t * 0.18f
			}
			display.value = current.copyOf()
		}
	}

	val bars = display.value

	Canvas(modifier = modifier.fillMaxSize()) {
		val n = bars.size
		if (n == 0) return@Canvas

		// Map a slot position to a band index (center-out mirrors low frequencies to the middle).
		fun bandFor(slot: Int, slots: Int): Int = if (centerOut) {
			val d = abs(slot - (slots - 1) / 2f) / ((slots - 1) / 2f)
			(d * (n - 1)).toInt().coerceIn(0, n - 1)
		} else {
			(slot.toFloat() / (slots - 1) * (n - 1)).toInt().coerceIn(0, n - 1)
		}

		if (radial) {
			// Two mirrored arcs in the side wings, bars radiating outward.
			val cx = size.width / 2f
			val cy = size.height / 2f
			val r0 = size.height * 0.48f
			val maxLen = (size.width / 2f - r0).coerceAtLeast(size.height * 0.12f) * 0.9f
			val thickness = (size.height / (n * 1.7f)).coerceAtLeast(2f)
			val halfArc = (62.0 * PI / 180.0).toFloat()

			for (i in 0 until n) {
				val frac = if (n == 1) 0.5f else i.toFloat() / (n - 1)
				val angle = -halfArc + frac * (2f * halfArc)
				val v = bars[bandFor(i, n)]
				if (v <= 0.01f) continue
				val len = v * maxLen
				val ca = cos(angle)
				val sa = sin(angle)
				val barColor = color.copy(alpha = 0.25f + 0.55f * v)

				// Right arc.
				drawLine(
					color = barColor,
					start = Offset(cx + ca * r0, cy + sa * r0),
					end = Offset(cx + ca * (r0 + len), cy + sa * (r0 + len)),
					strokeWidth = thickness,
					cap = StrokeCap.Round,
				)
				// Left arc (mirrored across the vertical axis).
				drawLine(
					color = barColor,
					start = Offset(cx - ca * r0, cy + sa * r0),
					end = Offset(cx - ca * (r0 + len), cy + sa * (r0 + len)),
					strokeWidth = thickness,
					cap = StrokeCap.Round,
				)
			}
		} else {
			// Straight bars hugging each edge. Top inset keeps them clear of the top-right clock
			// (not needed when the clock is centered by the focused layout).
			val inset = if (topInset) size.height * 0.10f else 0f
			val usableH = size.height - inset
			val slot = usableH / n
			val barHeight = slot * 0.55f
			val maxLen = size.width * 0.16f
			val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

			for (i in 0 until n) {
				val v = bars[bandFor(i, n)]
				if (v <= 0.01f) continue
				val len = v * maxLen
				val y = inset + i * slot + (slot - barHeight) / 2f
				val barColor = color.copy(alpha = 0.25f + 0.55f * v)

				drawRoundRect(barColor, Offset(0f, y), Size(len, barHeight), radius)
				drawRoundRect(barColor, Offset(size.width - len, y), Size(len, barHeight), radius)
			}
		}
	}
}
