package org.jellyfin.playback.media3.exoplayer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time audio spectrum sourced by tapping the decoded PCM inside our own ExoPlayer audio sink
 * (via a passthrough [AudioProcessor]). This needs no RECORD_AUDIO permission because it reads the
 * audio we are decoding rather than capturing the system output.
 *
 * The tap only does FFT work while [enabled] is true (set by the UI that draws the visualizer), so
 * it is effectively free when nothing is visualizing.
 */
object AudioSpectrum {
	const val BAND_COUNT = 48
	const val WAVE_POINTS = 128

	private val _bands = MutableStateFlow(FloatArray(BAND_COUNT))

	/** Per-band magnitudes in 0..1, low to high frequency. */
	val bands: StateFlow<FloatArray> = _bands.asStateFlow()

	private val _waveform = MutableStateFlow(FloatArray(WAVE_POINTS))

	/** Downsampled time-domain waveform, roughly -1..1 (for an oscilloscope-style line). */
	val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

	// Reference count rather than a boolean: during a track change the screensaver briefly keeps both
	// the outgoing and incoming composables alive, so a boolean would be turned off by the departing
	// one right after the new one turned it on.
	private val activeCount = AtomicInteger(0)

	val enabled: Boolean get() = activeCount.get() > 0

	fun acquire() {
		activeCount.incrementAndGet()
	}

	fun release() {
		if (activeCount.decrementAndGet() <= 0) {
			activeCount.set(0)
			reset()
		}
	}

	internal fun publish(values: FloatArray) {
		_bands.value = values
	}

	internal fun publishWaveform(values: FloatArray) {
		_waveform.value = values
	}

	fun reset() {
		_bands.value = FloatArray(BAND_COUNT)
		_waveform.value = FloatArray(WAVE_POINTS)
	}
}

/**
 * A [DefaultRenderersFactory] that routes audio through a [SpectrumAudioProcessor]. The processor
 * forwards audio unchanged, so playback is unaffected.
 */
@UnstableApi
class AudioTapRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
	override fun buildAudioSink(
		context: Context,
		enableFloatOutput: Boolean,
		enableAudioTrackPlaybackParams: Boolean,
	): AudioSink = DefaultAudioSink.Builder(context)
		.setEnableFloatOutput(enableFloatOutput)
		.setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
		.setAudioProcessors(arrayOf<AudioProcessor>(SpectrumAudioProcessor()))
		.build()
}

/**
 * Passthrough audio processor that copies each PCM buffer through unchanged while accumulating
 * FFT-sized frames, computing a log-spaced magnitude spectrum and publishing it to [AudioSpectrum].
 * Runs on the audio thread; kept allocation-light (reused buffers, one small result array per frame).
 */
@UnstableApi
private class SpectrumAudioProcessor : BaseAudioProcessor() {
	private companion object {
		const val FFT_SIZE = 1024
		const val MIN_FREQ = 40f
		const val MAX_FREQ = 16000f
		const val DB_FLOOR = -60f
	}

	private var sampleRate = 44100
	private var channelCount = 2
	private var encoding = C.ENCODING_PCM_16BIT

	private val window = FloatArray(FFT_SIZE) { i ->
		0.5f * (1f - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat())
	}
	private val ring = FloatArray(FFT_SIZE)
	private var filled = 0
	private val re = FloatArray(FFT_SIZE)
	private val im = FloatArray(FFT_SIZE)

