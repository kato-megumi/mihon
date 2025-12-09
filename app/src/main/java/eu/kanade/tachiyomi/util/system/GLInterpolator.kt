package eu.kanade.tachiyomi.util.system

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
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

    // Vertex shader - simple pass-through
    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    // Fragment shader for Nearest Neighbor
    private const val NEAREST_SHADER = """
        precision highp float;
        uniform sampler2D uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // Fragment shader for Bilinear (OpenGL default, but explicit)
    private const val BILINEAR_SHADER = """
        precision highp float;
        uniform sampler2D uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // Fragment shader for Lanczos3 interpolation
    private const val LANCZOS3_SHADER = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uTextureSize;
        uniform vec2 uOutputSize;
        varying vec2 vTexCoord;

        const float PI = 3.14159265358979323846;
        const float a = 3.0; // Lanczos kernel size

        float sinc(float x) {
            if (abs(x) < 0.0001) return 1.0;
            float pix = PI * x;
            return sin(pix) / pix;
        }

        float lanczos(float x) {
            if (abs(x) >= a) return 0.0;
            return sinc(x) * sinc(x / a);
        }

        void main() {
            vec2 texelSize = 1.0 / uTextureSize;
            vec2 scale = uTextureSize / uOutputSize;

            // Source position in texture coordinates
            vec2 srcPos = vTexCoord * uTextureSize - 0.5;
            vec2 srcPosFloor = floor(srcPos);
            vec2 f = srcPos - srcPosFloor;

            vec4 color = vec4(0.0);
            float weightSum = 0.0;

            // Sample 6x6 neighborhood for Lanczos3
            for (int y = -2; y <= 3; y++) {
                for (int x = -2; x <= 3; x++) {
                    vec2 samplePos = srcPosFloor + vec2(float(x), float(y));
                    vec2 sampleCoord = (samplePos + 0.5) / uTextureSize;

                    // Clamp to texture bounds
                    sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));

                    float wx = lanczos(float(x) - f.x);
                    float wy = lanczos(float(y) - f.y);
                    float weight = wx * wy;

                    color += texture2D(uTexture, sampleCoord) * weight;
                    weightSum += weight;
                }
            }

            if (weightSum > 0.0) {
                color /= weightSum;
            }

            // Clamp to valid range (Lanczos can produce values outside 0-1)
            gl_FragColor = clamp(color, 0.0, 1.0);
        }
    """

    // Fragment shader for Lanczos4 interpolation (8x8 kernel)
    private const val LANCZOS4_SHADER = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uTextureSize;
        uniform vec2 uOutputSize;
        varying vec2 vTexCoord;

        const float PI = 3.14159265358979323846;
        const float a = 4.0; // Lanczos kernel size

        float sinc(float x) {
            if (abs(x) < 0.0001) return 1.0;
            float pix = PI * x;
            return sin(pix) / pix;
        }

        float lanczos(float x) {
            if (abs(x) >= a) return 0.0;
            return sinc(x) * sinc(x / a);
        }

        void main() {
            vec2 texelSize = 1.0 / uTextureSize;
            vec2 scale = uTextureSize / uOutputSize;

            // Source position in texture coordinates
            vec2 srcPos = vTexCoord * uTextureSize - 0.5;
            vec2 srcPosFloor = floor(srcPos);
            vec2 f = srcPos - srcPosFloor;

            vec4 color = vec4(0.0);
            float weightSum = 0.0;

            // Sample 8x8 neighborhood for Lanczos4
            for (int y = -3; y <= 4; y++) {
                for (int x = -3; x <= 4; x++) {
                    vec2 samplePos = srcPosFloor + vec2(float(x), float(y));
                    vec2 sampleCoord = (samplePos + 0.5) / uTextureSize;

                    // Clamp to texture bounds
                    sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));

                    float wx = lanczos(float(x) - f.x);
                    float wy = lanczos(float(y) - f.y);
                    float weight = wx * wy;

                    color += texture2D(uTexture, sampleCoord) * weight;
                    weightSum += weight;
                }
            }

            if (weightSum > 0.0) {
                color /= weightSum;
            }

            // Clamp to valid range (Lanczos can produce values outside 0-1)
            gl_FragColor = clamp(color, 0.0, 1.0);
        }
    """

    // Fragment shader for Bicubic (Mitchell-Netravali) interpolation
    private const val BICUBIC_SHADER = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uTextureSize;
        uniform vec2 uOutputSize;
        varying vec2 vTexCoord;

        // Mitchell-Netravali coefficients (B=1/3, C=1/3)
        const float B = 0.333333;
        const float C = 0.333333;

        float mitchell(float x) {
            float ax = abs(x);
            if (ax < 1.0) {
                return ((12.0 - 9.0 * B - 6.0 * C) * ax * ax * ax +
                        (-18.0 + 12.0 * B + 6.0 * C) * ax * ax +
                        (6.0 - 2.0 * B)) / 6.0;
            } else if (ax < 2.0) {
                return ((-B - 6.0 * C) * ax * ax * ax +
                        (6.0 * B + 30.0 * C) * ax * ax +
                        (-12.0 * B - 48.0 * C) * ax +
                        (8.0 * B + 24.0 * C)) / 6.0;
            }
            return 0.0;
        }

        void main() {
            vec2 srcPos = vTexCoord * uTextureSize - 0.5;
            vec2 srcPosFloor = floor(srcPos);
            vec2 f = srcPos - srcPosFloor;

            vec4 color = vec4(0.0);
            float weightSum = 0.0;

            // Sample 4x4 neighborhood
            for (int y = -1; y <= 2; y++) {
                for (int x = -1; x <= 2; x++) {
                    vec2 samplePos = srcPosFloor + vec2(float(x), float(y));
                    vec2 sampleCoord = (samplePos + 0.5) / uTextureSize;
                    sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));

                    float wx = mitchell(float(x) - f.x);
                    float wy = mitchell(float(y) - f.y);
                    float weight = wx * wy;

                    color += texture2D(uTexture, sampleCoord) * weight;
                    weightSum += weight;
                }
            }

            if (weightSum > 0.0) {
                color /= weightSum;
            }

            gl_FragColor = clamp(color, 0.0, 1.0);
        }
    """

    // Fragment shader for Area (box filter) - good for downscaling
    private const val AREA_SHADER = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uTextureSize;
        uniform vec2 uOutputSize;
        varying vec2 vTexCoord;

        void main() {
            vec2 scale = uTextureSize / uOutputSize;

            // For downscaling, average over the source area
            if (scale.x > 1.0 || scale.y > 1.0) {
                vec2 srcStart = vTexCoord * uTextureSize - scale * 0.5;
                vec2 srcEnd = srcStart + scale;

                vec4 color = vec4(0.0);
                float samples = 0.0;

                // Adaptive sample count based on scale
                int samplesX = int(ceil(scale.x));
                int samplesY = int(ceil(scale.y));
                if (samplesX > 8) samplesX = 8; // GLES2 lacks min for int
                if (samplesY > 8) samplesY = 8;

                for (int y = 0; y < 8; y++) {
                    if (y >= samplesY) break;
                    for (int x = 0; x < 8; x++) {
                        if (x >= samplesX) break;
                        vec2 offset = vec2(float(x) + 0.5, float(y) + 0.5) / vec2(float(samplesX), float(samplesY));
                        vec2 sampleCoord = (srcStart + offset * scale) / uTextureSize;
                        sampleCoord = clamp(sampleCoord, vec2(0.0), vec2(1.0));
                        color += texture2D(uTexture, sampleCoord);
                        samples += 1.0;
                    }
                }

                gl_FragColor = color / samples;
            } else {
                // For upscaling, just use bilinear
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

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

    private var vertexBuffer: FloatBuffer? = null

    /**
     * Initialize OpenGL ES context for off-screen rendering.
     */
    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized) return true

        return try {
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
            nearestProgram = createProgram(VERTEX_SHADER, NEAREST_SHADER)
            bilinearProgram = createProgram(VERTEX_SHADER, BILINEAR_SHADER)
            bicubicProgram = createProgram(VERTEX_SHADER, BICUBIC_SHADER)
            lanczos3Program = createProgram(VERTEX_SHADER, LANCZOS3_SHADER)
            lanczos4Program = createProgram(VERTEX_SHADER, LANCZOS4_SHADER)
            areaProgram = createProgram(VERTEX_SHADER, AREA_SHADER)

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

    /**
     * Scale bitmap using specified interpolation method.
     */
    @Synchronized
    fun scale(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        method: BitmapScaler.InterpolationMethod,
    ): Bitmap? {
        if (!isInitialized && !initialize()) {
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
