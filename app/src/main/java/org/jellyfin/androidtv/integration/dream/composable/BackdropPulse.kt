package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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

// Speaker-cone "punch": a quick attack then a rapid exponential decay, so the backdrop thumps on the
// kick and snaps back rather than gently breathing.
private const val PUNCH_SCALE = 0.045f  // extra scale at a full-strength hit (~4.5%)
private const val PUNCH_DECAY = 0.80f   // per-frame decay of the punch (lower = snappier settle)

/** Backdrop pulse state: a bass-driven [scale] for the artwork (1f = rest). */
class BackdropPulse {
	var scale by mutableFloatStateOf(1f)
		internal set
}

/**
 * Drives a [BackdropPulse] from the live [AudioSpectrum] bass with a speaker-cone envelope: the scale
 * jumps up on a kick and decays rapidly. Returns a steady (scale 1) state when [enabled] is false. The
 * caller owns AudioSpectrum.acquire()/release().
 */
@Composable
fun rememberBackdropPulse(enabled: Boolean): BackdropPulse {
	val pulse = remember { BackdropPulse() }

	LaunchedEffect(enabled) {
		if (!enabled) {
			pulse.scale = 1f
			return@LaunchedEffect
		}
		var punch = 0f
		while (true) {
			withFrameNanos { }
			val bass = bassLevel(AudioSpectrum.bands.value)
			// Quick attack (jump to a louder hit), rapid exponential decay otherwise.
			punch = beatFollow(punch, bass, PUNCH_DECAY)
			pulse.scale = 1f + punch.coerceIn(0f, 1f) * PUNCH_SCALE
		}
	}

	return pulse
}
