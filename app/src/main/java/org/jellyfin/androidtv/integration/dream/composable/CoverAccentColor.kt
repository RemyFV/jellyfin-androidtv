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

private val WhiteStops = listOf(0f to Color.White)
private const val BUCKETS = 12
private const val PALETTE_SIZE = 3

// A pixel counts as "coloured" (gets a hue) only above this chroma (= S*V = delta/255). Below it the
// pixel is a neutral (white/black/grey) and never contributes a hue - this keeps JPEG/noise chroma
// (e.g. a slightly blue-tinted black) out of the accent colour.
private const val CHROMA_MIN = 0.15f
// A hue must cover at least this fraction of the cover to count as a real colour, not a stray speck.
private const val MIN_COLOR_AREA = 0.02
// If one colour (a hue, or white/black/grey) covers more than this share of the image the cover is
// "monochrome": the bars use the next colour instead, so they contrast with the dominant backdrop
// rather than blending into it (and never go invisible-white on white / invisible on black).
private const val DOMINANT_FRACTION = 0.80

/**
 * Visualizer colouring from the cover: gradient [stops] (position 0..1 to colour) for the bars. The
 * waveform reuses the same stops, so no separate contrast colour is needed.
 */
data class VisualizerPalette(
	val stops: List<Pair<Float, Color>>,
)

private val DefaultPalette = VisualizerPalette(WhiteStops)

/**
 * Loads the cover at [url] and derives the visualizer palette. Returns the default (white bars) when
 * disabled, unavailable, or [useCoverColor] is off.
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
	if (!useCoverColor) return DefaultPalette

	val size = 48
	val scaled = Bitmap.createScaledBitmap(source, size, size, true)
	val pixels = IntArray(size * size)
	scaled.getPixels(pixels, 0, size, 0, 0, size, size)
	val total = pixels.size

	// Per hue bucket: area (pixel count) and chroma-weighted colour sums, for the coloured pixels.
	val area = IntArray(BUCKETS)
	val wSum = DoubleArray(BUCKETS)
	val rSum = DoubleArray(BUCKETS)
	val gSum = DoubleArray(BUCKETS)
	val bSum = DoubleArray(BUCKETS)
	// Neutral areas (pixels below CHROMA_MIN), split by brightness, plus overall luma for the fallback.
	var whiteArea = 0
	var blackArea = 0
	var greyArea = 0
	var lumaSum = 0.0
	val hsv = FloatArray(3)

	for (p in pixels) {
		val r = (p shr 16) and 0xFF
		val g = (p shr 8) and 0xFF
		val b = p and 0xFF
		val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
		lumaSum += luma

		android.graphics.Color.RGBToHSV(r, g, b, hsv)
		val chroma = hsv[1] * hsv[2]
		if (chroma >= CHROMA_MIN) {
			val bucket = ((hsv[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
			area[bucket]++
			wSum[bucket] += chroma
			rSum[bucket] += r * chroma
			gSum[bucket] += g * chroma
			bSum[bucket] += b * chroma
		} else when {
			luma > 0.75 -> whiteArea++
			luma < 0.20 -> blackArea++
			else -> greyArea++
		}
	}

	// The single most common colour of any kind (a hue, or white/black/grey) and whether it dominates.
	val topHue = area.indices.maxByOrNull { area[it] } ?: 0
	val maxArea = maxOf(area[topHue], whiteArea, blackArea, greyArea)
	val dominatedByOne = maxArea.toDouble() / total > DOMINANT_FRACTION
	val dominantIsHue = area[topHue] == maxArea && area[topHue] > 0

	// Rank the real chromatic colours by area (largest first), dropping specks and - when one colour
	// owns the whole image - the dominant hue itself, so the bars contrast rather than blend.
	val ranked = area.indices
		.filter { area[it].toDouble() / total >= MIN_COLOR_AREA }
		.filterNot { dominatedByOne && dominantIsHue && it == topHue }
		.sortedByDescending { area[it] }

	if (ranked.isEmpty()) {
		// No real colour to use (grayscale / near-monochrome, or a single hue that filled the frame).
		// Pick a neutral that contrasts with the overall brightness so the bars stay visible on the
		// (same-coloured) backdrop: dark bars on a light cover, white bars otherwise.
		val bar = if (lumaSum / total > 0.6) Color(0.15f, 0.15f, 0.15f) else Color.White
		return VisualizerPalette(listOf(0f to bar))
	}

	val colors = ranked.take(PALETTE_SIZE)
		.map { bucketColor(rSum[it], gSum[it], bSum[it], wSum[it]) }
		.toMutableList()
	// Pad by repeating the last real colour so flank/outer bars stay as bright as the centre bars.
	while (colors.size < PALETTE_SIZE) colors.add(colors.last())

	return VisualizerPalette(buildStops(colors))
}

// Small centred band for the main colour with solid flanks, so the main doesn't bleed across the arc.
private fun buildStops(colors: List<Color>): List<Pair<Float, Color>> {
	val main = colors[0]
	val left = colors[1]
	val right = colors[2]
	val centerHalf = 0.08f
	val flank = 0.28f
	return listOf(
		0f to left,
		flank to left,
		(0.5f - centerHalf) to main,
		(0.5f + centerHalf) to main,
		(1f - flank) to right,
		1f to right,
	)
}

private fun bucketColor(rSum: Double, gSum: Double, bSum: Double, wSum: Double): Color {
	val hsv = FloatArray(3)
	android.graphics.Color.RGBToHSV((rSum / wSum).toInt(), (gSum / wSum).toInt(), (bSum / wSum).toInt(), hsv)
	// Vivid, bright accents so the bars pop against the muted blurred backdrop.
	hsv[1] = (hsv[1] * 2.0f).coerceIn(0.5f, 1f)
	hsv[2] = hsv[2].coerceIn(0.82f, 1f)
	return Color(android.graphics.Color.HSVToColor(hsv))
}
