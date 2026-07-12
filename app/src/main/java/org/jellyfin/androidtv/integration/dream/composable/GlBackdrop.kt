package org.jellyfin.androidtv.integration.dream.composable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.playback.media3.exoplayer.AudioSpectrum
import org.koin.compose.koinInject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole now-playing visual, rendered as a single OpenGL ES 2 pass on a [TextureView]: the cover
 * backdrop (with an optional bass-driven bulge/shake and an ld34 shockwave), plus the radial + centerOut
 * spectrum bars and the Catmull-smoothed oscilloscope soundwave, all as round-capped SDF capsules. This
 * replaces the per-frame Compose Canvas visualizer - a handful of GL draws with no per-frame brush
 * allocation - and reads the live [AudioSpectrum] on its own render thread. RuntimeShader is API 33+;
 * this app targets API 29, hence hand-written GLES.
 *
 * @param bulge apply the bass bulge/shake/shockwave to the backdrop (else a static cover).
 * @param visualizer draw the bars + soundwave.
 * @param centerOut mirror the spectrum around the arc's middle (bass in the centre).
 * @param palette nine floats: three RGB stops (left, main, right) for the bars/wave gradient.
 */
@Composable
fun GlBackdrop(
	url: String?,
	bulge: Boolean,
	visualizer: Boolean,
	centerOut: Boolean,
	palette: FloatArray,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	val view = remember { SceneTextureView(context) }

	view.setState(bulge, visualizer, centerOut, palette)

	LaunchedEffect(url) {
		if (url == null) return@LaunchedEffect
		val bmp = withContext(Dispatchers.IO) {
			runCatching {
				val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
				imageLoader.execute(request).image?.toBitmap()
			}.getOrNull()
		}
		if (bmp != null) view.setImage(bmp)
	}

	AndroidView(modifier = modifier, factory = { view }, onRelease = { it.release() })
}

private val BANDS = AudioSpectrum.BAND_COUNT
private const val WAVE_CTRL = 40      // control points along the soundwave
private const val WAVE_SUB = 6        // Catmull-Rom subdivisions between control points
private const val WAVE_GAIN = 2.5f
private const val HIGHLIGHT = 0.05f
private val HALF_ARC = Math.toRadians(37.0).toFloat()
private const val DIR_H = 0.5f

