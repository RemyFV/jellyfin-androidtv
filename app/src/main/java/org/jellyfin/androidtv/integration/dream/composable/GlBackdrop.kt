package org.jellyfin.androidtv.integration.dream.composable

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
 * The whole now-playing visual, rendered as a single OpenGL ES 2 pass on a [SurfaceView]: the cover
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
	val view = remember { SceneSurfaceView(context) }

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

/** Live GL render stats, surfaced in the FPS counter for tuning. */
object GlStats {
	@Volatile var renderW = 0
	@Volatile var renderH = 0
	@Volatile var fps = 0   // measured on the GL render thread (authoritative)
}

private val BANDS = AudioSpectrum.BAND_COUNT
private const val WAVE_CTRL = 40      // control points along the soundwave
private const val WAVE_SUB = 3        // Catmull-Rom subdivisions between control points (less overdraw)
private const val WAVE_GAIN = 2.5f
private const val HIGHLIGHT = 0.05f
private val HALF_ARC = Math.toRadians(37.0).toFloat()
private const val DIR_H = 0.5f

// Adaptive render-resolution ladder (longest side, px). Starts high and settles at the highest step that
// sustains the target framerate on whatever GPU this runs on; the SurfaceView upscales to the panel.
private val RES_STEPS = intArrayOf(1280, 1080, 960, 854, 720)
private const val RES_START = 0  // top step; only ever scales DOWN (upsizing the surface wedges swap on Amlogic)

private fun capTo(w: Int, h: Int, cap: Int): Pair<Int, Int> {
	val longest = max(w, h)
	if (longest <= cap || longest == 0) return w to h
	val s = cap.toFloat() / longest
	return max(1, (w * s).toInt()) to max(1, (h * s).toInt())
}

// Hoisted so the per-frame geometry builders don't allocate these every bar/capsule.
private val SIDES = intArrayOf(1, -1)
private val TRI_IDX = intArrayOf(0, 1, 2, 1, 3, 2)

