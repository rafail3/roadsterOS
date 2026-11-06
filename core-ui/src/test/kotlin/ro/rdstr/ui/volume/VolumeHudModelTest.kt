package ro.rdstr.ui.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeHudModelTest {
    @Test fun positionToLevel() {
        assertEquals(0, levelAt(-50f, 100f, 300f))
        assertEquals(0, levelAt(100f, 100f, 300f))
        assertEquals(15, levelAt(250f, 100f, 300f))
        assertEquals(30, levelAt(400f, 100f, 300f))
        assertEquals(30, levelAt(900f, 100f, 300f))
        // half a step rounds up, as Math.round in the emulator
        assertEquals(1, levelAt(105f, 100f, 300f))
        assertEquals(0, levelAt(104.9f, 100f, 300f))
    }

    @Test fun fillIsEmptyWhileMuted() {
        assertEquals(0.5f, fillFraction(15, muted = false), 0f)
        assertEquals(0f, fillFraction(15, muted = true), 0f)
    }

    @Test fun firstValuesAreNotAChange() {
        val m = VolumeHudModel()
        assertFalse(m.update(12, false, 0, now = 0))
        assertFalse(m.shown)
        assertFalse(m.update(12, false, 0, now = 10))
        assertTrue(m.update(13, false, 0, now = 20))
        assertTrue(m.shown)
    }

    @Test fun hidesAfterVisibleMsFromTheLastChange() {
        val m = started()
        m.update(13, false, 0, now = 1000)
        m.update(14, false, 0, now = 1400)
        assertFalse(m.tick(2899))
        assertTrue(m.tick(2900))
        assertFalse(m.shown)
    }

    @Test fun openingWithNothingChangedShowsIt() {
        val m = started()
        assertTrue(m.update(12, false, 1, now = 0))
        assertEquals(VISIBLE_MS, m.hideAt)
    }

    @Test fun staysWhileHeldAndRestartsOnRelease() {
        val m = started()
        m.hold(0)
        m.update(20, false, 0, now = 100)
        assertEquals(NEVER, m.hideAt)
        assertFalse(m.tick(10_000))
        m.release(10_000)
        assertTrue(m.tick(10_000 + VISIBLE_MS))
    }

    @Test fun quietKeepsItDown() {
        val m = started()
        m.quiet = true
        m.update(20, true, 0, now = 0)
        assertFalse(m.shown)
    }

    @Test fun mutingShakesOnceAndUnmutingCountsUp() {
        val m = started()
        m.update(12, true, 0, now = 0)
        assertTrue(m.shaking(0))
        m.update(12, false, 0, now = 100)
        m.update(12, true, 0, now = 200)
        assertEquals(0L, m.shakeFrom) // not restarted while running
        assertFalse(m.shaking(SHAKE_MS))
        m.update(12, false, 0, now = 1000)
        assertEquals(0, m.number(1000))
        assertEquals(countAt(12, 240), m.number(1240))
        assertEquals(12, m.number(1000 + MuteFill.MS))
    }

    @Test fun noMotionWhenAnimationsAreOff() {
        val m = started()
        m.update(12, true, 0, now = 0, animate = false)
        assertFalse(m.shaking(0))
        assertFalse(m.muteFill.active(0))
        m.update(12, false, 0, now = 10, animate = false)
        assertEquals(12, m.number(10))
    }

    @Test fun countIsEaseOutCubic() {
        assertEquals(0, countAt(30, 0))
        assertEquals(26, countAt(30, MuteFill.MS / 2)) // 30 × (1 − 0.5³) = 26.25
        assertEquals(30, countAt(30, MuteFill.MS))
    }

    @Test fun muteFillWindow() {
        val f = MuteFill(muted = false)
        assertFalse(f.update(false, 0))
        assertTrue(f.update(true, 100))
        assertTrue(f.active(100 + MuteFill.MS - 1))
        assertFalse(f.active(100 + MuteFill.MS))
    }

    private fun started() = VolumeHudModel().apply { update(12, false, 0, now = -10_000) }
}
