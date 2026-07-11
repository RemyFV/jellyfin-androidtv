package org.jellyfin.androidtv.integration.dream.composable

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
