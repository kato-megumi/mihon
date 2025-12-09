package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean

    /**
     * Get the scaled bitmap for the given page, if available.
     */
    fun getScaledBitmap(page: ReaderPage): Bitmap? = null

    /**
     * Check if the page has a scaled image different from original.
     */
    fun hasScaledImage(page: ReaderPage): Boolean = false

    /**
     * Check if the page is currently showing the scaled image.
     */
    fun isShowingScaledImage(page: ReaderPage): Boolean = true

    /**
     * Toggle between scaled and original image for the given page.
     * Returns true if now showing scaled, false if showing original.
     */
    fun toggleScaledOriginal(page: ReaderPage): Boolean = true
}
