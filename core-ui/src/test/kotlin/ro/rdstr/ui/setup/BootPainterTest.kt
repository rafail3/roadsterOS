package ro.rdstr.ui.setup

import android.graphics.Bitmap
import android.graphics.Canvas
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The approved boot and start pictures, pixel for pixel: a checksum of the 1024x600 frames BootPainter draws at fixed
 * moments. The figures were taken from the drawing as it stood in BootView, compared pixel by pixel before it moved.
 */
class BootPainterTest {
    @get:Rule val paparazzi = Paparazzi()

    private fun checksum(draw: (Canvas) -> Unit): Long {
        val b = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF07080A.toInt())
        draw(c)
        val px = IntArray(1024 * 600).also { b.getPixels(it, 0, 1024, 0, 0, 1024, 600) }
        return px.foldIndexed(0L) { i, s, p -> (s * 31 + (p.toLong() and 0xFFFFFFFFL) * (i % 89 + 1)) % 1_000_000_007 }
    }

    @Test
    fun `the boot and start pictures are the approved ones`() {
        val art = BootPainter()
        val still = art.startStill("1.18.0")
        val got = listOf(300.0, 900.0, 1450.0, 2700.0, 9999.0).map { t -> checksum { art.drawBoot(it, t) } } +
            checksum { art.drawBoot(it, 9999.0, still = true) } +
            listOf(0.0, 600.0).map { t -> checksum { art.drawStart(it, t, still) } }
        assertEquals(EXPECTED, got)
    }

    private companion object {
        val EXPECTED = listOf(570089826L, 332675434L, 605547904L, 256198882L, 354936185L, 388784343L, 175317517L, 872526636L)
    }
}