private class SceneTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
	@Volatile private var pendingBitmap: Bitmap? = null
	@Volatile private var bulge = false
	@Volatile private var visualizer = false
	@Volatile private var centerOut = true
	@Volatile private var palette = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
	private var thread: RenderThread? = null

	init {
		isOpaque = true
		surfaceTextureListener = this
	}

	fun setImage(bitmap: Bitmap) { pendingBitmap = bitmap }

	fun setState(bulge: Boolean, visualizer: Boolean, centerOut: Boolean, palette: FloatArray) {
		this.bulge = bulge
		this.visualizer = visualizer
		this.centerOut = centerOut
		if (palette.size == 9) this.palette = palette
	}

	fun release() {
		thread?.let { it.running = false; it.join(500) }
		thread = null
	}

	override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
		thread = RenderThread(surface, width, height).also { it.start() }
	}

	override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
		thread?.resize(width, height)
	}

	override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
		release()
		return true
	}

	override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

	private inner class RenderThread(
		private val surfaceTexture: SurfaceTexture,
		width: Int,
		height: Int,
	) : Thread("GlScene") {
		@Volatile var running = true
		@Volatile private var viewW = width
		@Volatile private var viewH = height

		private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
		private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
		private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

		// backdrop program
		private var bdProg = 0
		private var bdPos = 0
		private var uTex = 0
		private var uBass = 0
		private var uShock = 0
		private var uShockAmp = 0
		private var uTime = 0
		private var uAspect = 0
		private var uHighlight = 0
		private var uCrop = 0
		private lateinit var quad: FloatBuffer

		// capsule programs (bars, wave)
		private var barProg = 0
		private var waveProg = 0
		private val barLoc = CapLoc()
		private val waveLoc = CapLoc()

		private var texId = 0
		private var texW = 1
		private var texH = 1
		private var hasTexture = false

		// reused geometry buffers (no per-frame allocation)
		private val barArr = FloatArray(BANDS * 2 * 6 * 10)
		private val barBuf = directBuffer(barArr.size)
		private var barCount = 0
		private val waveArrL = FloatArray(WAVE_CTRL * WAVE_SUB * 6 * 8)
		private val waveArrR = FloatArray(WAVE_CTRL * WAVE_SUB * 6 * 8)
		private val waveBufL = directBuffer(waveArrL.size)
		private val waveBufR = directBuffer(waveArrR.size)
		private var waveCountL = 0
		private var waveCountR = 0
		private var waveAlpha = 0f
		private var lastWaveNanos = 0L

		private val cur = FloatArray(BANDS)
		private var bassState = 0f
		private var beat = 0f
		private var phase = 0f
		private var sraw = 0f
		private var prevSraw = 0f
		private var avg = 0f
		private var sinceShock = 1e9f
		private var shockAge = 1e9f
		private var shockAmp = 0f
		private var startNanos = 0L
		private var lastNanos = 0L

		fun resize(w: Int, h: Int) { viewW = w; viewH = h }

		override fun run() {
			try {
				initEgl()
				initGl()
				startNanos = System.nanoTime()
				lastNanos = startNanos
				while (running) {
					pendingBitmap?.let { bmp -> pendingBitmap = null; uploadTexture(bmp) }
					step()
					EGL14.eglSwapBuffers(eglDisplay, eglSurface)  // blocks on vsync
				}
			} catch (_: Throwable) {
				// Never crash the screensaver on a GL error; just stop rendering.
			} finally {
				releaseEgl()
			}
		}

		private fun step() {
			val now = System.nanoTime()
			val t = (now - startNanos) / 1e9f
			val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
			lastNanos = now

			// Smooth the spectrum (fast attack, slow release), as the Compose visualizer did.
			val target = AudioSpectrum.bands.value
			for (i in cur.indices) {
				val tv = if (i < target.size) target[i] else 0f
				cur[i] = if (tv > cur[i]) tv else cur[i] * 0.82f + tv * 0.18f
			}

			val raw = bassLevel(cur)
			avg = avg * 0.93f + raw * 0.07f
			val transient = max(0f, raw - avg)
			val drive = min(1f, transient * 2.2f)
			bassState = beatFollow(bassState, drive, 0.78f)
			beat = beatFollow(beat, raw, 0.90f)
			phase += 0.12f

			sraw = beatFollow(sraw, raw, 0.55f)
			val slope = sraw - prevSraw
			prevSraw = sraw
			sinceShock += dt
			if (slope > 0.008f && sinceShock >= 0.12f) {
				shockAge = 0f
				shockAmp = min(1f, sraw * 1.6f)
				sinceShock = 0f
			}
			shockAge += dt
			val shock = min(shockAge / 0.55f, 1f)
			if (shock >= 1f) shockAmp = 0f

			val w = viewW.toFloat()
			val h = viewH.toFloat()
			GLES20.glViewport(0, 0, viewW, viewH)
			GLES20.glClearColor(0f, 0f, 0f, 1f)
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
			if (!hasTexture || viewW <= 0 || viewH <= 0) return

			val surfAspect = w / h
			val texAspect = if (texH > 0) texW.toFloat() / texH else 1f
			var cropX = 1f
			var cropY = 1f
			if (surfAspect > texAspect) cropY = texAspect / surfAspect else cropX = surfAspect / texAspect

			// --- backdrop ---
			GLES20.glUseProgram(bdProg)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
			GLES20.glUniform1i(uTex, 0)
			GLES20.glUniform1f(uBass, if (bulge) bassState else 0f)
			GLES20.glUniform1f(uShock, shock)
			GLES20.glUniform1f(uShockAmp, if (bulge) shockAmp else 0f)
			GLES20.glUniform1f(uTime, t)
			GLES20.glUniform1f(uAspect, surfAspect)
			GLES20.glUniform1f(uHighlight, HIGHLIGHT)
			GLES20.glUniform2f(uCrop, cropX, cropY)
			quad.position(0)
			GLES20.glEnableVertexAttribArray(bdPos)
			GLES20.glVertexAttribPointer(bdPos, 2, GLES20.GL_FLOAT, false, 0, quad)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
			GLES20.glDisableVertexAttribArray(bdPos)

			if (!visualizer) return

			// --- bars ---
			barCount = buildBars(w, h)
			if (barCount > 0) {
				barBuf.clear(); barBuf.put(barArr, 0, barCount * 10); barBuf.position(0)
				GLES20.glUseProgram(barProg)
				setPalette(barProg)
				GLES20.glUniform2f(GLES20.glGetUniformLocation(barProg, "uRes"), w, h)
				drawCapsules(barBuf, barLoc, 10, barCount, bars = true)
			}

			// --- soundwave --- (rebuilt ~15x/s, held between, like Android's per-hop update)
			if ((now - lastWaveNanos) / 1e9f >= 1f / 15f) {
				lastWaveNanos = now
				buildWave(w, h)
			}
			if (waveAlpha > 0.001f) {
				GLES20.glUseProgram(waveProg)
				setPalette(waveProg)
				GLES20.glUniform2f(GLES20.glGetUniformLocation(waveProg, "uRes"), w, h)
				GLES20.glUniform1f(GLES20.glGetUniformLocation(waveProg, "uWaveAlpha"), waveAlpha)
				if (waveCountL > 0) {
					waveBufL.clear(); waveBufL.put(waveArrL, 0, waveCountL * 8); waveBufL.position(0)
					drawCapsules(waveBufL, waveLoc, 8, waveCountL, bars = false)
				}
				if (waveCountR > 0) {
					waveBufR.clear(); waveBufR.put(waveArrR, 0, waveCountR * 8); waveBufR.position(0)
					drawCapsules(waveBufR, waveLoc, 8, waveCountR, bars = false)
				}
			}
		}

		private fun setPalette(prog: Int) {
			val p = palette
			GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uPal0"), p[0], p[1], p[2])
			GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uPal1"), p[3], p[4], p[5])
			GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uPal2"), p[6], p[7], p[8])
		}

		private fun drawCapsules(buf: FloatBuffer, loc: CapLoc, stride: Int, verts: Int, bars: Boolean) {
			val sb = stride * 4
			buf.position(0); GLES20.glEnableVertexAttribArray(loc.pos)
			GLES20.glVertexAttribPointer(loc.pos, 2, GLES20.GL_FLOAT, false, sb, buf)
			buf.position(2); GLES20.glEnableVertexAttribArray(loc.a)
			GLES20.glVertexAttribPointer(loc.a, 2, GLES20.GL_FLOAT, false, sb, buf)
			buf.position(4); GLES20.glEnableVertexAttribArray(loc.b)
			GLES20.glVertexAttribPointer(loc.b, 2, GLES20.GL_FLOAT, false, sb, buf)
			buf.position(6); GLES20.glEnableVertexAttribArray(loc.radius)
			GLES20.glVertexAttribPointer(loc.radius, 1, GLES20.GL_FLOAT, false, sb, buf)
			if (bars) {
				buf.position(7); GLES20.glEnableVertexAttribArray(loc.lenReach)
				GLES20.glVertexAttribPointer(loc.lenReach, 1, GLES20.GL_FLOAT, false, sb, buf)
				buf.position(8); GLES20.glEnableVertexAttribArray(loc.frac)
				GLES20.glVertexAttribPointer(loc.frac, 1, GLES20.GL_FLOAT, false, sb, buf)
				buf.position(9); GLES20.glEnableVertexAttribArray(loc.pulse)
				GLES20.glVertexAttribPointer(loc.pulse, 1, GLES20.GL_FLOAT, false, sb, buf)
			} else {
				buf.position(7); GLES20.glEnableVertexAttribArray(loc.t)
				GLES20.glVertexAttribPointer(loc.t, 1, GLES20.GL_FLOAT, false, sb, buf)
			}
			GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts)
			GLES20.glDisableVertexAttribArray(loc.pos)
			GLES20.glDisableVertexAttribArray(loc.a)
			GLES20.glDisableVertexAttribArray(loc.b)
			GLES20.glDisableVertexAttribArray(loc.radius)
			if (bars) {
				GLES20.glDisableVertexAttribArray(loc.lenReach)
				GLES20.glDisableVertexAttribArray(loc.frac)
				GLES20.glDisableVertexAttribArray(loc.pulse)
			} else {
				GLES20.glDisableVertexAttribArray(loc.t)
			}
		}

		// ---- geometry (ported from the validated preview) ----

		private fun frameLimit(px: Float, py: Float, dx: Float, dy: Float, w: Float, h: Float, m: Float): Float {
			var t = Float.MAX_VALUE
			if (dx > 1e-4f) t = min(t, (w - m - px) / dx) else if (dx < -1e-4f) t = min(t, (m - px) / dx)
			if (dy > 1e-4f) t = min(t, (h - m - py) / dy) else if (dy < -1e-4f) t = min(t, (m - py) / dy)
			return max(0f, t)
		}

		/** Fills [barArr]; returns the vertex count. */
		private fun buildBars(w: Float, h: Float): Int {
			val n = BANDS
			val cx = w / 2f
			val cy = h / 2f
			val coverHalf = h * 0.5f
			val a = coverHalf * 0.80f
			val b = coverHalf * 1.48f
			val maxLen = h * 0.52f
			val xGap = h * 0.10f
			val margin = h * 0.035f
			val r = max(4f, h / (n * 1.6f)) / 2f
			var cur2 = 0
			for (slot in 0 until n) {
				val d = if (n > 1) abs(slot - (n - 1) / 2f) / ((n - 1) / 2f) else 0f
				val band = if (centerOut) (d * (n - 1)).toInt().coerceIn(0, n - 1) else slot
				val v = cur[band]
				if (v <= 0.01f) continue
				val frac = if (n > 1) slot.toFloat() / (n - 1) else 0.5f
				val pulse = (0.12f * (0.5f + 0.5f * sin(phase + slot * 0.55f)) + 0.45f * beat).coerceIn(0f, 0.7f)
				val tt = -HALF_ARC + frac * (2f * HALF_ARC)
				val bx = a * cos(tt)
				val by = b * sin(tt)
				val dist = hypot(bx, by).coerceAtLeast(1f)
				val nx = bx / dist
				val ny = by / dist
				for (side in intArrayOf(1, -1)) {
					var dx = side * nx * (1f - DIR_H) + side * DIR_H
					var dy = ny * (1f - DIR_H)
					val dl = hypot(dx, dy).coerceAtLeast(1e-4f)
					dx /= dl; dy /= dl
					val baseX = cx + side * (bx + xGap)
					val baseY = cy + by
					val fl = frameLimit(baseX, baseY, dx, dy, w, h, margin)
					val ln = min(v * maxLen, fl)
					if (ln <= 0f) continue
					val maxReach = min(maxLen, fl).coerceAtLeast(1f)
					cur2 = capsule(barArr, cur2, baseX, baseY, baseX + dx * ln, baseY + dy * ln, r,
						floatArrayOf(ln / maxReach, frac, pulse))
				}
			}
			return cur2 / 10
		}

		private fun buildWave(w: Float, h: Float) {
			val wf = AudioSpectrum.waveform.value
			val ctrl = FloatArray(WAVE_CTRL)
			var peak = 0f
			if (wf.size >= WAVE_CTRL) {
				val block = wf.size / WAVE_CTRL
				for (i in 0 until WAVE_CTRL) {
					var s = 0f
					for (j in 0 until block) s += wf[i * block + j]
					val vv = (s / block * WAVE_GAIN).coerceIn(-1f, 1f)
					ctrl[i] = vv
					if (abs(vv) > peak) peak = abs(vv)
				}
			}
			val fade = min(1f, peak / 0.03f)
			waveAlpha = if (peak > 0.005f) fade * min(1f, 0.6f + peak * 2.5f) else 0f

			val cx = w / 2f
			val cy = h / 2f
			val coverHalf = h * 0.5f
			val a = coverHalf * 0.80f
			val b = coverHalf * 1.48f
			val xGap = h * 0.10f
			val waveAmp = h * 0.056f
			val r = max(4f, h / (BANDS * 1.6f)) / 2f
			for (side in intArrayOf(1, -1)) {
				// arc points with the wave offset
				val px = FloatArray(WAVE_CTRL)
				val py = FloatArray(WAVE_CTRL)
				for (k in 0 until WAVE_CTRL) {
					val frac = k.toFloat() / (WAVE_CTRL - 1)
					val tt = -HALF_ARC + frac * (2f * HALF_ARC)
					val bx = a * cos(tt)
					val by = b * sin(tt)
					val dist = hypot(bx, by).coerceAtLeast(1f)
					var dx = (bx / dist) * side * (1f - DIR_H) + side * DIR_H
					var dy = (by / dist) * (1f - DIR_H)
					val dl = hypot(dx, dy).coerceAtLeast(1e-4f)
					dx /= dl; dy /= dl
					val off = ctrl[k] * waveAmp
					px[k] = cx + side * (bx + xGap) + dx * off
					py[k] = cy + by + dy * off
				}
				// Catmull-Rom upsample into a smooth curve, then capsule segments
				val arr = if (side == 1) waveArrL else waveArrR
				var cur2 = 0
				var prevX = px[0]
				var prevY = py[0]
				var first = true
				val total = (WAVE_CTRL - 1) * WAVE_SUB
				var idx = 0
				for (i in 0 until WAVE_CTRL - 1) {
					val p0x = if (i > 0) px[i - 1] else px[i]
					val p0y = if (i > 0) py[i - 1] else py[i]
					val p3x = if (i + 2 < WAVE_CTRL) px[i + 2] else px[i + 1]
					val p3y = if (i + 2 < WAVE_CTRL) py[i + 2] else py[i + 1]
					for (s in 0 until WAVE_SUB) {
						val u = s.toFloat() / WAVE_SUB
						val cxp = catmull(p0x, px[i], px[i + 1], p3x, u)
						val cyp = catmull(p0y, py[i], py[i + 1], p3y, u)
						if (!first) {
							cur2 = capsule(arr, cur2, prevX, prevY, cxp, cyp, r,
								floatArrayOf(idx.toFloat() / total))
						}
						prevX = cxp; prevY = cyp; first = false; idx++
					}
				}
				// final point
				cur2 = capsule(arr, cur2, prevX, prevY, px[WAVE_CTRL - 1], py[WAVE_CTRL - 1], r,
					floatArrayOf(1f))
				if (side == 1) waveCountL = cur2 / 8 else waveCountR = cur2 / 8
			}
		}

		// ---- EGL / GL setup ----

		private fun initEgl() {
			eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
			val version = IntArray(2)
			EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
			val cfg = intArrayOf(
				EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
				EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
				EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_NONE,
			)
			val configs = arrayOfNulls<EGLConfig>(1)
			val num = IntArray(1)
			EGL14.eglChooseConfig(eglDisplay, cfg, 0, configs, 0, 1, num, 0)
			val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
			eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
			eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
			EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
		}

		private fun initGl() {
			GLES20.glEnable(GLES20.GL_BLEND)
			GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

			bdProg = buildProgram(BD_VERT, BD_FRAG)
			bdPos = GLES20.glGetAttribLocation(bdProg, "aPos")
			uTex = GLES20.glGetUniformLocation(bdProg, "uTex")
			uBass = GLES20.glGetUniformLocation(bdProg, "uBass")
			uShock = GLES20.glGetUniformLocation(bdProg, "uShock")
			uShockAmp = GLES20.glGetUniformLocation(bdProg, "uShockAmp")
			uTime = GLES20.glGetUniformLocation(bdProg, "uTime")
			uAspect = GLES20.glGetUniformLocation(bdProg, "uAspect")
			uHighlight = GLES20.glGetUniformLocation(bdProg, "uHighlight")
			uCrop = GLES20.glGetUniformLocation(bdProg, "uCrop")

			barProg = buildProgram(CAP_VERT_BAR, CAP_FRAG_BAR)
			barLoc.bind(barProg, bars = true)
			waveProg = buildProgram(CAP_VERT_WAVE, CAP_FRAG_WAVE)
			waveLoc.bind(waveProg, bars = false)

			val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
			quad = directBuffer(v.size).also { it.put(v); it.position(0) }

			val ids = IntArray(1)
			GLES20.glGenTextures(1, ids, 0)
			texId = ids[0]
		}

		private fun uploadTexture(bitmap: Bitmap) {
			texW = bitmap.width
			texH = bitmap.height
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
			GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
			hasTexture = true
		}

		private fun buildProgram(vs: String, fs: String): Int {
			val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { GLES20.glShaderSource(it, vs); GLES20.glCompileShader(it) }
			val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { GLES20.glShaderSource(it, fs); GLES20.glCompileShader(it) }
			return GLES20.glCreateProgram().also {
				GLES20.glAttachShader(it, v); GLES20.glAttachShader(it, f); GLES20.glLinkProgram(it)
				GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
			}
		}

		private fun releaseEgl() {
			if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
				EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
				if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
				if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
				EGL14.eglTerminate(eglDisplay)
			}
			eglDisplay = EGL14.EGL_NO_DISPLAY
			eglContext = EGL14.EGL_NO_CONTEXT
			eglSurface = EGL14.EGL_NO_SURFACE
		}
	}

	private class CapLoc {
		var pos = 0; var a = 0; var b = 0; var radius = 0
		var lenReach = 0; var frac = 0; var pulse = 0; var t = 0
		fun bind(prog: Int, bars: Boolean) {
			pos = GLES20.glGetAttribLocation(prog, "aPosPx")
			a = GLES20.glGetAttribLocation(prog, "aA")
			b = GLES20.glGetAttribLocation(prog, "aB")
			radius = GLES20.glGetAttribLocation(prog, "aRadius")
			if (bars) {
				lenReach = GLES20.glGetAttribLocation(prog, "aLenReach")
				frac = GLES20.glGetAttribLocation(prog, "aFrac")
				pulse = GLES20.glGetAttribLocation(prog, "aPulse")
			} else {
				t = GLES20.glGetAttribLocation(prog, "aT")
			}
		}
	}
}

