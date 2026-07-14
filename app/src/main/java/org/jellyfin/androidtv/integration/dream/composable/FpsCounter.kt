package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * A lightweight FPS readout (white text, black outline) for spot-checking render cost. The animation
 * runs entirely on [GlStats] (the GL render thread), which is plain volatile state, not Compose state -
 * so we poll it on a timer coroutine and push to Compose state, rather than relying on recomposition
 * (nothing on the Compose side animates the now-playing screen, so it would otherwise only refresh on a
 * song change). Gate the whole composable behind a toggle so it costs nothing when hidden.
 */
@Composable
fun FpsCounter(modifier: Modifier = Modifier) {
	var fps by remember { mutableIntStateOf(0) }
	var rw by remember { mutableIntStateOf(0) }
	var rh by remember { mutableIntStateOf(0) }

	LaunchedEffect(Unit) {
		while (true) {
			fps = GlStats.fps
			rw = GlStats.renderW
			rh = GlStats.renderH
			delay(250)
		}
	}

	OutlinedText(
		text = if (rw > 0) "$fps fps · ${rw}x$rh" else "$fps fps",
		color = Color.White,
		fontSize = 14.sp,
		modifier = modifier,
	)
}
