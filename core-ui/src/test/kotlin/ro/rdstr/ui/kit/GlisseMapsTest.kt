package ro.rdstr.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlisseMapsTest {
    @Test fun rimLightsTheEdgeOnly() {
        val w = 100f; val h = 40f
        val size = GlisseMaps.rimSize(w, h, 1f)
        assertEquals(100, size[0]); assertEquals(40, size[1])
        val px = GlisseMaps.rim(w, h, 20f, 0.13f, 1f, 0x14FFFFFF)
        // inside, past the rim, nothing
        assertEquals(0, px[20 * 100 + 50])
        // the top edge carries the hairline and some light
        val top = px[0 * 100 + 50]
        assertTrue((top ushr 24) > 0)
        // the left and right ends face the light differently from the top, but both are drawn
        assertTrue((px[20 * 100 + 0] ushr 24) > 0)
        assertTrue((px[20 * 100 + 99] ushr 24) > 0)
        // outside the rounded corner, nothing
        assertEquals(0, px[0])
    }

    @Test fun rimFollowsDensity() {
        val size = GlisseMaps.rimSize(60f, 44f, 0.8f)
        assertEquals(48, size[0]); assertEquals(35, size[1])
        assertEquals(48 * 35, GlisseMaps.rim(60f, 44f, 22f, 0.3f, 0.8f, 0x14000000).size)
    }

    @Test fun selectionHighlightsInsideTheEdge() {
        val size = GlisseMaps.selectionSize(100f, 40f, 1f)
        assertEquals(100, size[0]); assertEquals(40, size[1])
        for (bright in listOf(false, true)) {
            val px = GlisseMaps.selection(100f, 40f, 20f, 1f, bright)
            assertEquals(0, px[20 * 100 + 50] ushr 24)
            assertTrue((px[0 * 100 + 50] ushr 24) > 0)
        }
    }
}