private fun directBuffer(floats: Int): FloatBuffer =
	ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
	val t2 = t * t
	val t3 = t2 * t
	return 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
}

/** Writes a round-capped segment a->b of radius r into [arr] at cursor [cur]; returns the new cursor. */
private fun capsule(arr: FloatArray, cur: Int, ax: Float, ay: Float, bx: Float, by: Float, r: Float, extra: FloatArray): Int {
	val dx = bx - ax
	val dy = by - ay
	val ln = hypot(dx, dy).coerceAtLeast(1e-4f)
	val ux = dx / ln
	val uy = dy / ln
	val px = -uy
	val py = ux
	val a0x = ax - ux * r; val a0y = ay - uy * r
	val b0x = bx + ux * r; val b0y = by + uy * r
	val cxs = floatArrayOf(a0x - px * r, b0x - px * r, a0x + px * r, b0x + px * r)
	val cys = floatArrayOf(a0y - py * r, b0y - py * r, a0y + py * r, b0y + py * r)
	var c = cur
	for (i in intArrayOf(0, 1, 2, 1, 3, 2)) {
		arr[c++] = cxs[i]; arr[c++] = cys[i]
		arr[c++] = ax; arr[c++] = ay
		arr[c++] = bx; arr[c++] = by
		arr[c++] = r
		for (e in extra) arr[c++] = e
	}
	return c
}

