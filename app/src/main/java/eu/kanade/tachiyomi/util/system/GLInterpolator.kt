package eu.kanade.tachiyomi.util.system

import android.content.res.Resources
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import androidx.annotation.RawRes
import eu.kanade.tachiyomi.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * OpenGL ES 2.0 based image interpolator with custom shader support.
 * Provides high-quality Lanczos and other interpolation methods using GPU shaders.
 */
object GLInterpolator {

    private const val TAG = "GLInterpolator"

    private data class ShaderSources(
        val vertex: String,
        val nearest: String,
        val bilinear: String,
        val bicubic: String,
        val lanczos3: String,
        val lanczos4: String,
        val area: String,
    )

    // Quad vertices (full screen) with position (x,y) and texture coords (s,t)
    // Flip texture Y (v) so rendered FBO is upright before readback (we still flip buffer rows for Bitmap)
    private val QUAD_VERTICES = floatArrayOf(
        -1f, -1f, 0f, 1f, // bottom-left  (tex: 0, 1)
        1f, -1f, 1f, 1f, // bottom-right (tex: 1, 1)
        -1f, 1f, 0f, 0f, // top-left     (tex: 0, 0)
        1f, 1f, 1f, 0f, // top-right    (tex: 1, 0)
    )

    private var isInitialized = false
    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    // Shader programs
    private var nearestProgram = 0
    private var bilinearProgram = 0
    private var bicubicProgram = 0
    private var lanczos3Program = 0
    private var lanczos4Program = 0
    private var areaProgram = 0

    private var shaderSources: ShaderSources? = null

    private var vertexBuffer: FloatBuffer? = null

    /**
     * Initialize OpenGL ES context for off-screen rendering.
     */
    @Synchronized
    fun initialize(resources: Resources): Boolean {
        if (isInitialized) return true

        return try {
            val sources = shaderSources ?: loadShaderSources(resources).also { shaderSources = it }

            // Get EGL instance
            egl = EGLContext.getEGL() as EGL10

            // Get default display
            eglDisplay = egl!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
                Log.e(TAG, "Failed to get EGL display")
                return false
            }

            // Initialize EGL
            val version = IntArray(2)
            if (!egl!!.eglInitialize(eglDisplay, version)) {
                Log.e(TAG, "Failed to initialize EGL")
                return false
            }

            // Choose config
            val configAttribs = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_NONE,
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!egl!!.eglChooseConfig(eglDisplay, configAttribs, configs, 1, numConfigs)) {
                Log.e(TAG, "Failed to choose EGL config")
                return false
            }
            eglConfig = configs[0]

