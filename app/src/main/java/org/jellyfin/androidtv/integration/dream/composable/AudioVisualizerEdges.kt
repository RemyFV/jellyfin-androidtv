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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
	topInset: Boolean = true,
) {
	val display = remember { mutableStateOf(FloatArray(AudioSpectrum.BAND_COUNT)) }
	val beatState = remember { mutableStateOf(0f) }
	val phaseState = remember { mutableStateOf(0f) }

	LaunchedEffect(Unit) {
		val current = FloatArray(AudioSpectrum.BAND_COUNT)
		var beat = 0f
		var phase = 0f
		while (true) {
			withFrameNanos { }
			val target = AudioSpectrum.bands.value
			for (i in current.indices) {
				val t = target.getOrElse(i) { 0f }
				current[i] = if (t > current[i]) t else current[i] * 0.82f + t * 0.18f
			}
			display.value = current.copyOf()

			// Beat level from the low bands (fast attack, slow release) drives a brightness pulse.
			beat = beatFollow(beat, bassLevel(current))
			beatState.value = beat.coerceIn(0f, 1f)

			phase += 0.12f
			phaseState.value = phase
		}
	}

	val bars = display.value
	val beat = beatState.value
	val phase = phaseState.value

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

		// Brightness pulse as an amount to lift the bar colour toward white (0 = base colour, 1 = white):
		// a per-bar phase-shifted shimmer (always >= 0) plus a global beat boost, so bars brighten with
		// the music and slightly out of sync with each other, never dropping below their base colour.
		fun barPulse(slot: Int): Float =
			(0.12f * (0.5f + 0.5f * sin(phase + slot * 0.55f)) + 0.45f * beat).coerceIn(0f, 0.7f)

		// Lift [this] toward white by [f] (0 = unchanged, 1 = white).
		fun Color.brighten(f: Float): Color = lerp(this, Color.White, f.coerceIn(0f, 1f))

		// Shimmer profile along a bar (base -> tip): the base colour for the first 40%, then ramping to
		// the brightened colour at the tip, so the glow reads as a gradient concentrated at the tip.
		fun barShimmerStops(slot: Int): Array<Pair<Float, Color>> {
			val base = barColor(slot)
			return arrayOf(0f to base, 0.4f to base, 1f to base.brighten(barPulse(slot)))
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
				val stops = barShimmerStops(i)

				for (side in intArrayOf(1, -1)) {
					val baseX = cx + side * (bx + xGap)
					val baseY = cy + by
					var dx = side * nx * (1f - dirHorizontal) + side * dirHorizontal
					var dy = ny * (1f - dirHorizontal)
					val dl = hypot(dx, dy).coerceAtLeast(1e-4f)
					dx /= dl
					dy /= dl

					val frameLimit = maxLengthInFrame(baseX, baseY, dx, dy, w, h, margin)
					val len = minOf(desired, frameLimit)
					if (len <= 0f) continue

					val start = Offset(baseX, baseY)
					val end = Offset(baseX + dx * len, baseY + dy * len)
					// Anchor the shimmer gradient to the bar's MAX reach (design max, capped by the frame),
					// not its current length, so the tip glow only appears as the bar nears full length.
					val maxReach = minOf(maxLen, frameLimit)
					val gradientEnd = Offset(baseX + dx * maxReach, baseY + dy * maxReach)

					drawLine(
						Brush.linearGradient(*stops, start = start, end = gradientEnd),
						start, end, thickness, StrokeCap.Round,
					)
				}
			}
			// Oscilloscope waveform traced along each oval, jittering in/out with the raw audio.
			val wave = AudioSpectrum.waveform.value
			if (wave.size >= 2 && (wave.maxOfOrNull { abs(it) } ?: 0f) > 0.015f) {
				val waveAmp = h * 0.05f
				val waveWidth = thickness.coerceAtLeast(3f)
				val waveAlpha = ((wave.maxOfOrNull { abs(it) } ?: 0f) * 5f).coerceIn(0f, 0.85f)
				// Exactly the bars' gradient - no brightness offset. The waveform's motion is what
				// sets it apart from the bars underneath.
				val waveBrush = if (colorStops.size == 1) SolidColor(colorStops[0].second)
				else Brush.linearGradient(
					*colorStops.toTypedArray(),
					start = Offset(cx, cy - b * sin(halfArc)),
					end = Offset(cx, cy + b * sin(halfArc)),
				)
				// Low-pass the raw samples (moving average) so the line flows in rounded curves.
				val smooth = FloatArray(wave.size) { k ->
					var s = 0f
					var cnt = 0
					for (j in (k - 2)..(k + 2)) if (j in wave.indices) { s += wave[j]; cnt++ }
					s / cnt
				}
				for (side in intArrayOf(1, -1)) {
					val path = Path()
					var prevX = 0f
					var prevY = 0f
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
						val off = smooth[k] * waveAmp
						val px = cx + side * (bx2 + xGap) + dx2 * off
						val py = cy + by2 + dy2 * off
						// Quadratic through midpoints: each raw point is a control point, the on-curve
						// anchors sit between them, rounding the spikes into smooth curves.
						if (k == 0) path.moveTo(px, py)
						else path.quadraticTo(prevX, prevY, (prevX + px) / 2f, (prevY + py) / 2f)
						prevX = px
						prevY = py
					}
					path.lineTo(prevX, prevY)
					drawPath(
						path, waveBrush, alpha = waveAlpha,
						style = Stroke(width = waveWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
					)
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
				val stops = barShimmerStops(i)

				// The gradient spans the bar's MAX length (maxLen), not its current length, so a short bar
				// shows only the base-colour end and the tip glow appears as it grows toward full length.
				// Left bar: base at the left screen edge (x=0), glowing tip pointing inward.
				drawRoundRect(
					Brush.horizontalGradient(*stops, startX = 0f, endX = maxLen),
					Offset(0f, y), Size(len, barHeight), radius,
				)
				// Right bar: base at the right screen edge, tip pointing inward.
				drawRoundRect(
					Brush.horizontalGradient(*stops, startX = size.width, endX = size.width - maxLen),
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