// ---- shaders (GLES 2) ----

private const val BD_VERT = """
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
private const val BD_FRAG = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform float uBass, uShock, uShockAmp, uTime, uAspect, uHighlight;
uniform vec2 uCrop;
vec2 crop(vec2 uv) { vec2 t = (uv - 0.5) * uCrop + 0.5; return vec2(t.x, 1.0 - t.y); }
void main() {
    vec2 c = vec2(0.5);
    vec2 uv = vUv;
    uv += vec2(sin(uTime * 91.0), cos(uTime * 77.0)) * (uBass * 0.007);
    vec2 d = uv - c;
    vec2 dd = vec2(d.x * uAspect, d.y);
    float dist = length(dd);
    float fall = 1.0 - smoothstep(0.0, 0.55, dist);
    uv = c + (uv - c) * (1.0 - uBass * (0.04 + 0.11 * fall));
    d = uv - c; dd = vec2(d.x * uAspect, d.y); dist = length(dd);
    float ld34Hl = 0.0;
    if (uShockAmp > 0.001) {
        float t = uShock * 0.9;
        float width = 0.1;
        if (dist <= t + width && dist >= t - width) {
            float diff = dist - t;
            float powDiff = 1.0 - pow(abs(diff * 8.0), 0.8);
            uv += normalize(d + 1e-5) * (diff * powDiff) * 0.7 * uShockAmp * (1.0 - uShock);
        }
        ld34Hl = exp(-pow((dist - t) / (width * 0.6), 2.0)) * uShockAmp * (1.0 - uShock);
    }
    float m = max(abs(vUv.x - 0.5), abs(vUv.y - 0.5));
    float edge = smoothstep(0.5, 0.42, m);
    uv = vUv + (uv - vUv) * edge;
    vec3 col = texture2D(uTex, crop(uv)).rgb;
    col += ld34Hl * uHighlight * edge;
    gl_FragColor = vec4(col, 1.0);
}
"""

