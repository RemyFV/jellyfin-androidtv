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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
	tipColor: Color = Color.White,
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

					// Bar colour most of the way, fading to the contrast colour at the tip.
					drawLine(
						Brush.linearGradient(0f to c, 0.75f to c, 1f to tipColor, start = start, end = end),
						start, end, thickness, StrokeCap.Round,
					)
				}
			}
			// Oscilloscope waveform traced along each oval, jittering in/out with the raw audio.
			val wave = AudioSpectrum.waveform.value
			if (wave.size >= 2) {
				val waveAmp = h * 0.05f
				val waveWidth = (thickness * 0.5f).coerceAtLeast(1.5f)
				for (side in intArrayOf(1, -1)) {
					val path = Path()
					for (k in wave.indices) {
						val ft = k.toFloat() / (wave.size - 1)
						val tt = -halfArc + ft * (2f * halfArc)
						val bx2 = a * cos(tt)
						val by2 = b * sin(tt)
						val d2 = hypot(bx2, by2).coerceAtLeast(1f)
						var dx2 = (bx2 / d2) * side * (1f - dirHorizontal) + side * dirHorizontal
						var dy2 = (by2 / d2) * (1f - dirHorizontal)
						val dl2 = hypot(dx2, dy2).coerceAtLeast(1e-4f)
						dx2 /= dl2
						dy2 /= dl2
						val off = wave[k] * waveAmp
						val px = cx + side * (bx2 + xGap) + dx2 * off
						val py = cy + by2 + dy2 * off
						if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
					}
					drawPath(path, tipColor.copy(alpha = 0.85f), style = Stroke(width = waveWidth))
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

				// Bar colour most of the way, fading to the contrast colour at the tip (inner end).
				drawRoundRect(
					Brush.linearGradient(0f to c, 0.75f to c, 1f to tipColor, start = Offset(0f, yc), end = Offset(len, yc)),
					Offset(0f, y), Size(len, barHeight), radius,
				)
				drawRoundRect(
					Brush.linearGradient(0f to c, 0.75f to c, 1f to tipColor, start = Offset(size.width, yc), end = Offset(size.width - len, yc)),
					Offset(size.width - len, y), Size(len, barHeight), radius,
				)
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
