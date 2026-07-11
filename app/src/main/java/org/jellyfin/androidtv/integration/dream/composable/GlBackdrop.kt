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

/**
 * Full-screen backdrop rendered with OpenGL ES so a bass-driven radial "bulge" shockwave can distort
 * the artwork per-pixel. RuntimeShader (the easy path) is API 33+; this app targets API 29, so we run
 * a hand-written GLES 2 fragment shader on a [TextureView] - which composes inline under the Compose UI
 * (visualizer, text) exactly like the plain ImageView backdrop it replaces, avoiding SurfaceView
 * z-ordering issues. The render thread reads the live [AudioSpectrum] bass itself.
 */
@Composable
fun GlBackdrop(url: String?, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	val view = remember { BulgeTextureView(context) }

	// Load the cover off-thread; hand the software bitmap to the GL thread to upload as a texture.
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

	AndroidView(
		modifier = modifier,
		factory = { view },
		onRelease = { it.release() },
	)
}

/**
 * A [TextureView] that renders a bass-reactive bulge of a single bitmap via its own EGL/GLES2 thread.
 */
private class BulgeTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
	@Volatile private var pendingBitmap: Bitmap? = null
	private var thread: RenderThread? = null

	init {
		isOpaque = true
		surfaceTextureListener = this
	}

	fun setImage(bitmap: Bitmap) {
		pendingBitmap = bitmap
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
	) : Thread("GlBackdrop") {
		@Volatile var running = true
		@Volatile private var viewW = width
		@Volatile private var viewH = height

		private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
		private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
		private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

		private var program = 0
		private var aPos = 0
		private var uTex = 0
		private var uBass = 0
		private var uCrop = 0
		private var texId = 0
		private var texW = 1
		private var texH = 1
		private var hasTexture = false
		private lateinit var quad: FloatBuffer

		fun resize(w: Int, h: Int) {
			viewW = w
			viewH = h
		}

		override fun run() {
			try {
				initEgl()
				initGl()
				var punch = 0f
				while (running) {
					pendingBitmap?.let { bmp ->
						pendingBitmap = null
						uploadTexture(bmp)
					}
					val bass = bassLevel(AudioSpectrum.bands.value)
					// Quick attack, rapid decay - a speaker-cone punch that drives the bulge amount.
					punch = beatFollow(punch, bass, 0.80f).coerceIn(0f, 1f)
					drawFrame(punch)
					// eglSwapBuffers blocks on vsync, throttling the loop to the display refresh.
					EGL14.eglSwapBuffers(eglDisplay, eglSurface)
				}
			} catch (_: Throwable) {
				// Never crash the screensaver on a GL error; just stop rendering.
			} finally {
				releaseEgl()
			}
		}

		private fun initEgl() {
			eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
			val version = IntArray(2)
			EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

			val configAttribs = intArrayOf(
				EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
				EGL14.EGL_RED_SIZE, 8,
				EGL14.EGL_GREEN_SIZE, 8,
				EGL14.EGL_BLUE_SIZE, 8,
				EGL14.EGL_ALPHA_SIZE, 0,
				EGL14.EGL_NONE,
			)
			val configs = arrayOfNulls<EGLConfig>(1)
			val numConfig = IntArray(1)
			EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfig, 0)

			val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
			eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
			eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
			EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
		}

		private fun initGl() {
			val vs = """
				attribute vec2 aPos;
				varying vec2 vUv;
				void main() {
					vUv = aPos * 0.5 + 0.5;
					gl_Position = vec4(aPos, 0.0, 1.0);
				}
			""".trimIndent()
			val fs = """
				precision mediump float;
				varying vec2 vUv;
				uniform sampler2D uTex;
				uniform float uBass;
				uniform vec2 uCrop;
				void main() {
					vec2 c = vec2(0.5);
					vec2 d = vUv - c;
					float dist = length(d);
					// Radial falloff: strongest at the centre, gone past ~0.55 of the frame.
					float fall = 1.0 - smoothstep(0.0, 0.55, dist);
					// Magnify toward the centre (bulge) - a uniform punch plus a centre-weighted shockwave.
					float k = uBass * (0.06 + 0.16 * fall);
					vec2 uv = c + d * (1.0 - k);
					// Center-crop the (square) cover into the widescreen frame, and flip Y for the bitmap.
					vec2 tc = (uv - c) * uCrop + c;
					tc.y = 1.0 - tc.y;
					gl_FragColor = texture2D(uTex, tc);
				}
			""".trimIndent()

			program = buildProgram(vs, fs)
			aPos = GLES20.glGetAttribLocation(program, "aPos")
			uTex = GLES20.glGetUniformLocation(program, "uTex")
			uBass = GLES20.glGetUniformLocation(program, "uBass")
			uCrop = GLES20.glGetUniformLocation(program, "uCrop")

			val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
			quad = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
			quad.put(verts).position(0)

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

		private fun drawFrame(bass: Float) {
			GLES20.glViewport(0, 0, viewW, viewH)
			GLES20.glClearColor(0f, 0f, 0f, 1f)
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
			if (!hasTexture) return

			// Center-crop factor: shrink sampling on the axis the frame overflows so the image fills it.
			val surfAspect = if (viewH > 0) viewW.toFloat() / viewH else 1f
			val texAspect = if (texH > 0) texW.toFloat() / texH else 1f
			var cropX = 1f
			var cropY = 1f
			if (surfAspect > texAspect) cropY = texAspect / surfAspect else cropX = surfAspect / texAspect

			GLES20.glUseProgram(program)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
			GLES20.glUniform1i(uTex, 0)
			GLES20.glUniform1f(uBass, bass)
			GLES20.glUniform2f(uCrop, cropX, cropY)

			GLES20.glEnableVertexAttribArray(aPos)
			GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
			GLES20.glDisableVertexAttribArray(aPos)
		}

		private fun buildProgram(vsSource: String, fsSource: String): Int {
			val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
				GLES20.glShaderSource(it, vsSource); GLES20.glCompileShader(it)
			}
			val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
				GLES20.glShaderSource(it, fsSource); GLES20.glCompileShader(it)
			}
			return GLES20.glCreateProgram().also {
				GLES20.glAttachShader(it, vs)
				GLES20.glAttachShader(it, fs)
				GLES20.glLinkProgram(it)
				GLES20.glDeleteShader(vs)
				GLES20.glDeleteShader(fs)
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
}
