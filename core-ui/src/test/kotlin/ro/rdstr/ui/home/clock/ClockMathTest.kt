package ro.rdstr.ui.home.clock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Bezier

class ClockMathTest {
    private val roll = Base.DARK.easeRoll
    private val easeOut = Base.DARK.easeOut

    @Test fun handAngles() {
        assertEquals(105f, HandAngles.hour(15, 30, 0), 1e-4f)
        assertEquals(180f, HandAngles.minute(30, 0), 1e-4f)
        assertEquals(0.25f, HandAngles.hour(0, 0, 30), 1e-4f)
        assertEquals(3f, HandAngles.minute(0, 30), 1e-4f)
        assertEquals(354f, HandAngles.second(59), 1e-4f)
        assertEquals(359.99167f, HandAngles.hour(23, 59, 59), 1e-3f)
        assertEquals(359.9f, HandAngles.minute(59, 59), 1e-3f)
    }

    @Test fun cubicBezier() {
        assertEquals(0f, roll.at(0f), 0f)
        assertEquals(1f, roll.at(1f), 0f)
        // CSS `ease` at half time
        assertEquals(0.8024f, Bezier(0.25f, 0.1f, 0.25f, 1f).at(0.5f), 1e-3f)
        assertEquals(0.5f, Bezier(0f, 0f, 1f, 1f).at(0.5f), 1e-4f)
    }

    @Test fun glyphInKeyframes() {
        val f = FloatArray(3)
        Roll.glyphIn(0f, roll, f)
        assertArrayEquals(floatArrayOf(0.34f, 0.96f, 0f), f, 1e-5f)
        Roll.glyphIn(0.45f, roll, f)
        assertEquals(0.8f, f[2], 1e-5f)
        // transform eases over the whole roll, not the opacity segment
        assertEquals(0.34f * (1 - roll.at(0.45f)), f[0], 1e-5f)
        Roll.glyphIn(1f, roll, f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), f, 1e-5f)
    }

    @Test fun glyphOutKeyframes() {
        val f = FloatArray(3)
        Roll.glyphOut(0f, f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), f, 1e-5f)
        Roll.glyphOut(0.35f, f)
        assertEquals(0.5f, f[2], 1e-5f)
        Roll.glyphOut(0.7f, f)
        assertEquals(0f, f[2], 1e-5f)
        Roll.glyphOut(1f, f)
        assertArrayEquals(floatArrayOf(-0.3f, 0.97f, 0f), f, 1e-5f)
    }

    @Test fun firstTextDoesNotRoll() {
        val c = DigitCells(560, 336)
        c.set("12:59", 0, true)
        assertEquals(5, c.length)
        assertFalse(c.moving(0))
        assertEquals('1', c.char[4])
        assertEquals('9', c.char[0])
    }

    @Test fun cellsAreKeyedFromTheRight() {
        val c = DigitCells(560, 336)
        c.set("12:59", 0, true)
        c.set("1:00", 1000, true)
        assertEquals(4, c.length)
        // key 4 held "2" and rolls to "1"; the colon stays; the minutes roll
        assertEquals('1', c.char[3])
        assertEquals(1000L, c.inStart[3])
        assertEquals('2', c.outChar[3][0])
        assertEquals(NONE, c.inStart[2])
        assertEquals(1000L, c.inStart[1])
        assertEquals('5', c.outChar[1][0])
        assertEquals(1000L, c.inStart[0])
        assertTrue(c.moving(1500))
        assertFalse(c.moving(1560))
    }

    @Test fun addedCellRollsInWithoutLeavingGlyph() {
        val c = DigitCells(560, 336)
        c.set("9:59", 0, true)
        c.set("10:00", 1000, true)
        assertEquals(1000L, c.inStart[4])
        assertEquals(NONE, c.outStart[4][0])
        assertEquals('9', c.outChar[3][0])
    }

    @Test fun noMotionPlacesDigits() {
        val c = DigitCells(560, 336)
        c.set("12:59", 0, true)
        c.set("13:00", 1000, false)
        assertFalse(c.moving(1000))
        assertEquals('3', c.char[3])
    }

    @Test fun ticks() {
        assertEquals(240f, Ticks.x(0, 222), 0f)
        assertEquals(18f, Ticks.y(0, 222), 0f)
        assertEquals(346f, Ticks.x(5, 212), 0f)
        assertEquals(56.4f, Ticks.y(5, 212), 0f)
        assertEquals(466f, Ticks.x(15, 226), 0f)
    }

    @Test fun handTakesTheShortWayForward() {
        val h = Hand(600, easeOut)
        h.set(359.9f, 0, false)
        h.set(0f, 1000, true)
        // a per-second step jumps, forward past 360
        assertFalse(h.moving(1000))
        assertEquals(360f, h.at(1000), 1e-3f)
        // a set clock: 10 past to 50 past goes back 120 deg, not forward 240
        val m = Hand(600, easeOut)
        m.set(60f, 0, false)
        m.set(300f, 1000, true)
        assertTrue(m.moving(1300))
        assertEquals(-60f, m.at(1600), 1e-3f)
        val mid = m.at(1300)
        assertTrue(mid < 60f && mid > -60f)
    }

    @Test fun handRetargetsFromWhereItIs() {
        val h = Hand(600, easeOut)
        h.set(0f, 0, false)
        h.set(90f, 0, true)
        val at = h.at(300)
        h.set(100f, 300, true)
        assertEquals(at, h.at(300), 1e-4f)
        assertEquals(100f, h.at(900), 1e-4f)
    }

    @Test fun baselineAsBlinkPlacesIt() {
        // Inter: ascent 1984, descent 494 of 2048
        assertEquals(110.5f, baseline(128f, 128f, 1984f / 2048, 494f / 2048), 1e-4f)
    }

    @Test fun premultipliedMix() {
        assertEquals(0x80FFFFFF.toInt(), mixColor(0x00000000, 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF808080.toInt(), mixColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0x12345678, mixColor(0x12345678, 0x7F000000, 0f))
    }
}
