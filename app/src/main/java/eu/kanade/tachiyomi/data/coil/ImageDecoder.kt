package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.davemorrissey.labs.subscaleview.CropBorders
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer

/**
 * A [Decoder] that uses [ImageDecoder] (libvips-based) to decode image formats not supported
 * by the Android system decoder (AVIF, JXL, HEIF, etc.).
 */
class ImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    /**
     * Wraps a raw [ImageDecoder.DecodeResult] as a Coil [Image] for callers that want
     * direct access to the RGBA [java.nio.ByteBuffer] (e.g. the new-decoder path).
     */
    class DecodeResultImage(val res: ImageDecoder.DecodeResult) : Image {
        override val size: Long get() = res.image.capacity().toLong()
        override val width: Int get() = res.width
        override val height: Int get() = res.height
        override val shareable: Boolean get() = true
        override fun draw(canvas: Canvas) {}
    }

    override suspend fun decode(): DecodeResult {
        val decoder = resources.source().use {
            try {
                ImageDecoder.new(it.inputStream())
            } catch (e: ImageDecoder.DecodeException) {
                logcat(LogPriority.ERROR, e) { "ImageDecoder.new failed: ${e.message}" }
                null
            }
        }

        check(decoder != null && decoder.pages > 0) { "Failed to initialize decoder" }

        val res = decoder.decode()

        val srcWidth = res.width
        val srcHeight = res.height

        // newDecoder path: caller wants the raw DecodeResult (e.g. for custom rendering).
        // Hand it back as-is; sampling is the caller's responsibility, and crop-borders is left
        // out so the caller keeps the full frame.
        if (options.newDecoder) {
            return DecodeResult(
                image = DecodeResultImage(res),
                isSampled = false,
            )
        }

        // Reader pages are handed to SSIV as pre-decoded bitmaps, so SSIV's own crop-borders
        // detector — which only runs inside its stream decoder — never applies to them. Trim them
        // here with the same native detector so both reader decode paths crop identically.
        val trimmed = if (options.cropBorders) cropBorders(res.image, srcWidth, srcHeight) else null

        // Copy RGBA pixels from the native buffer into a full-resolution bitmap when nothing was
        // trimmed. We must do this while `res` (and its native memory) is still alive.
        val decoded = trimmed ?: createBitmap(srcWidth, srcHeight).apply {
            res.image.rewind()
            copyPixelsFromBuffer(res.image)
        }

        // Normal path: produce a Bitmap scaled to the requested output size.
        val dstWidth = options.size.widthPx(options.scale) { decoded.width }
        val dstHeight = options.size.heightPx(options.scale) { decoded.height }
        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = decoded.width,
            srcHeight = decoded.height,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        // Downsample if needed. sampleSize is a power-of-two factor; the target
        // dimensions are src / sampleSize, matching BitmapFactory inSampleSize behaviour.
        val bitmap = if (sampleSize > 1) {
            val scaledWidth = (decoded.width / sampleSize).coerceAtLeast(1)
            val scaledHeight = (decoded.height / sampleSize).coerceAtLeast(1)
            val scaled = decoded.scale(scaledWidth, scaledHeight)
            decoded.recycle()
            scaled
        } else {
            decoded
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    /**
     * Trims the borders detected by [CropBorders] — the same native detector SSIV runs on the
     * decode path it owns — returning null when the source has no borders to trim.
     *
     * Only the kept rows are copied out of [rgba], so the untrimmed full-resolution image is never
     * materialised.
     */
    private fun cropBorders(rgba: ByteBuffer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null

        rgba.rewind()
        val pixels = ByteArray(rgba.remaining())
        rgba.get(pixels)
        if (pixels.size < width * height * PIXEL_BYTES) return null

        val rect = CropBorders.findCropBorders(pixels, width, height)
        val left = rect[0].coerceIn(0, width - 1)
        val top = rect[1].coerceIn(0, height - 1)
        val cropWidth = rect[2].coerceIn(1, width - left)
        val cropHeight = rect[3].coerceIn(1, height - top)

        if (left == 0 && top == 0 && cropWidth == width && cropHeight == height) return null

        return createBitmap(cropWidth, cropHeight).apply {
            copyPixelsFromBuffer(extractRows(rgba, width, left, top, cropWidth, cropHeight))
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            // This decoder honors cropBorders (see [decode]), so route those requests here even
            // when the caller did not opt into the custom decoder.
            val supported = options.newDecoder ||
                options.customDecoder ||
                options.cropBorders ||
                isApplicable(result.source.source())
            return if (supported) {
                ImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF,
                ImageUtil.ImageType.JXL,
                ImageUtil.ImageType.HEIF,
                ImageUtil.ImageType.JP2,
                -> true

                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}

/**
 * Copies the [cropWidth] x [cropHeight] region at ([left], [top]) out of [rgba], an RGBA buffer
 * holding [width] pixels per row. Only the copied bytes are read, so the untrimmed image is never
 * materialised.
 */
internal fun extractRows(
    rgba: ByteBuffer,
    width: Int,
    left: Int,
    top: Int,
    cropWidth: Int,
    cropHeight: Int,
): ByteBuffer {
    val rowBytes = cropWidth * PIXEL_BYTES
    val rows = ByteBuffer.allocateDirect(rowBytes * cropHeight)
    val source = rgba.duplicate()
    for (row in 0 until cropHeight) {
        val rowStart = ((top + row) * width + left) * PIXEL_BYTES
        source.limit(rowStart + rowBytes)
        source.position(rowStart)
        rows.put(source)
    }
    rows.rewind()
    return rows
}

/** RGBA, 4 bytes per pixel — the layout the libvips decoder hands back. */
private const val PIXEL_BYTES = 4
