package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import org.jellyfin.playback.media3.exoplayer.AudioSpectrum

/** Number of low spectrum bands treated as "bass". Shared by the visualizer and the backdrop pulse. */
const val BASS_BANDS = 8

/** Mean magnitude (0..1) of the low (bass) bands of [bands]. */
fun bassLevel(bands: FloatArray): Float {
	val lo = minOf(BASS_BANDS, bands.size)
	if (lo == 0) return 0f
	var sum = 0f
	for (i in 0 until lo) sum += bands[i]
	return sum / lo
}

/**
 * One fast-attack, slow-release step of an envelope follower from [prev] toward [level]: jumps up
 * instantly on a louder value, eases back down by [release] per step. Used for beat-driven pulses.
 */
fun beatFollow(prev: Float, level: Float, release: Float = 0.90f): Float =
	if (level > prev) level else prev * release + level * (1f - release)

// A single expanding ring. age is seconds since it spawned; strength is its starting opacity factor.
internal data class RippleRing(val age: Float, val strength: Float)

// Tuning - deliberately subtle so it reads as a gentle pulse, not a strobe.
private const val ZOOM_AMOUNT = 0.02f       // max extra scale on a strong beat (2%)
private const val RING_LIFETIME = 1.4f      // seconds for a ring to cross the screen and fade out
private const val RING_PEAK_ALPHA = 0.14f   // opacity of a fresh ring at full-strength bass
private const val ONSET_SENSITIVITY = 1.35f // bass must exceed this * running average to spawn a ring
private const val ONSET_FLOOR = 0.12f       // and be at least this loud, so quiet passages stay still
private const val MIN_RING_INTERVAL = 0.18f // seconds between rings, so fast bass doesn't flood

/** Backdrop pulse state: a beat-driven [scale] for the artwork plus the live set of expanding rings. */
class BackdropPulse {
	var scale by mutableFloatStateOf(1f)
		internal set
	internal var rings by mutableStateOf<List<RippleRing>>(emptyList())
}

/**
 * Drives a [BackdropPulse] from the live [AudioSpectrum] bass: eases the zoom toward the beat and
 * spawns a ring whenever the bass jumps above its recent average. Returns a steady (scale 1, no rings)
 * state when [enabled] is false. The caller is responsible for AudioSpectrum.acquire()/release().
 */
@Composable
fun rememberBackdropPulse(enabled: Boolean): BackdropPulse {
	val pulse = remember { BackdropPulse() }

	LaunchedEffect(enabled) {
		if (!enabled) {
			pulse.scale = 1f
			pulse.rings = emptyList()
			return@LaunchedEffect
		}

		var beat = 0f
		var avg = 0f
		var sinceRing = MIN_RING_INTERVAL
		var lastNanos = 0L
		var rings = emptyList<RippleRing>()

		while (true) {
			val now = withFrameNanos { it }
			val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
			lastNanos = now

			val bass = bassLevel(AudioSpectrum.bands.value)

			// Zoom eases with the beat (fast attack, slow release).
			beat = beatFollow(beat, bass)
			pulse.scale = 1f + beat * ZOOM_AMOUNT

			// Age existing rings and drop the finished ones.
			rings = rings.mapNotNull { r ->
				val aged = r.copy(age = r.age + dt)
				if (aged.age >= RING_LIFETIME) null else aged
			}

			// Onset detection: a ring on a bass hit that clearly exceeds the running average.
			sinceRing += dt
			if (bass > ONSET_FLOOR && bass > avg * ONSET_SENSITIVITY && sinceRing >= MIN_RING_INTERVAL) {
				rings = rings + RippleRing(age = 0f, strength = bass.coerceIn(0f, 1f))
				sinceRing = 0f
			}
			avg = avg * 0.92f + bass * 0.08f

			pulse.rings = rings
		}
	}

	return pulse
}

/** Draws the expanding rings of [pulse] over the backdrop in [color]. */
@Composable
fun BackdropRippleOverlay(
	pulse: BackdropPulse,
	color: Color,
	modifier: Modifier = Modifier,
) {
	Canvas(modifier = modifier.fillMaxSize()) {
		val rings = pulse.rings
		if (rings.isEmpty()) return@Canvas

		val center = size.center
		val maxRadius = size.maxDimension * 0.6f
		val strokeWidth = size.minDimension * 0.006f

		for (r in rings) {
			val progress = (r.age / RING_LIFETIME).coerceIn(0f, 1f)
			// Fade in briefly then out, so a ring doesn't pop in at full opacity.
			val fade = (1f - progress) * (progress * 4f).coerceAtMost(1f)
			val alpha = RING_PEAK_ALPHA * r.strength * fade
			if (alpha <= 0.001f) continue
			drawCircle(
				color = color,
				radius = maxRadius * progress,
				center = center,
				alpha = alpha,
				style = Stroke(width = strokeWidth),
			)
		}
	}
}
