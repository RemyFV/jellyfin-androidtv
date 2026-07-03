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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import org.jellyfin.playback.media3.exoplayer.AudioSpectrum
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Real-time audio spectrum overlay for the now-playing screensaver, driven by [AudioSpectrum].
 *
 * @param radial draw a mirrored oval arc on each side of the cover instead of straight edge bars.
 * @param centerOut mirror the spectrum around the middle (bass in the center).
 * @param colorStops bars are coloured with a gradient sampled at (position 0..1 -> colour). A single
 * stop means a flat colour.
 * @param topInset leave room at the top for the (top-right) clock; not needed with a centered clock.
 */
@Composable
fun AudioVisualizer(
	modifier: Modifier = Modifier,
	radial: Boolean = false,
	centerOut: Boolean = false,
	colorStops: List<Pair<Float, Color>> = listOf(0f to Color.White),
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

		fun bandFor(slot: Int): Int = if (centerOut) {
			val d = abs(slot - (n - 1) / 2f) / ((n - 1) / 2f)
			(d * (n - 1)).toInt().coerceIn(0, n - 1)
		} else {
			slot.coerceIn(0, n - 1)
		}

		fun sampleStops(frac: Float): Color {
			if (colorStops.size == 1) return colorStops[0].second
			if (frac <= colorStops.first().first) return colorStops.first().second
			if (frac >= colorStops.last().first) return colorStops.last().second
			for (i in 1 until colorStops.size) {
				val (f1, c1) = colorStops[i]
				if (frac <= f1) {
					val (f0, c0) = colorStops[i - 1]
					val t = if (f1 > f0) (frac - f0) / (f1 - f0) else 0f
					return lerp(c0, c1, t)
				}
			}
			return colorStops.last().second
		}

		fun barColor(slot: Int, v: Float): Color {
			val frac = if (n == 1) 0f else slot.toFloat() / (n - 1)
			return sampleStops(frac).copy(alpha = 0.5f + 0.5f * v)
		}

		if (radial) {
			// A flat oval whose left/right extremes sit on the square cover's side edges; bars
			// radiate outward from it into the wings.
			val cx = size.width / 2f
			val cy = size.height / 2f
			val coverHalf = size.height * 0.5f
			// Tall (vertical) oval: horizontal axis at the cover's side edges, taller vertically so
			// the arcs hug the edges. Arc span kept narrow enough to stay on-screen.
			val a = coverHalf * 1.02f
			val b = coverHalf * 1.5f
			val maxLen = size.height * 0.195f
			val thickness = (size.height / (n * 1.7f)).coerceAtLeast(2f)
			val halfArc = (38.0 * Math.PI / 180.0).toFloat()

			for (i in 0 until n) {
				val v = bars[bandFor(i)]
				if (v <= 0.01f) continue
				val frac = if (n == 1) 0.5f else i.toFloat() / (n - 1)
				val t = -halfArc + frac * (2f * halfArc)
				val bx = a * cos(t)
				val by = b * sin(t)
				val dist = hypot(bx, by).coerceAtLeast(1f)
				val nx = bx / dist
				val ny = by / dist
				val len = v * maxLen
				val c = barColor(i, v)
				// Fade in from the oval (transparent) so the bases don't form a visible static ring.
				// Vary the opaque point per bar so it doesn't read as a uniform outline.
				val reach = 0.4f + 0.35f * (((i * 37) % 100) / 100f)

				val rStart = Offset(cx + bx, cy + by)
				val rEnd = Offset(cx + bx + nx * len, cy + by + ny * len)
				drawLine(
					Brush.linearGradient(0f to c.copy(alpha = 0f), reach to c, 1f to c, start = rStart, end = rEnd),
					rStart, rEnd, thickness, StrokeCap.Round,
				)

				val lStart = Offset(cx - bx, cy + by)
				val lEnd = Offset(cx - bx - nx * len, cy + by + ny * len)
				drawLine(
					Brush.linearGradient(0f to c.copy(alpha = 0f), reach to c, 1f to c, start = lStart, end = lEnd),
					lStart, lEnd, thickness, StrokeCap.Round,
				)
			}
		} else {
			// Straight bars hugging each edge. Top inset keeps them clear of the top-right clock.
			val inset = if (topInset) size.height * 0.10f else 0f
			val usableH = size.height - inset
			val slot = usableH / n
			val barHeight = slot * 0.55f
			val maxLen = size.width * 0.16f
			val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

			for (i in 0 until n) {
				val v = bars[bandFor(i)]
				if (v <= 0.01f) continue
				val len = v * maxLen
				val y = inset + i * slot + (slot - barHeight) / 2f
				val c = barColor(i, v)

				drawRoundRect(c, Offset(0f, y), Size(len, barHeight), radius)
				drawRoundRect(c, Offset(size.width - len, y), Size(len, barHeight), radius)
			}
		}
	}
}