private const val CAP_VERT_BAR = """
attribute vec2 aPosPx; attribute vec2 aA; attribute vec2 aB; attribute float aRadius;
attribute float aLenReach; attribute float aFrac; attribute float aPulse;
uniform vec2 uRes;
varying vec2 vPos; varying vec2 vA; varying vec2 vB; varying float vRad;
varying float vLenReach; varying float vFrac; varying float vPulse;
void main() {
    vPos = aPosPx; vA = aA; vB = aB; vRad = aRadius; vLenReach = aLenReach; vFrac = aFrac; vPulse = aPulse;
    vec2 ndc = vec2(aPosPx.x / uRes.x * 2.0 - 1.0, 1.0 - aPosPx.y / uRes.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
"""
private const val CAP_FRAG_BAR = """
precision mediump float;
varying vec2 vPos; varying vec2 vA; varying vec2 vB; varying float vRad;
varying float vLenReach; varying float vFrac; varying float vPulse;
uniform vec3 uPal0, uPal1, uPal2;
vec3 pal(float f) { return f < 0.5 ? mix(uPal0, uPal1, f * 2.0) : mix(uPal1, uPal2, (f - 0.5) * 2.0); }
void main() {
    vec2 pa = vPos - vA; vec2 ba = vB - vA;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-4), 0.0, 1.0);
    float dseg = length(pa - ba * h);
    float aa = 1.0 - smoothstep(vRad - 1.0, vRad + 0.5, dseg);
    if (aa <= 0.0) discard;
    float b = clamp(smoothstep(0.4, 1.0, h * vLenReach) * vPulse, 0.0, 1.0);
    gl_FragColor = vec4(mix(pal(vFrac), vec3(1.0), b), aa);
}
"""