            // Create context
            val contextAttribs = intArrayOf(
                0x3098,
                2, // EGL_CONTEXT_CLIENT_VERSION = 2
                EGL10.EGL_NONE,
            )
            eglContext = egl!!.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, contextAttribs)
            if (eglContext == EGL10.EGL_NO_CONTEXT) {
                Log.e(TAG, "Failed to create EGL context")
                return false
            }

            // Create a small pbuffer surface (will be replaced per-render)
            val surfaceAttribs = intArrayOf(
                EGL10.EGL_WIDTH,
                1,
                EGL10.EGL_HEIGHT,
                1,
                EGL10.EGL_NONE,
            )
            eglSurface = egl!!.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs)

            // Make context current
            if (!egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "Failed to make EGL context current")
                return false
            }

            // Compile shaders
            nearestProgram = createProgram(sources.vertex, sources.nearest)
            bilinearProgram = createProgram(sources.vertex, sources.bilinear)
            bicubicProgram = createProgram(sources.vertex, sources.bicubic)
            lanczos3Program = createProgram(sources.vertex, sources.lanczos3)
            lanczos4Program = createProgram(sources.vertex, sources.lanczos4)
            areaProgram = createProgram(sources.vertex, sources.area)

            // Create vertex buffer
            vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(QUAD_VERTICES)
            vertexBuffer!!.position(0)

            isInitialized = true
            Log.d(TAG, "GLInterpolator initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GLInterpolator", e)
            false
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Program link error: $error")
            GLES20.glDeleteProgram(program)
            return 0
        }

        // Clean up shaders (they're linked into the program now)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile error: $error")
            GLES20.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    private fun loadShaderSources(resources: Resources): ShaderSources {
        return ShaderSources(
            vertex = readRawText(resources, R.raw.gl_interpolator_vertex),
            nearest = readRawText(resources, R.raw.gl_interpolator_nearest),
            bilinear = readRawText(resources, R.raw.gl_interpolator_bilinear),
            bicubic = readRawText(resources, R.raw.gl_interpolator_bicubic),
            lanczos3 = readRawText(resources, R.raw.gl_interpolator_lanczos3),
            lanczos4 = readRawText(resources, R.raw.gl_interpolator_lanczos4),
            area = readRawText(resources, R.raw.gl_interpolator_area),
        )
    }

    private fun readRawText(resources: Resources, @RawRes resId: Int): String {
        return resources.openRawResource(resId).bufferedReader().use { it.readText() }
    }

    /**
     * Scale bitmap using specified interpolation method.
     */
    @Synchronized
    fun scale(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        method: BitmapScaler.InterpolationMethod,
        resources: Resources,
    ): Bitmap? {
        if (!isInitialized && !initialize(resources)) {
            Log.e(TAG, "GLInterpolator not initialized")
            return null
        }

        if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        return try {
            // Make context current
            egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            // Select program based on method
            val program = when (method) {
                BitmapScaler.InterpolationMethod.INTER_NEAREST -> nearestProgram
                BitmapScaler.InterpolationMethod.INTER_LINEAR -> bilinearProgram
                BitmapScaler.InterpolationMethod.INTER_AREA -> areaProgram
                BitmapScaler.InterpolationMethod.INTER_CUBIC -> bicubicProgram
                BitmapScaler.InterpolationMethod.INTER_LANCZOS3 -> lanczos3Program
                BitmapScaler.InterpolationMethod.INTER_LANCZOS4 -> lanczos4Program
            }

            if (program == 0) {
                Log.e(TAG, "Invalid program for method $method")
                return null
            }

            // Create framebuffer for output
            val framebuffer = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])

            // Create output texture
            val outputTexture = IntArray(1)
            GLES20.glGenTextures(1, outputTexture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture[0])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                targetWidth,
                targetHeight,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Attach output texture to framebuffer
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                outputTexture[0],
                0,
            )

            // Check framebuffer status
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Framebuffer incomplete: $status")
                GLES20.glDeleteTextures(1, outputTexture, 0)
                GLES20.glDeleteFramebuffers(1, framebuffer, 0)
                return null
            }

            // Create input texture from bitmap
            val inputTexture = IntArray(1)
            GLES20.glGenTextures(1, inputTexture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture[0])

            // Set texture parameters based on interpolation method
            val minFilter = if (method == BitmapScaler.InterpolationMethod.INTER_NEAREST) {
                GLES20.GL_NEAREST
            } else {
                GLES20.GL_LINEAR
            }
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, minFilter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Upload bitmap to texture
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            // Setup viewport
            GLES20.glViewport(0, 0, targetWidth, targetHeight)

            // Use program
            GLES20.glUseProgram(program)

            // Set uniforms
            val textureSizeLoc = GLES20.glGetUniformLocation(program, "uTextureSize")
            val outputSizeLoc = GLES20.glGetUniformLocation(program, "uOutputSize")
            val textureLoc = GLES20.glGetUniformLocation(program, "uTexture")

            if (textureSizeLoc >= 0) {
                GLES20.glUniform2f(textureSizeLoc, bitmap.width.toFloat(), bitmap.height.toFloat())
            }
            if (outputSizeLoc >= 0) {
                GLES20.glUniform2f(outputSizeLoc, targetWidth.toFloat(), targetHeight.toFloat())
            }
            GLES20.glUniform1i(textureLoc, 0)

            // Set vertex attributes
            val positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

            vertexBuffer!!.position(0)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionLoc)

            vertexBuffer!!.position(2)
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texCoordLoc)

            // Draw
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Read pixels
            val resultBuffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0,
                0,
                targetWidth,
                targetHeight,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                resultBuffer,
            )

            // Flip the buffer vertically (OpenGL reads bottom-to-top, Bitmap expects top-to-bottom)
            val rowSize = targetWidth * 4
            val rowBuffer = ByteArray(rowSize)
            val flippedBuffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4)
                .order(ByteOrder.nativeOrder())
            android.util.Log.d(
                "GLInterpolator",
                "Flipping buffer: width=$targetWidth height=$targetHeight rowSize=$rowSize",
            )
            for (row in 0 until targetHeight) {
                resultBuffer.position((targetHeight - 1 - row) * rowSize)
                resultBuffer.get(rowBuffer)
                flippedBuffer.put(rowBuffer)
                if (row == 0 || row == targetHeight - 1) {
                    android.util.Log.d("GLInterpolator", "Row $row first bytes: ${rowBuffer.take(8)}")
                }
            }
            flippedBuffer.rewind()

            // Create result bitmap
            val resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            resultBitmap.copyPixelsFromBuffer(flippedBuffer)
            android.util.Log.d("GLInterpolator", "Result bitmap created: ${resultBitmap.width}x${resultBitmap.height}")

            // Cleanup
            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(texCoordLoc)
            GLES20.glDeleteTextures(1, inputTexture, 0)
            GLES20.glDeleteTextures(1, outputTexture, 0)
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error during GL scaling", e)
            null
        }
    }

    /**
     * Release OpenGL resources.
     */
    @Synchronized
    fun release() {
        if (!isInitialized) return

        try {
            egl?.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            if (nearestProgram != 0) GLES20.glDeleteProgram(nearestProgram)
            if (bilinearProgram != 0) GLES20.glDeleteProgram(bilinearProgram)
            if (bicubicProgram != 0) GLES20.glDeleteProgram(bicubicProgram)
            if (lanczos3Program != 0) GLES20.glDeleteProgram(lanczos3Program)
            if (lanczos4Program != 0) GLES20.glDeleteProgram(lanczos4Program)
            if (areaProgram != 0) GLES20.glDeleteProgram(areaProgram)

            nearestProgram = 0
            bilinearProgram = 0
            bicubicProgram = 0
            lanczos3Program = 0
            lanczos4Program = 0
            areaProgram = 0
            shaderSources = null

            egl?.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl?.eglDestroySurface(eglDisplay, eglSurface)
            egl?.eglDestroyContext(eglDisplay, eglContext)
            egl?.eglTerminate(eglDisplay)

            isInitialized = false
            Log.d(TAG, "GLInterpolator released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing GLInterpolator", e)
        }
    }
}