	override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
		sampleRate = inputAudioFormat.sampleRate
		channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
		encoding = inputAudioFormat.encoding
		filled = 0
		// Passthrough: output format == input format (keeps this processor active as a tap).
		return inputAudioFormat
	}

	override fun queueInput(inputBuffer: ByteBuffer) {
		val remaining = inputBuffer.remaining()
		if (remaining <= 0) return

		if (AudioSpectrum.enabled) analyze(inputBuffer)

		// Copy input straight to output unchanged.
		val output = replaceOutputBuffer(remaining)
		output.put(inputBuffer)
		output.flip()
	}

	private fun analyze(inputBuffer: ByteBuffer) {
		val ch = channelCount
		val b = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

		when (encoding) {
			C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2 * ch) {
				var sum = 0f
				for (c in 0 until ch) sum += b.short.toFloat() / 32768f
				push(sum / ch)
			}

			C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * ch) {
				var sum = 0f
				for (c in 0 until ch) sum += b.float
				push(sum / ch)
			}

			else -> return
		}
	}

	private fun push(sample: Float) {
		ring[filled++] = sample
		if (filled >= FFT_SIZE) {
			process()
			filled = 0
		}
	}

	private fun process() {
		// Downsampled raw waveform for the oscilloscope line (before windowing).
		val wavePoints = AudioSpectrum.WAVE_POINTS
		val waveform = FloatArray(wavePoints)
		val step = FFT_SIZE / wavePoints
		for (i in 0 until wavePoints) waveform[i] = ring[i * step].coerceIn(-1f, 1f)
		AudioSpectrum.publishWaveform(waveform)

		for (i in 0 until FFT_SIZE) {
			re[i] = ring[i] * window[i]
			im[i] = 0f
		}
		fft(re, im)

		val bands = FloatArray(AudioSpectrum.BAND_COUNT)
		val nyquist = sampleRate / 2f
		val maxFreq = min(MAX_FREQ, nyquist)
		val ratio = maxFreq / MIN_FREQ
		val half = FFT_SIZE / 2

		for (i in 0 until AudioSpectrum.BAND_COUNT) {
			val f0 = MIN_FREQ * pow(ratio, i.toFloat() / AudioSpectrum.BAND_COUNT)
			val f1 = MIN_FREQ * pow(ratio, (i + 1f) / AudioSpectrum.BAND_COUNT)
			val bin0 = (f0 * FFT_SIZE / sampleRate).toInt().coerceIn(1, half - 1)
			val bin1 = (f1 * FFT_SIZE / sampleRate).toInt().coerceIn(bin0 + 1, half)

			var mag = 0f
			for (bin in bin0 until bin1) mag += hypot(re[bin], im[bin])
			mag /= (bin1 - bin0)

			val normalized = mag / half
			val db = 20f * log10(normalized + 1e-6f)
			bands[i] = ((db - DB_FLOOR) / -DB_FLOOR).coerceIn(0f, 1f)
		}

		AudioSpectrum.publish(bands)
	}

	private fun pow(base: Float, exponent: Float): Float = exp(exponent * ln(base))

	/** In-place iterative radix-2 FFT. */
	private fun fft(re: FloatArray, im: FloatArray) {
		val n = re.size

		var j = 0
		for (i in 1 until n) {
			var bit = n shr 1
			while (j and bit != 0) {
				j = j xor bit
				bit = bit shr 1
			}
			j = j or bit
			if (i < j) {
				var t = re[i]; re[i] = re[j]; re[j] = t
				t = im[i]; im[i] = im[j]; im[j] = t
			}
		}

		var len = 2
		while (len <= n) {
			val ang = -2.0 * Math.PI / len
			val wr = cos(ang).toFloat()
			val wi = sin(ang).toFloat()
			var i = 0
			while (i < n) {
				var cr = 1f
				var ci = 0f
				val halfLen = len / 2
				for (k in 0 until halfLen) {
					val a = i + k
					val bIdx = a + halfLen
					val vr = re[bIdx] * cr - im[bIdx] * ci
					val vi = re[bIdx] * ci + im[bIdx] * cr
					re[bIdx] = re[a] - vr
					im[bIdx] = im[a] - vi
					re[a] += vr
					im[a] += vi
					val ncr = cr * wr - ci * wi
					ci = cr * wi + ci * wr
					cr = ncr
				}
				i += len
			}
			len = len shl 1
		}
	}
}
