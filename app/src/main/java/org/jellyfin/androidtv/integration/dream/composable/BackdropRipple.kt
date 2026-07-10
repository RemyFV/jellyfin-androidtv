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
import androidx.compose.ui.graphics.Brush
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
private const val RING_LIFETIME = 0.95f     // seconds a ring is tracked as it expands
private const val RING_PEAK_ALPHA = 0.45f   // peak opacity a ring fades in to, then out from
private const val BEAT_RISE = 0.06f         // min upward jump in the beat envelope to spawn a ring
private const val ONSET_FLOOR = 0.12f       // beat must be at least this loud, so quiet passages stay still
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
		var prevBeat = 0f
		var sinceRing = MIN_RING_INTERVAL
		var lastNanos = 0L
		var rings = emptyList<RippleRing>()

		while (true) {
			val now = withFrameNanos { it }
			val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
			lastNanos = now

			val bass = bassLevel(AudioSpectrum.bands.value)

			// One envelope (fast attack, slow release) drives BOTH the zoom and the rings, so they pulse
			// together off the same beat.
			beat = beatFollow(beat, bass)
			pulse.scale = 1f + beat * ZOOM_AMOUNT

			// Age existing rings and drop the finished ones.
			rings = rings.mapNotNull { r ->
				val aged = r.copy(age = r.age + dt)
				if (aged.age >= RING_LIFETIME) null else aged
			}

			// Spawn a ring on the rising edge of that same beat envelope: the zoom kicking up is exactly
			// what produces a ring. Rate-limited so one hit makes one ring; strength = the pulse height.
			sinceRing += dt
			if (beat - prevBeat > BEAT_RISE && beat > ONSET_FLOOR && sinceRing >= MIN_RING_INTERVAL) {
				rings = rings + RippleRing(age = 0f, strength = beat.coerceIn(0f, 1f))
				sinceRing = 0f
			}
			prevBeat = beat

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

		val maxRadius = size.maxDimension * 0.6f
		val minDim = size.minDimension

		for (r in rings) {
			val progress = (r.age / RING_LIFETIME).coerceIn(0f, 1f)
			// Opacity envelope: start fully transparent, fade in to the peak (by ~20% of the expansion),
			// then fade out, so each ring blooms and dissolves in time with the backdrop beat.
			val fade = if (progress < 0.2f) progress / 0.2f
			else (1f - (progress - 0.2f) / 0.5f).coerceIn(0f, 1f)
			val alpha = RING_PEAK_ALPHA * fade
			if (alpha <= 0.001f) continue

			val radius = maxRadius * progress
			if (radius <= 1f) continue

			// Leading ring line, thicker on a stronger bass hit (like a bar's length tracks its band).
			val thickness = minDim * (0.003f + 0.018f * r.strength)
			// Inner glow trailing behind the ring: the same colour fades to transparent a short way
			// inside the edge, so the expanding ring drags a soft fading tail.
			val glowInner = ((radius - minDim * 0.05f) / radius).coerceIn(0f, 1f)
			val glow = Brush.radialGradient(
				0f to Color.Transparent,
				glowInner to Color.Transparent,
				1f to color,
				center = center,
				radius = radius,
			)

			drawCircle(brush = glow, radius = radius, alpha = alpha)
			drawCircle(color = color, radius = radius, alpha = alpha, style = Stroke(width = thickness))
		}
	}
}
