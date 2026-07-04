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

		// Cheap glow: two wider, semi-transparent solid strokes in the bar's own colour (no blur,
		// so it's GPU-cheap on a TV). start..end should be the outer half of the bar.
		fun drawGlow(start: Offset, end: Offset, width: Float, color: Color) {
			drawLine(color.copy(alpha = 0.15f), start, end, width * 3.0f, StrokeCap.Round)
			drawLine(color.copy(alpha = 0.30f), start, end, width * 1.8f, StrokeCap.Round)
		}

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

		fun barColor(slot: Int): Color {
			val frac = if (n == 1) 0f else slot.toFloat() / (n - 1)
			return sampleStops(frac)
		}

		if (radial) {
			// Two mirrored near-vertical arcs on the cover's sides. Values tuned in the layout tool:
			// a slightly-bowed baseline pushed apart by xGap, bars pointing half-radial/half-outward,
			// each length-clamped so its tip can't leave the frame, fading in from the base.
			val w = size.width
			val h = size.height
			val cx = w / 2f
			val cy = h / 2f
			val coverHalf = h * 0.5f
			val a = coverHalf * 0.80f
			val b = coverHalf * 1.48f
			val maxLen = h * 0.52f
			val xGap = h * 0.10f
			val margin = h * 0.035f
			val dirHorizontal = 0.5f
			val thickness = (h / (n * 2.34f)).coerceAtLeast(2f)
			val halfArc = (37.0 * Math.PI / 180.0).toFloat()

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
				val desired = v * maxLen
				val c = barColor(i)

				for (side in intArrayOf(1, -1)) {
					val baseX = cx + side * (bx + xGap)
					val baseY = cy + by
					var dx = side * nx * (1f - dirHorizontal) + side * dirHorizontal
					var dy = ny * (1f - dirHorizontal)
					val dl = hypot(dx, dy).coerceAtLeast(1e-4f)
					dx /= dl
					dy /= dl

					val len = minOf(desired, maxLengthInFrame(baseX, baseY, dx, dy, w, h, margin))
					if (len <= 0f) continue

					val start = Offset(baseX, baseY)
					val end = Offset(baseX + dx * len, baseY + dy * len)

					// Glow on the outer half of the bar.
					drawGlow(Offset(baseX + dx * len * 0.5f, baseY + dy * len * 0.5f), end, thickness, c)

					drawLine(c, start, end, thickness, StrokeCap.Round)
				}
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
				val yc = y + barHeight / 2f
				val c = barColor(i)

				// Glow on the outer half of each bar.
				drawGlow(Offset(len * 0.5f, yc), Offset(len, yc), barHeight, c)
				drawGlow(Offset(size.width - len * 0.5f, yc), Offset(size.width - len, yc), barHeight, c)

				drawRoundRect(c, Offset(0f, y), Size(len, barHeight), radius)
				drawRoundRect(c, Offset(size.width - len, y), Size(len, barHeight), radius)
			}
		}
	}
}

/** Max length from (px,py) along unit (dx,dy) keeping the tip [margin] px inside the w x h frame. */
private fun maxLengthInFrame(px: Float, py: Float, dx: Float, dy: Float, w: Float, h: Float, margin: Float): Float {
	var t = Float.MAX_VALUE
	if (dx > 1e-4f) t = minOf(t, (w - margin - px) / dx)
	else if (dx < -1e-4f) t = minOf(t, (margin - px) / dx)
	if (dy > 1e-4f) t = minOf(t, (h - margin - py) / dy)
	else if (dy < -1e-4f) t = minOf(t, (margin - py) / dy)
	return t.coerceAtLeast(0f)
}
