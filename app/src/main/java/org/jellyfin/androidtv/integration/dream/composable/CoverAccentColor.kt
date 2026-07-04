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

private val WhiteStops = listOf(0f to Color.White)
private const val BUCKETS = 12
private const val PALETTE_SIZE = 3

/**
 * Loads the cover at [url] and returns gradient stops (position 0..1 to colour): the most prevalent
 * vivid colour sits in the centre (band capped to ~30% of the arc), the next two flank it. Returns a
 * single white stop when disabled, unavailable, or the cover has no colourful content.
 */
@Composable
fun rememberVisualizerColorStops(url: String?, enabled: Boolean): List<Pair<Float, Color>> {
	if (!enabled || url == null) return WhiteStops

	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	var stops by remember(url) { mutableStateOf(WhiteStops) }

	LaunchedEffect(url) {
		val extracted = withContext(Dispatchers.IO) {
			runCatching {
				val request = ImageRequest.Builder(context)
					.data(url)
					.allowHardware(false)
					.build()
				imageLoader.execute(request).image?.toBitmap()?.let(::extractStops)
			}.getOrNull()
		}
		if (extracted != null) stops = extracted
	}

	return stops
}

private fun extractStops(source: Bitmap): List<Pair<Float, Color>> {
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

	// Rank hue buckets by prevalence, keeping them apart so the gradient has variety.
	val picked = mutableListOf<Int>()
	for (bucket in weight.indices.sortedByDescending { weight[it] }) {
		if (weight[bucket] <= 0.0) break
		if (picked.all { min(abs(it - bucket), BUCKETS - abs(it - bucket)) >= 2 }) picked.add(bucket)
		if (picked.size == PALETTE_SIZE) break
	}

	if (picked.isEmpty()) return WhiteStops

	val colors = picked.map { bucketColor(rSum[it], gSum[it], bSum[it], weight[it]) }.toMutableList()
	// Pad with darker shades of the dominant colour rather than inventing new hues (a mostly-brown
	// cover should stay brown, not gain blue/green).
	var shadeFactor = 0.62f
	while (colors.size < PALETTE_SIZE) {
		colors.add(shade(colors[0], shadeFactor))
		shadeFactor *= 0.75f
	}

	// Centre the most prevalent colour, band sized by its share but capped to ~30% of the gradient.
	val total = picked.sumOf { weight[it] }.coerceAtLeast(1e-6)
	val centerHalf = (0.5 * weight[picked[0]] / total).coerceIn(0.06, 0.15).toFloat()

	val dominant = colors[0]
	val left = colors[1]
	val right = colors[2]
	return listOf(
		0f to left,
		(0.5f - centerHalf) to dominant,
		(0.5f + centerHalf) to dominant,
		1f to right,
	)
}

private fun bucketColor(rSum: Double, gSum: Double, bSum: Double, weight: Double): Color {
	val hsv = FloatArray(3)
	android.graphics.Color.RGBToHSV((rSum / weight).toInt(), (gSum / weight).toInt(), (bSum / weight).toInt(), hsv)
	hsv[1] = (hsv[1] * 1.35f).coerceAtMost(1f)
	hsv[2] = hsv[2].coerceIn(0.65f, 0.95f)
	return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun shade(color: Color, valueFactor: Float): Color {
	val hsv = FloatArray(3)
	android.graphics.Color.RGBToHSV(
		(color.red * 255).toInt(),
		(color.green * 255).toInt(),
		(color.blue * 255).toInt(),
		hsv,
	)
	// Same hue, darker (toward black) - keeps a monochrome cover monochrome.
	hsv[2] = (hsv[2] * valueFactor).coerceIn(0.2f, 1f)
	return Color(android.graphics.Color.HSVToColor(hsv))
}
