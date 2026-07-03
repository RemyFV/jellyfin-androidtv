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
import org.jellyfin.playback.media3.exoplayer.AudioSpectrum

/**
 * Draws a real-time audio spectrum as mirrored bars hugging the left and right edges - over the
 * blurred side fill of the now-playing backdrop. Values come from [AudioSpectrum]; a per-frame
 * peak-decay (fast attack, slow release) keeps the motion smooth regardless of the audio cadence.
 */
@Composable
fun AudioVisualizerEdges(
	modifier: Modifier = Modifier,
	color: Color = Color.White,
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

		val slot = size.height / n
		val barHeight = slot * 0.55f
		val maxLen = size.width * 0.16f
		val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

		for (i in 0 until n) {
			val v = bars[i]
			if (v <= 0.01f) continue

			val len = v * maxLen
			val y = i * slot + (slot - barHeight) / 2f
			val barColor = color.copy(alpha = 0.25f + 0.55f * v)

			// Left edge, growing inward.
			drawRoundRect(
				color = barColor,
				topLeft = Offset(0f, y),
				size = Size(len, barHeight),
				cornerRadius = radius,
			)
			// Right edge, mirrored.
			drawRoundRect(
				color = barColor,
				topLeft = Offset(size.width - len, y),
				size = Size(len, barHeight),
				cornerRadius = radius,
			)
		}
	}
}
