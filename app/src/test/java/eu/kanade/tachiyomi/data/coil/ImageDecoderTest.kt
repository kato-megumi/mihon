package eu.kanade.tachiyomi.data.coil

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class ImageDecoderTest {

    @Test
    fun `extracts the detected region with its rows intact`() {
        // 4x3 RGBA image where every pixel encodes its own coordinates.
        val width = 4
        val height = 3
        val pixels = ByteArray(width * height * PIXEL_BYTES)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * PIXEL_BYTES
                pixels[i] = x.toByte()
                pixels[i + 1] = y.toByte()
                pixels[i + 2] = 1
                pixels[i + 3] = 0xFF.toByte()
            }
        }

        // Borders trimmed to the 2x2 region starting at (1, 1), as CropBorders reports them.
        // The buffer is exhausted first, matching the decoder that has already read the pixels.
        val source = ByteBuffer.wrap(pixels)
        source.position(source.limit())
        val cropped = extractRows(source, width, left = 1, top = 1, cropWidth = 2, cropHeight = 2)

        val expected = ByteArray(2 * 2 * PIXEL_BYTES)
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                val i = (y * 2 + x) * PIXEL_BYTES
                expected[i] = (x + 1).toByte()
                expected[i + 1] = (y + 1).toByte()
                expected[i + 2] = 1
                expected[i + 3] = 0xFF.toByte()
            }
        }

        val actual = ByteArray(cropped.remaining())
        cropped.get(actual)
        assertArrayEquals(expected, actual)
    }
}

/** RGBA, 4 bytes per pixel. */
private const val PIXEL_BYTES = 4
