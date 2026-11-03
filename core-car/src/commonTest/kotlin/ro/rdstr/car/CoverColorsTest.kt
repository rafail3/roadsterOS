package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverColorsTest {
    private val size = 48
    private fun image(top: Int, bottom: Int) = IntArray(size * size) { i -> if (i / size < size / 2) top else bottom }

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun brightness(c: Int) = red(c) + green(c) + blue(c)

    @Test
    fun `the top colour comes from the top edge and the bottom colour from the bottom edge`() {
        val (top, bottom) = CoverColors.from(image(0xFFE04030.toInt(), 0xFF3050E0.toInt()), size)
        assertTrue(red(top) > blue(top), "top keeps the red of the top edge")
        assertTrue(blue(bottom) > red(bottom), "bottom keeps the blue of the bottom edge")
    }

    @Test
    fun `both colours are dark enough for white text and the bottom is darker`() {
        val white = 0xFFFFFFFF.toInt()
        val (top, bottom) = CoverColors.from(image(white, white), size)
        assertTrue(CoverColors.luminance(top) <= CoverColors.MAX_LUMINANCE)
        assertTrue(CoverColors.luminance(bottom) <= CoverColors.MAX_LUMINANCE)
        assertTrue(brightness(bottom) < brightness(top))
    }

    @Test
    fun `a missing or transparent cover falls back to neutral greys`() {
        val fallback = CoverColors.FALLBACK_TOP to CoverColors.FALLBACK_BOTTOM
        assertEquals(fallback, CoverColors.from(IntArray(0), size))
        assertEquals(fallback, CoverColors.from(IntArray(size * size), size))
    }

    @Test
    fun `colours travel as hex`() {
        assertEquals("#2c2c2e", CoverColors.hex(CoverColors.FALLBACK_TOP))
    }
}
