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
 * Visualizer colouring from the cover: gradient [stops] (position 0..1 to colour) for the bars, and
 * [lightenTips] - whether bar tips should be a lighter shade (dark cover) or darker shade (light
 * cover) of their own colour.
 */
data class VisualizerPalette(
	val stops: List<Pair<Float, Color>>,
	val lightenTips: Boolean,
)

private val DefaultPalette = VisualizerPalette(WhiteStops, true)

/**
 * Loads the cover at [url] and derives the visualizer palette. When [useCoverColor] is off the bars
 * stay white but the tip contrast colour is still computed from the cover's brightness. Returns the
 * default (white bars, white tips) when disabled or unavailable.
 */
@Composable
fun rememberVisualizerPalette(url: String?, enabled: Boolean, useCoverColor: Boolean): VisualizerPalette {
	if (!enabled || url == null) return DefaultPalette

	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	var palette by remember(url, useCoverColor) { mutableStateOf(DefaultPalette) }

	LaunchedEffect(url, useCoverColor) {
		val extracted = withContext(Dispatchers.IO) {
			runCatching {
				val request = ImageRequest.Builder(context)
					.data(url)
					.allowHardware(false)
					.build()
				imageLoader.execute(request).image?.toBitmap()?.let { extractPalette(it, useCoverColor) }
			}.getOrNull()
		}
		if (extracted != null) palette = extracted
	}

	return palette
}

private fun extractPalette(source: Bitmap, useCoverColor: Boolean): VisualizerPalette {
	val size = 48
	val scaled = Bitmap.createScaledBitmap(source, size, size, true)
	val pixels = IntArray(size * size)
	scaled.getPixels(pixels, 0, size, 0, 0, size, size)

	val weight = DoubleArray(BUCKETS)
	val rSum = DoubleArray(BUCKETS)
	val gSum = DoubleArray(BUCKETS)
	val bSum = DoubleArray(BUCKETS)
	val hsv = FloatArray(3)
	var lumaSum = 0.0

	for (p in pixels) {
		val r = (p shr 16) and 0xFF
		val g = (p shr 8) and 0xFF
		val b = p and 0xFF
		lumaSum += 0.299 * r + 0.587 * g + 0.114 * b

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

	// Tips shade toward the contrasting end: lighten on dark covers, darken on light ones.
	val avgLuma = (lumaSum / pixels.size) / 255.0
	val lightenTips = avgLuma <= 0.5

	val stops = if (useCoverColor) buildStops(weight, rSum, gSum, bSum) else WhiteStops
	return VisualizerPalette(stops, lightenTips)
}

private fun buildStops(
	weight: DoubleArray,
	rSum: DoubleArray,
	gSum: DoubleArray,
	bSum: DoubleArray,
): List<Pair<Float, Color>> {
	// Rank hue buckets by prevalence, keeping them apart so the gradient has variety.
	val picked = mutableListOf<Int>()
	for (bucket in weight.indices.sortedByDescending { weight[it] }) {
		if (weight[bucket] <= 0.0) break
		// Only a reasonably prevalent hue counts as a distinct colour, so faint off-hue noise on a
		// near-monochrome cover doesn't become an invented colour (buckets are sorted by weight).
		if (picked.isNotEmpty() && weight[bucket] < weight[picked[0]] * 0.18) break
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

	// Small centred band for the dominant colour; the two flanks get solid plateaus of their own so
	// the dominant doesn't bleed across the whole arc.
	val total = picked.sumOf { weight[it] }.coerceAtLeast(1e-6)
	val centerHalf = (0.5 * weight[picked[0]] / total).coerceIn(0.05, 0.10).toFloat()
	val flank = 0.28f

	val dominant = colors[0]
	val left = colors[1]
	val right = colors[2]
	return listOf(
		0f to left,
		flank to left,
		(0.5f - centerHalf) to dominant,
		(0.5f + centerHalf) to dominant,
		(1f - flank) to right,
		1f to right,
	)
}

private fun bucketColor(rSum: Double, gSum: Double, bSum: Double, weight: Double): Color {
	val hsv = FloatArray(3)
	android.graphics.Color.RGBToHSV((rSum / weight).toInt(), (gSum / weight).toInt(), (bSum / weight).toInt(), hsv)
	// Vivid, bright accents so the bars pop against the muted blurred backdrop.
	hsv[1] = (hsv[1] * 1.6f).coerceAtMost(1f)
	hsv[2] = hsv[2].coerceIn(0.82f, 1f)
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
