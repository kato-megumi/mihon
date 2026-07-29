package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.BitmapScaler
import eu.kanade.tachiyomi.util.system.GLInterpolator
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import logcat.LogPriority
import logcat.logcat
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV.get()
    }

    private var pageView: View? = null

    private var config: Config? = null

    // Store bitmaps for toggle and save functionality
    private var originalBitmap: Bitmap? = null
    private var scaledBitmap: Bitmap? = null
    private var isShowingScaled: Boolean = true
    private var lastScaleRatio: Float? = null
    private var scaleToggleRunnable: Runnable? = null
    private var isTogglingImage: Boolean = false

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
        maybeScheduleToggleForZoom(newScale)
    }

    private fun maybeScheduleToggleForZoom(newScale: Float) {
        val ssiv = pageView as? SubsamplingScaleImageView ?: return
        if (!hasScaledImage()) return
        if (isTogglingImage) return

        val baseScale = ssiv.minScale.takeIf { it > 0F } ?: return
        lastScaleRatio = newScale / baseScale

        scaleToggleRunnable?.let { ssiv.handler?.removeCallbacks(it) }
        val runnable = Runnable { applyZoomToggleDecision() }
        scaleToggleRunnable = runnable
        ssiv.handler?.postDelayed(runnable, ZOOM_TOGGLE_DEBOUNCE_MS)
    }

    private fun applyZoomToggleDecision() {
        val ssiv = pageView as? SubsamplingScaleImageView ?: return
        if (!hasScaledImage()) return

        val zoomRatio = lastScaleRatio ?: return

        if (!isShowingScaled && zoomRatio <= AUTO_TOGGLE_SCALED_RATIO) {
            toggleScaledOriginal()
            return
        }

        if (isShowingScaled && zoomRatio >= AUTO_TOGGLE_ORIGINAL_RATIO) {
            toggleScaledOriginal()
        }
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        // Recycle and clear previous bitmaps before loading a new image
        recycleStoredBitmaps()
        isShowingScaled = true

        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        // Recycle and clear previous bitmaps before loading a new image
        recycleStoredBitmaps()
        isShowingScaled = true

        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    /**
     * Get the scaled/interpolated bitmap if available.
     */
    fun getScaledBitmap(): Bitmap? = scaledBitmap

    /**
     * Check if interpolation was applied (scaled bitmap differs from original).
     */
    fun hasScaledImage(): Boolean = scaledBitmap != null && originalBitmap != null && scaledBitmap != originalBitmap

    /**
     * Recycle and clear the stored original/scaled bitmaps. The display copy handed to SSIV is a
     * separate bitmap owned by SSIV, so recycling these does not affect what is on screen.
     */
    private fun recycleStoredBitmaps() {
        scaledBitmap?.takeIf { it != originalBitmap && !it.isRecycled }?.recycle()
        originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        originalBitmap = null
        scaledBitmap = null
    }

    /**
     * Check if currently showing scaled image.
     */
    fun isShowingScaledImage(): Boolean = isShowingScaled

    /**
     * Toggle between original and scaled image.
     * Returns true if now showing scaled, false if showing original.
     */
    fun toggleScaledOriginal(): Boolean {
        val ssiv = pageView as? SubsamplingScaleImageView ?: return isShowingScaled
        val original = originalBitmap ?: return isShowingScaled
        val scaled = scaledBitmap ?: return isShowingScaled

        if (original == scaled || original.isRecycled || scaled.isRecycled) {
            return isShowingScaled
        }

        // Capture state before toggle
        val currentBitmap = if (isShowingScaled) scaled else original
        val targetBitmap = if (isShowingScaled) original else scaled

        // Get current scale ratio relative to minScale
        val currentScale = ssiv.scale
        val currentMinScale = ssiv.minScale.takeIf { it > 0f } ?: 1f
        val zoomRatio = currentScale / currentMinScale

        // Get center as fraction of image dimensions
        val center = ssiv.center
        val centerFractionX = center?.let { it.x / currentBitmap.width.toFloat() } ?: 0.5f
        val centerFractionY = center?.let { it.y / currentBitmap.height.toFloat() } ?: 0.5f

        // Prevent auto-toggle during this toggle operation
        isTogglingImage = true
        scaleToggleRunnable?.let { ssiv.handler?.removeCallbacks(it) }
        scaleToggleRunnable = null

        isShowingScaled = !isShowingScaled
        val bitmapToShow = targetBitmap

        // Use a copy of the bitmap to prevent issues with SSIV recycling
        val bitmapCopy = bitmapToShow.copy(Bitmap.Config.ARGB_8888, false)
        if (bitmapCopy == null) {
            isShowingScaled = !isShowingScaled
            isTogglingImage = false
            return isShowingScaled
        }

        // Capture values for closure to prevent capture issues
        val savedZoomRatio = zoomRatio
        val savedCenterFractionX = centerFractionX
        val savedCenterFractionY = centerFractionY

        ssiv.recycle()

        // Set listener BEFORE setImage, as bitmap loading is synchronous
        ssiv.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onReady() {
                // Use SSIV's actual source dimensions
                val newWidth = ssiv.sWidth
                val newHeight = ssiv.sHeight
                val newMinScale = ssiv.minScale
                val newMaxScale = ssiv.maxScale

                // Restore center using normalized fractions
                val restoredCenter = PointF(
                    savedCenterFractionX * newWidth.toFloat(),
                    savedCenterFractionY * newHeight.toFloat(),
                )
                // Restore scale using zoom ratio relative to new minScale
                val restoredScale = (newMinScale * savedZoomRatio).coerceIn(newMinScale, newMaxScale)

                ssiv.setScaleAndCenter(restoredScale, restoredCenter)
                isTogglingImage = false
                ssiv.setOnImageEventListener(null)
            }
        })

        ssiv.setImage(ImageSource.bitmap(bitmapCopy))

        return isShowingScaled
    }

    fun recycle() = pageView?.let {
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)

        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                // Always copy to keep a stored copy independent from the one SSIV may recycle.
                // Fall back to the source bitmap if the copy fails so we never hand a null downstream.
                val originalCopy = data.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: data.bitmap
                originalBitmap = originalCopy

                val interpolated = applyInterpolationIfNeeded(originalCopy, config.interpolationMethod)
                val scaledCopy = if (interpolated != originalCopy) {
                    // interpolated already a new bitmap
                    interpolated
                } else {
                    interpolated.copy(Bitmap.Config.ARGB_8888, false) ?: interpolated
                }
                scaledBitmap = scaledCopy

                // Use a separate display copy so SSIV recycling won't touch stored references
                val displayBitmap = scaledCopy.copy(Bitmap.Config.ARGB_8888, false) ?: scaledCopy
                setImage(ImageSource.bitmap(displayBitmap))
                isVisible = true
            }
            is BufferedSource -> {
                // Use SSIV with hardware bitmap for default linear interpolation in paged mode
                if ((!isWebtoon || alwaysDecodeLongStripWithSSIV) && config.interpolationMethod == 1) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                // For custom interpolation or webtoon mode, decode through Coil
                // Decode at ORIGINAL size to preserve quality for custom interpolation
                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage

                            // Always copy to keep stored copy independent from the one SSIV may recycle.
                            // Fall back to the source bitmap if the copy fails so we never hand a null downstream.
                            val originalCopy = image.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: image.bitmap
                            originalBitmap = originalCopy

                            val interpolated = applyInterpolationIfNeeded(originalCopy, config.interpolationMethod)
                            val scaledCopy = if (interpolated != originalCopy) {
                                interpolated
                            } else {
                                interpolated.copy(Bitmap.Config.ARGB_8888, false) ?: interpolated
                            }
                            scaledBitmap = scaledCopy

                            // Use a separate display copy so SSIV recycling won't touch stored references
                            val displayBitmap = scaledCopy.copy(Bitmap.Config.ARGB_8888, false) ?: scaledCopy
                            setImage(ImageSource.bitmap(displayBitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    // Use Size.ORIGINAL to decode at full resolution for custom interpolation
                    .size(coil3.size.Size.ORIGINAL)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    /**
     * Apply interpolation to bitmap if using non-default interpolation method.
     * Uses OpenGL ES shaders for high-quality interpolation.
     */
    private fun applyInterpolationIfNeeded(bitmap: Bitmap, interpolationMethod: Int): Bitmap {
        // Skip for default linear (method 1) - let SubsamplingScaleImageView handle it
        if (interpolationMethod == 1) return bitmap

        val currentConfig = config ?: return bitmap

        // Get view dimensions
        val viewWidth = width
        val viewHeight = height

        // Only apply if view is measured
        if (viewWidth <= 0 || viewHeight <= 0) {
            return bitmap
        }

        // Calculate scale based on minimumScaleType (same logic as SSIV)
        // Scale types: 1=FitScreen, 2=Stretch, 3=FitWidth, 4=FitHeight, 5=Original, 6=SmartFit
        val scaleType = currentConfig.minimumScaleType
        val scale = when (scaleType) {
            SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE -> {
                // Fit screen - scale to fit both dimensions
                minOf(
                    viewWidth.toFloat() / bitmap.width,
                    viewHeight.toFloat() / bitmap.height,
                )
            }
            SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP -> {
                // Stretch/crop - scale to cover both dimensions
                maxOf(
                    viewWidth.toFloat() / bitmap.width,
                    viewHeight.toFloat() / bitmap.height,
                )
            }
            SubsamplingScaleImageView.SCALE_TYPE_CUSTOM -> {
                // Fit width
                viewWidth.toFloat() / bitmap.width
            }
            4 -> {
                // Fit height (SCALE_TYPE_START in SSIV, repurposed as fit height)
                viewHeight.toFloat() / bitmap.height
            }
            5 -> {
                // Original size - no scaling needed
                1f
            }
            6 -> {
                // Smart fit - fit width for tall images, fit height for wide images
                if (bitmap.height > bitmap.width) {
                    viewWidth.toFloat() / bitmap.width
                } else {
                    viewHeight.toFloat() / bitmap.height
                }
            }
            else -> {
                // Default to fit screen
                minOf(
                    viewWidth.toFloat() / bitmap.width,
                    viewHeight.toFloat() / bitmap.height,
                )
            }
        }

        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)

        // Skip if no size change
        if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
            return bitmap
        }

        // Map preference value to interpolation method
        // 1 = INTER_LINEAR (handled above), 2 = INTER_AREA, 3 = INTER_CUBIC, 4 = INTER_LANCZOS3
        val method = when (interpolationMethod) {
            2 -> BitmapScaler.InterpolationMethod.INTER_AREA
            3 -> BitmapScaler.InterpolationMethod.INTER_CUBIC
            4 -> BitmapScaler.InterpolationMethod.INTER_LANCZOS3
            else -> return bitmap
        }

        return try {
            // Convert hardware bitmap to software bitmap if needed
            val sourceBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            } else {
                bitmap
            }

            // Use OpenGL ES shader-based interpolation (highest quality)
            GLInterpolator.scale(sourceBitmap, targetWidth, targetHeight, method, context.resources)
                ?: BitmapScaler.scale(sourceBitmap, targetWidth, targetHeight, method)
        } catch (e: Exception) {
            // If interpolation fails, return original bitmap
            logcat(LogPriority.WARN) { "Failed to apply interpolation: ${e.message}" }
            bitmap
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     * interpolationMethod: 1=NEAREST, 2=LINEAR(default), 3=AREA, 4=CUBIC, 5=LANCZOS4
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
        val interpolationMethod: Int = 2,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
private const val AUTO_TOGGLE_ORIGINAL_RATIO = 1.08F
private const val AUTO_TOGGLE_SCALED_RATIO = 1.02F
private const val ZOOM_TOGGLE_DEBOUNCE_MS = 175L