private class SceneSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
	@Volatile private var pendingBitmap: Bitmap? = null
	@Volatile private var bulge = false
	@Volatile private var visualizer = false
	@Volatile private var centerOut = true
	@Volatile private var palette = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
	private var thread: RenderThread? = null

	init {
		// Default z-order: the surface sits behind the window, so the Compose text/clock draw on top.
		holder.addCallback(this)
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

	override fun surfaceCreated(holder: SurfaceHolder) = Unit  // wait for surfaceChanged (has the size)

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
		// Buffer size (incl. adaptive setFixedSize changes) arrives here; the render thread owns the cap.
		if (thread == null) thread = RenderThread(holder.surface, width, height).also { it.start() }
		else thread?.resize(width, height)
	}

	override fun surfaceDestroyed(holder: SurfaceHolder) {
		release()
	}

	private inner class RenderThread(
		private val surface: Surface,
		width: Int,
		height: Int,
	) : Thread("GlScene") {
		@Volatile var running = true
		@Volatile private var viewW = width
		@Volatile private var viewH = height

		private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
		private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
		private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

		// backdrop programs (full bulge/shockwave shader, and a plain textured one for when pulse is off)
		private var bdProg = 0
		private var bdPlainProg = 0
		private var bdpPos = 0
		private var bdpTex = 0
		private var bdpCrop = 0
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
		private var wPos = 0
		private var wEdge = 0
		private var wT = 0
		private var wURes = 0
		private var wUPal0 = 0
		private var wUPal1 = 0
		private var wUPal2 = 0
		private var wUWaveAlpha = 0

		private var texId = 0
		private var texW = 1
		private var texH = 1
		private var hasTexture = false
		private var barVbo = 0
		private var waveVbo = 0

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

		// Adaptive resolution state (ratchet-down-only: upsizing the surface wedges swap on Amlogic)
		private var stepIndex = RES_START
		private var winFrames = 0
		private var winTime = 0f
		private var slowWins = 0

		fun resize(w: Int, h: Int) { viewW = w; viewH = h; GlStats.renderW = w; GlStats.renderH = h }

		private fun applyRes() {
			val nw = this@SceneSurfaceView.width
			val nh = this@SceneSurfaceView.height
			if (nw <= 0 || nh <= 0) return
			val (rw, rh) = capTo(nw, nh, RES_STEPS[stepIndex])
			this@SceneSurfaceView.post { runCatching { holder.setFixedSize(rw, rh) } }
		}

		// Achieved-FPS controller (eglSwapBuffers blocks on vsync, so swaps/sec reflects GPU load).
		private fun adapt(dt: Float) {
			winFrames++
			winTime += dt
			if (winTime < 1f) return
			val fps = winFrames / winTime
			winFrames = 0
			winTime = 0f
			GlStats.fps = (fps + 0.5f).toInt()
			if (fps < 40f) {
				slowWins++
				if (slowWins >= 2 && stepIndex < RES_STEPS.size - 1) { stepIndex++; applyRes(); slowWins = 0 }
			} else {
				slowWins = 0
			}
		}

		override fun run() {
			try {
				initEgl()
				initGl()
				applyRes()  // request the initial (top-of-ladder) capped resolution
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
			adapt(dt)

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

			// --- backdrop --- opaque, so blend off (avoids a no-op fullscreen read-modify-write)
			GLES20.glDisable(GLES20.GL_BLEND)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
			if (bulge) {
				GLES20.glUseProgram(bdProg)
				GLES20.glUniform1i(uTex, 0)
				GLES20.glUniform1f(uBass, bassState)
				GLES20.glUniform1f(uShock, shock)
				GLES20.glUniform1f(uShockAmp, shockAmp)
				GLES20.glUniform1f(uTime, t)
				GLES20.glUniform1f(uAspect, surfAspect)
				GLES20.glUniform1f(uHighlight, HIGHLIGHT)
				GLES20.glUniform2f(uCrop, cropX, cropY)
				drawQuad(bdPos)
			} else {
				// Pulse off: a trivial textured+crop shader, none of the bulge/shake/edge math.
				GLES20.glUseProgram(bdPlainProg)
				GLES20.glUniform1i(bdpTex, 0)
				GLES20.glUniform2f(bdpCrop, cropX, cropY)
				drawQuad(bdpPos)
			}

			if (!visualizer) return
			GLES20.glEnable(GLES20.GL_BLEND)

			// --- bars ---
			barCount = buildBars(w, h)
			if (barCount > 0) {
				barBuf.clear(); barBuf.put(barArr, 0, barCount * 10); barBuf.position(0)
				GLES20.glUseProgram(barProg)
				setPalette(barLoc)
				GLES20.glUniform2f(barLoc.uRes, w, h)
				GLES20.glUniform1f(barLoc.uInvH, 8f / h)   // scale coords into mediump's sweet spot
				GLES20.glUniform1f(barLoc.uAA, 22f / h)     // wider AA band (~2.7px) to soften the upscale
				drawCapsules(barVbo, barBuf, barCount * 10, barLoc, 10, barCount, bars = true)
			}

			// --- soundwave --- (rebuilt ~15x/s, held between, like Android's per-hop update)
			if ((now - lastWaveNanos) / 1e9f >= 1f / 15f) {
				lastWaveNanos = now
				buildWave(w, h)
			}
			if (waveAlpha > 0.001f) {
				GLES20.glUseProgram(waveProg)
				val p = palette
				GLES20.glUniform3f(wUPal0, p[0], p[1], p[2])
				GLES20.glUniform3f(wUPal1, p[3], p[4], p[5])
				GLES20.glUniform3f(wUPal2, p[6], p[7], p[8])
				GLES20.glUniform2f(wURes, w, h)
				GLES20.glUniform1f(wUWaveAlpha, waveAlpha)
				if (waveCountL > 0) {
					waveBufL.clear(); waveBufL.put(waveArrL, 0, waveCountL * 4); waveBufL.position(0)
					drawWaveStrip(waveBufL, waveCountL * 4, waveCountL)
				}
				if (waveCountR > 0) {
					waveBufR.clear(); waveBufR.put(waveArrR, 0, waveCountR * 4); waveBufR.position(0)
					drawWaveStrip(waveBufR, waveCountR * 4, waveCountR)
				}
			}
		}

		private fun setPalette(loc: CapLoc) {
			val p = palette
			GLES20.glUniform3f(loc.uPal0, p[0], p[1], p[2])
			GLES20.glUniform3f(loc.uPal1, p[3], p[4], p[5])
			GLES20.glUniform3f(loc.uPal2, p[6], p[7], p[8])
		}

		private fun drawCapsules(vbo: Int, staging: FloatBuffer, floatCount: Int, loc: CapLoc, stride: Int, verts: Int, bars: Boolean) {
			// Upload to a VBO and address attributes by explicit byte offset: interleaved client-side
			// arrays (buffer.position() per attribute) are unreliable on Android and made vA==vB (circles).
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
			staging.position(0)
			GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floatCount * 4, staging, GLES20.GL_DYNAMIC_DRAW)
			val sb = stride * 4
			GLES20.glEnableVertexAttribArray(loc.pos); GLES20.glVertexAttribPointer(loc.pos, 2, GLES20.GL_FLOAT, false, sb, 0)
			GLES20.glEnableVertexAttribArray(loc.a); GLES20.glVertexAttribPointer(loc.a, 2, GLES20.GL_FLOAT, false, sb, 2 * 4)
			GLES20.glEnableVertexAttribArray(loc.b); GLES20.glVertexAttribPointer(loc.b, 2, GLES20.GL_FLOAT, false, sb, 4 * 4)
			GLES20.glEnableVertexAttribArray(loc.radius); GLES20.glVertexAttribPointer(loc.radius, 1, GLES20.GL_FLOAT, false, sb, 6 * 4)
			if (bars) {
				GLES20.glEnableVertexAttribArray(loc.lenReach); GLES20.glVertexAttribPointer(loc.lenReach, 1, GLES20.GL_FLOAT, false, sb, 7 * 4)
				GLES20.glEnableVertexAttribArray(loc.frac); GLES20.glVertexAttribPointer(loc.frac, 1, GLES20.GL_FLOAT, false, sb, 8 * 4)
				GLES20.glEnableVertexAttribArray(loc.pulse); GLES20.glVertexAttribPointer(loc.pulse, 1, GLES20.GL_FLOAT, false, sb, 9 * 4)
			} else {
				GLES20.glEnableVertexAttribArray(loc.t); GLES20.glVertexAttribPointer(loc.t, 1, GLES20.GL_FLOAT, false, sb, 7 * 4)
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
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
		}

		private fun drawQuad(pos: Int) {
			quad.position(0)
			GLES20.glEnableVertexAttribArray(pos)
			GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
			GLES20.glDisableVertexAttribArray(pos)
		}

		private fun drawWaveStrip(staging: FloatBuffer, floatCount: Int, verts: Int) {
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, waveVbo)
			staging.position(0)
			GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floatCount * 4, staging, GLES20.GL_DYNAMIC_DRAW)
			val sb = 4 * 4
			GLES20.glEnableVertexAttribArray(wPos); GLES20.glVertexAttribPointer(wPos, 2, GLES20.GL_FLOAT, false, sb, 0)
			GLES20.glEnableVertexAttribArray(wEdge); GLES20.glVertexAttribPointer(wEdge, 1, GLES20.GL_FLOAT, false, sb, 2 * 4)
			GLES20.glEnableVertexAttribArray(wT); GLES20.glVertexAttribPointer(wT, 1, GLES20.GL_FLOAT, false, sb, 3 * 4)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, verts)
			GLES20.glDisableVertexAttribArray(wPos)
			GLES20.glDisableVertexAttribArray(wEdge)
			GLES20.glDisableVertexAttribArray(wT)
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
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
				for (side in SIDES) {
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
						ln / maxReach, frac, pulse)
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
			val dense = (WAVE_CTRL - 1) * WAVE_SUB + 1
			for (side in SIDES) {
				// control points along the arc, offset by the wave sample
				val cpx = FloatArray(WAVE_CTRL)
				val cpy = FloatArray(WAVE_CTRL)
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
					cpx[k] = cx + side * (bx + xGap) + dx * off
					cpy[k] = cy + by + dy * off
				}
				// Catmull-Rom upsample into a smooth dense curve
				val dpx = FloatArray(dense)
				val dpy = FloatArray(dense)
				var di = 0
				for (i in 0 until WAVE_CTRL - 1) {
					val p0x = if (i > 0) cpx[i - 1] else cpx[i]
					val p0y = if (i > 0) cpy[i - 1] else cpy[i]
					val p3x = if (i + 2 < WAVE_CTRL) cpx[i + 2] else cpx[i + 1]
					val p3y = if (i + 2 < WAVE_CTRL) cpy[i + 2] else cpy[i + 1]
					for (s in 0 until WAVE_SUB) {
						val u = s.toFloat() / WAVE_SUB
						dpx[di] = catmull(p0x, cpx[i], cpx[i + 1], p3x, u)
						dpy[di] = catmull(p0y, cpy[i], cpy[i + 1], p3y, u)
						di++
					}
				}
				dpx[di] = cpx[WAVE_CTRL - 1]; dpy[di] = cpy[WAVE_CTRL - 1]

				// emit a continuous ribbon: two edge vertices per dense point (aEdge -1/+1, aT along)
				val arr = if (side == 1) waveArrL else waveArrR
				var c = 0
				for (k in 0 until dense) {
					val txv: Float
					val tyv: Float
					when {
						k == 0 -> { txv = dpx[1] - dpx[0]; tyv = dpy[1] - dpy[0] }
						k == dense - 1 -> { txv = dpx[k] - dpx[k - 1]; tyv = dpy[k] - dpy[k - 1] }
						else -> { txv = dpx[k + 1] - dpx[k - 1]; tyv = dpy[k + 1] - dpy[k - 1] }
					}
					val tl = hypot(txv, tyv).coerceAtLeast(1e-4f)
					val perpx = -tyv / tl * r
					val perpy = txv / tl * r
					val tCoord = k.toFloat() / (dense - 1)
					arr[c++] = dpx[k] + perpx; arr[c++] = dpy[k] + perpy; arr[c++] = 1f; arr[c++] = tCoord
					arr[c++] = dpx[k] - perpx; arr[c++] = dpy[k] - perpy; arr[c++] = -1f; arr[c++] = tCoord
				}
				if (side == 1) waveCountL = c / 4 else waveCountR = c / 4
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
			eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
			EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
			EGL14.eglSwapInterval(eglDisplay, 1)  // pace swaps to vsync so the measured rate is the real one
		}

		private fun initGl() {
			// Blend is toggled per pass (off for the opaque backdrop, on for the bars/wave), not global.
			GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

			bdPlainProg = buildProgram(BD_VERT, BD_FRAG_PLAIN)
			bdpPos = GLES20.glGetAttribLocation(bdPlainProg, "aPos")
			bdpTex = GLES20.glGetUniformLocation(bdPlainProg, "uTex")
			bdpCrop = GLES20.glGetUniformLocation(bdPlainProg, "uCrop")

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
			waveProg = buildProgram(WAVE_VERT, WAVE_FRAG)
			wPos = GLES20.glGetAttribLocation(waveProg, "aPosPx")
			wEdge = GLES20.glGetAttribLocation(waveProg, "aEdge")
			wT = GLES20.glGetAttribLocation(waveProg, "aT")
			wURes = GLES20.glGetUniformLocation(waveProg, "uRes")
			wUPal0 = GLES20.glGetUniformLocation(waveProg, "uPal0")
			wUPal1 = GLES20.glGetUniformLocation(waveProg, "uPal1")
			wUPal2 = GLES20.glGetUniformLocation(waveProg, "uPal2")
			wUWaveAlpha = GLES20.glGetUniformLocation(waveProg, "uWaveAlpha")

			val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
			quad = directBuffer(v.size).also { it.put(v); it.position(0) }

			val ids = IntArray(1)
			GLES20.glGenTextures(1, ids, 0)
			texId = ids[0]

			val bufs = IntArray(2)
			GLES20.glGenBuffers(2, bufs, 0)
			barVbo = bufs[0]
			waveVbo = bufs[1]
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
		var uRes = 0; var uInvH = 0; var uAA = 0; var uPal0 = 0; var uPal1 = 0; var uPal2 = 0; var uWaveAlpha = 0
		fun bind(prog: Int, bars: Boolean) {
			pos = GLES20.glGetAttribLocation(prog, "aPosPx")
			a = GLES20.glGetAttribLocation(prog, "aA")
			b = GLES20.glGetAttribLocation(prog, "aB")
			radius = GLES20.glGetAttribLocation(prog, "aRadius")
			uRes = GLES20.glGetUniformLocation(prog, "uRes")
			uInvH = GLES20.glGetUniformLocation(prog, "uInvH")
			uAA = GLES20.glGetUniformLocation(prog, "uAA")
			uPal0 = GLES20.glGetUniformLocation(prog, "uPal0")
			uPal1 = GLES20.glGetUniformLocation(prog, "uPal1")
			uPal2 = GLES20.glGetUniformLocation(prog, "uPal2")
			if (bars) {
				lenReach = GLES20.glGetAttribLocation(prog, "aLenReach")
				frac = GLES20.glGetAttribLocation(prog, "aFrac")
				pulse = GLES20.glGetAttribLocation(prog, "aPulse")
			} else {
				t = GLES20.glGetAttribLocation(prog, "aT")
				uWaveAlpha = GLES20.glGetUniformLocation(prog, "uWaveAlpha")
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

/**
 * Writes a round-capped segment a->b of radius r into [arr] at cursor [cur]; returns the new cursor.
 * Extras (e0,e1,e2 = lenReach, frac, pulse) are scalars, and corners are computed inline, so this
 * allocates nothing on the per-frame hot path (used only by the bars).
 */
private fun capsule(
	arr: FloatArray, cur: Int, ax: Float, ay: Float, bx: Float, by: Float, r: Float,
	e0: Float, e1: Float, e2: Float,
): Int {
	val dx = bx - ax
	val dy = by - ay
	val ln = hypot(dx, dy).coerceAtLeast(1e-4f)
	val ux = dx / ln
	val uy = dy / ln
	val px = -uy
	val py = ux
	val ext = r + 2f  // pad past the radius so the anti-alias fade has geometry to cover (no hard cut)
	val a0x = ax - ux * ext; val a0y = ay - uy * ext
	val b0x = bx + ux * ext; val b0y = by + uy * ext
	// four corners: 0 = a0-perp, 1 = b0-perp, 2 = a0+perp, 3 = b0+perp
	val c0x = a0x - px * ext; val c0y = a0y - py * ext
	val c1x = b0x - px * ext; val c1y = b0y - py * ext
	val c2x = a0x + px * ext; val c2y = a0y + py * ext
	val c3x = b0x + px * ext; val c3y = b0y + py * ext
	var c = cur
	for (i in TRI_IDX) {
		val cxv: Float
		val cyv: Float
		when (i) {
			0 -> { cxv = c0x; cyv = c0y }
			1 -> { cxv = c1x; cyv = c1y }
			2 -> { cxv = c2x; cyv = c2y }
			else -> { cxv = c3x; cyv = c3y }
		}
		arr[c++] = cxv; arr[c++] = cyv
		arr[c++] = ax; arr[c++] = ay
		arr[c++] = bx; arr[c++] = by
		arr[c++] = r
		arr[c++] = e0; arr[c++] = e1; arr[c++] = e2
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
private const val BD_FRAG_PLAIN = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uCrop;
void main() {
    vec2 t = (vUv - 0.5) * uCrop + 0.5;
    gl_FragColor = texture2D(uTex, vec2(t.x, 1.0 - t.y));
}
"""

// SDF works in height-normalized coords (uInvH) so magnitudes stay ~0..2: mediump-safe (no overflow)
// and fast on Mali, and we output alpha instead of discard() (discard wrecks tile-based HSR).
private const val CAP_VERT_BAR = """
attribute vec2 aPosPx; attribute vec2 aA; attribute vec2 aB; attribute float aRadius;
attribute float aLenReach; attribute float aFrac; attribute float aPulse;
uniform vec2 uRes; uniform float uInvH;
varying vec2 vPos; varying vec2 vA; varying vec2 vB; varying float vRad;
varying float vLenReach; varying float vFrac; varying float vPulse;
void main() {
    vPos = aPosPx * uInvH; vA = aA * uInvH; vB = aB * uInvH; vRad = aRadius * uInvH;
    vLenReach = aLenReach; vFrac = aFrac; vPulse = aPulse;
    vec2 ndc = vec2(aPosPx.x / uRes.x * 2.0 - 1.0, 1.0 - aPosPx.y / uRes.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
"""
private const val CAP_FRAG_BAR = """
precision mediump float;
varying vec2 vPos; varying vec2 vA; varying vec2 vB; varying float vRad;
varying float vLenReach; varying float vFrac; varying float vPulse;
uniform vec3 uPal0, uPal1, uPal2;
uniform float uAA;
vec3 pal(float f) { return f < 0.5 ? mix(uPal0, uPal1, f * 2.0) : mix(uPal1, uPal2, (f - 0.5) * 2.0); }
void main() {
    vec2 pa = vPos - vA; vec2 ba = vB - vA;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-3), 0.0, 1.0);
    float dseg = length(pa - ba * h);
    float aa = 1.0 - smoothstep(vRad - uAA, vRad + uAA, dseg);
    float b = clamp(smoothstep(0.4, 1.0, h * vLenReach) * vPulse, 0.0, 1.0);
    gl_FragColor = vec4(mix(pal(vFrac), vec3(1.0), b), aa);
}
"""

// The soundwave is ONE continuous triangle-strip ribbon (not overlapping capsules, which double-blend
// at the joins and bead when translucent). aEdge is the signed perpendicular coord (-1..1) for edge AA.
private const val WAVE_VERT = """
attribute vec2 aPosPx; attribute float aEdge; attribute float aT;
uniform vec2 uRes;
varying float vEdge; varying float vT;
void main() {
    vEdge = aEdge; vT = aT;
    vec2 ndc = vec2(aPosPx.x / uRes.x * 2.0 - 1.0, 1.0 - aPosPx.y / uRes.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
"""
private const val WAVE_FRAG = """
precision mediump float;
varying float vEdge; varying float vT;
uniform vec3 uPal0, uPal1, uPal2;
uniform float uWaveAlpha;
vec3 pal(float f) { return f < 0.5 ? mix(uPal0, uPal1, f * 2.0) : mix(uPal1, uPal2, (f - 0.5) * 2.0); }
void main() {
    float aa = 1.0 - smoothstep(0.55, 1.0, abs(vEdge));  // soft long edges (wide, to survive the upscale)
    gl_FragColor = vec4(pal(vT), aa * uWaveAlpha);
}
"""
