package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * A lightweight FPS readout (white text, black outline) for spot-checking render cost. It counts every
 * frame via [withFrameNanos] but only pushes a new value to state a few times a second, so the text
 * recomposes ~3x/s rather than every frame. Gate the whole composable behind a toggle so it costs
 * nothing (no frame loop) when hidden.
 */
@Composable
fun FpsCounter(modifier: Modifier = Modifier) {
	var fps by remember { mutableIntStateOf(0) }

	LaunchedEffect(Unit) {
		var frames = 0
		var elapsed = 0.0
		var lastNanos = 0L
		while (true) {
			val now = withFrameNanos { it }
			if (lastNanos != 0L) {
				elapsed += (now - lastNanos) / 1_000_000_000.0
				frames++
				if (elapsed >= 0.33) {
					fps = (frames / elapsed).toInt()
					frames = 0
					elapsed = 0.0
				}
			}
			lastNanos = now
		}
	}

	val rw = GlStats.renderW
	val rh = GlStats.renderH
	val glFps = GlStats.fps
	OutlinedText(
		text = if (rw > 0) "ui $fps · gl $glFps · ${rw}x$rh" else "ui $fps",
		color = Color.White,
		fontSize = 14.sp,
		modifier = modifier,
	)
}
