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
import kotlin.math.min

private val WhiteOnly = listOf(Color.White)
private const val BUCKETS = 12
private const val PALETTE_SIZE = 3

/**
 * Loads the cover at [url] and returns up to [PALETTE_SIZE] vibrant accent colours sampled from it
 * (for a gradient), or a single white entry when disabled, unavailable, or the cover has no colourful
 * content. Recomputed when the url changes.
 */
@Composable
fun rememberCoverAccentColors(url: String?, enabled: Boolean): List<Color> {
	if (!enabled || url == null) return WhiteOnly

	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	var colors by remember(url) { mutableStateOf(WhiteOnly) }

	LaunchedEffect(url) {
		val palette = withContext(Dispatchers.IO) {
			runCatching {
				val request = ImageRequest.Builder(context)
					.data(url)
					.allowHardware(false)
					.build()
				imageLoader.execute(request).image?.toBitmap()?.let(::extractPalette)
			}.getOrNull()
		}
		if (palette != null) colors = palette
	}

	return colors
}

private fun extractPalette(source: Bitmap): List<Color> {
	val size = 48
	val scaled = Bitmap.createScaledBitmap(source, size, size, true)
	val pixels = IntArray(size * size)
	scaled.getPixels(pixels, 0, size, 0, 0, size, size)

	val weight = DoubleArray(BUCKETS)
	val rSum = DoubleArray(BUCKETS)
	val gSum = DoubleArray(BUCKETS)
	val bSum = DoubleArray(BUCKETS)
	val hsv = FloatArray(3)

	for (p in pixels) {
		val r = (p shr 16) and 0xFF
		val g = (p shr 8) and 0xFF
		val b = p and 0xFF
		android.graphics.Color.RGBToHSV(r, g, b, hsv)
		// Favour saturated, mid-bright pixels so accents are vivid, not muddy.
		val w = (hsv[1] * hsv[1]) * (1f - abs(hsv[2] - 0.6f))
		if (w <= 0f) continue
		val bucket = ((hsv[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
		weight[bucket] += w
		rSum[bucket] += r * w
		gSum[bucket] += g * w
		bSum[bucket] += b * w
	}

	// Greedily pick the strongest hue buckets, keeping them apart so the gradient has variety.
	val picked = mutableListOf<Int>()
	for (bucket in weight.indices.sortedByDescending { weight[it] }) {
		if (weight[bucket] <= 0.0) break
		if (picked.all { min(abs(it - bucket), BUCKETS - abs(it - bucket)) >= 2 }) picked.add(bucket)
		if (picked.size == PALETTE_SIZE) break
	}

	if (picked.isEmpty()) return WhiteOnly

	val result = picked
		.map { bucketColor(rSum[it], gSum[it], bSum[it], weight[it]) }
		.toMutableList()
	// Pad with hue-shifted variants if the cover has fewer distinct vivid hues.
	while (result.size < PALETTE_SIZE) result.add(hueShift(result.last(), 40f))
	return result
}

private fun bucketColor(rSum: Double, gSum: Double, bSum: Double, weight: Double): Color {
	val hsv = FloatArray(3)
	android.graphics.Color.RGBToHSV((rSum / weight).toInt(), (gSum / weight).toInt(), (bSum / weight).toInt(), hsv)
	hsv[1] = (hsv[1] * 1.35f).coerceAtMost(1f)
	hsv[2] = hsv[2].coerceIn(0.65f, 0.95f)
	return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun hueShift(color: Color, degrees: Float): Color {
	val hsv = FloatArray(3)
	android.graphics.Color.RGBToHSV(
		(color.red * 255).toInt(),
		(color.green * 255).toInt(),
		(color.blue * 255).toInt(),
		hsv,
	)
	hsv[0] = (hsv[0] + degrees) % 360f
	return Color(android.graphics.Color.HSVToColor(hsv))
}