private const val CAP_VERT_WAVE = """
attribute vec2 aPosPx; attribute vec2 aA; attribute vec2 aB; attribute float aRadius; attribute float aT;
uniform vec2 uRes;
varying vec2 vPos; varying vec2 vA; varying vec2 vB; varying float vRad; varying float vT;
void main() {
    vPos = aPosPx; vA = aA; vB = aB; vRad = aRadius; vT = aT;
    vec2 ndc = vec2(aPosPx.x / uRes.x * 2.0 - 1.0, 1.0 - aPosPx.y / uRes.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
"""
private const val CAP_FRAG_WAVE = """
precision mediump float;
varying vec2 vPos; varying vec2 vA; varying vec2 vB; varying float vRad; varying float vT;
uniform vec3 uPal0, uPal1, uPal2;
uniform float uWaveAlpha;
vec3 pal(float f) { return f < 0.5 ? mix(uPal0, uPal1, f * 2.0) : mix(uPal1, uPal2, (f - 0.5) * 2.0); }
void main() {
    vec2 pa = vPos - vA; vec2 ba = vB - vA;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-4), 0.0, 1.0);
    float dseg = length(pa - ba * h);
    float aa = 1.0 - smoothstep(vRad - 1.0, vRad + 0.5, dseg);
    if (aa <= 0.0) discard;
    gl_FragColor = vec4(pal(vT), aa * uWaveAlpha);
}
"""
