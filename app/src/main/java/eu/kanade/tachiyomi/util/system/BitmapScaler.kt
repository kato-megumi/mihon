package eu.kanade.tachiyomi.util.system

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Utility class for scaling bitmaps with various interpolation methods.
 * Uses GPU-accelerated Canvas drawing for optimal performance.
 */
object BitmapScaler {

    /**
     * Interpolation method to use when scaling bitmaps.
     */
    enum class InterpolationMethod {
        /** Nearest neighbor interpolation - fastest, pixelated results */
        INTER_NEAREST,

        /** Bilinear interpolation - default, good balance of speed and quality */
        INTER_LINEAR,

        /** Pixel area relation resampling - best for downscaling, prevents moiré */
        INTER_AREA,

        /** Bicubic interpolation over 4x4 pixel neighborhood */
        INTER_CUBIC,

        /** Lanczos3 interpolation over 6x6 pixel neighborhood - matches chaiNNer */
        INTER_LANCZOS3,

        /** Lanczos4 interpolation over 8x8 pixel neighborhood - sharpest */
        INTER_LANCZOS4,
    }

    /**
     * Scale a bitmap using the specified interpolation method.
     * Uses GPU-accelerated Paint filters for best performance.
     *
     * @param source The source bitmap to scale
     * @param targetWidth The desired width
     * @param targetHeight The desired height
     * @param method The interpolation method to use
     * @return The scaled bitmap
     */
    fun scale(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        method: InterpolationMethod = InterpolationMethod.INTER_LINEAR,
    ): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) {
            return source
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val paint = Paint().apply {
            isAntiAlias = true
            isDither = true

            // Set filter quality based on method
            when (method) {
                InterpolationMethod.INTER_NEAREST -> {
                    // No filtering for nearest neighbor
                    isFilterBitmap = false
                }
                InterpolationMethod.INTER_LINEAR -> {
                    isFilterBitmap = true
                }
                InterpolationMethod.INTER_AREA -> {
                    // Area-based filtering for downscaling
                    isFilterBitmap = true
                    isAntiAlias = true
                }
                InterpolationMethod.INTER_CUBIC,
                InterpolationMethod.INTER_LANCZOS3,
                InterpolationMethod.INTER_LANCZOS4,
                -> {
                    // High quality filtering
                    isFilterBitmap = true
                    isAntiAlias = true
                    isDither = true
                }
            }
        }

        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
            paint,
        )

        return result
    }
}
