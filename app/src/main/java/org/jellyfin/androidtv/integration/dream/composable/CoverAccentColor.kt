package org.jellyfin.androidtv.integration.dream.composable

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * Loads the cover at [url] and returns a vibrant accent colour sampled from it, or [Color.White]
 * when disabled, unavailable, or the cover has no colourful content. Recomputed when the url changes.
 */
@Composable
fun rememberCoverAccentColor(url: String?, enabled: Boolean): Color {
	if (!enabled || url == null) return Color.White

	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	var color by remember(url) { mutableStateOf(Color.White) }

	LaunchedEffect(url) {
		val accent = withContext(Dispatchers.IO) {
			runCatching {
				val request = ImageRequest.Builder(context)
					.data(url)
					.allowHardware(false)
					.build()
				imageLoader.execute(request).image?.toBitmap()?.let(::extractAccent)
			}.getOrNull()
		}
		if (accent != null) color = accent
	}

	return color
}

private fun extractAccent(source: Bitmap): Color {
	val size = 48
	val scaled = Bitmap.createScaledBitmap(source, size, size, true)
	val pixels = IntArray(size * size)
	scaled.getPixels(pixels, 0, size, 0, 0, size, size)

	val hsv = FloatArray(3)
	var rw = 0.0
	var gw = 0.0
	var bw = 0.0
	var weightSum = 0.0

	for (p in pixels) {
		val r = (p shr 16) and 0xFF
		val g = (p shr 8) and 0xFF
		val b = p and 0xFF
		android.graphics.Color.RGBToHSV(r, g, b, hsv)
		// Favour saturated, mid-bright pixels so the accent is vivid, not muddy or washed out.
		val weight = (hsv[1] * hsv[1]) * (1f - abs(hsv[2] - 0.6f))
		if (weight <= 0f) continue
		rw += r * weight
		gw += g * weight
		bw += b * weight
		weightSum += weight
	}

	if (weightSum < 1e-3) return Color.White

	val r = (rw / weightSum).toInt()
	val g = (gw / weightSum).toInt()
	val b = (bw / weightSum).toInt()
	android.graphics.Color.RGBToHSV(r, g, b, hsv)
	hsv[1] = (hsv[1] * 1.35f).coerceAtMost(1f)
	hsv[2] = hsv[2].coerceIn(0.65f, 0.95f)
	return Color(android.graphics.Color.HSVToColor(hsv))
}
