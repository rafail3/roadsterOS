package ro.rdstr.ui.sound

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.rdstr.car.EqPreset
import ro.rdstr.car.ListeningSeat
import ro.rdstr.car.SoundSettings
import ro.rdstr.car.SpeakerFilter

class SoundModelTest {
    @Test fun formats() {
        assertEquals("600 Hz", SoundModel.hz(600))
        assertEquals("2 kHz", SoundModel.hz(2000))
        assertEquals("1.25 kHz", SoundModel.hz(1250))
        assertEquals("6.3 kHz", SoundModel.hz(6300))
        assertEquals("+3", SoundModel.signed(3)); assertEquals("0", SoundModel.signed(0)); assertEquals("-2", SoundModel.signed(-2))
        assertEquals("0.0 ms", SoundModel.ms(0)); assertEquals("20.0 ms", SoundModel.ms(200)); assertEquals("1.5 ms", SoundModel.ms(15))
        // Math.round(0.5 * 3.4 * ...) rounds halves up
        assertEquals("17 cm", SoundModel.cm(5))
    }

    @Test fun summaries() {
        val s = SoundSettings()
        assertEquals("Flat", SoundModel.summary(SoundSection.EQ, s))
        assertEquals("Custom", SoundModel.summary(SoundSection.EQ, s.copy(preset = EqPreset.USER)))
        assertEquals("Centered", SoundModel.summary(SoundSection.BALANCE, s))
        assertEquals("Left 3, Front 2", SoundModel.summary(SoundSection.BALANCE, s.copy(balance = -3, fader = 2)))
        assertEquals("Rear 7", SoundModel.summary(SoundSection.BALANCE, s.copy(fader = -7)))
        assertEquals("Center", SoundModel.summary(SoundSection.FIELD, s))
        assertEquals("Front Row", SoundModel.summary(SoundSection.FIELD, s.copy(seat = ListeningSeat.FRONT)))
        assertEquals("Rear Right", SoundModel.summary(SoundSection.FIELD, s.copy(seat = ListeningSeat.REAR_RIGHT)))
        assertEquals("On", SoundModel.summary(SoundSection.LOUDNESS, s))
        assertEquals("Off", SoundModel.summary(SoundSection.BASS, s))
        assertEquals("+6 dB", SoundModel.summary(SoundSection.BASS, s.copy(bass = true)))
        assertEquals("Off", SoundModel.summary(SoundSection.FILTERS, s))
        assertEquals("2 On", SoundModel.summary(SoundSection.FILTERS, s.copy(front = SpeakerFilter(highPass = true), rear = SpeakerFilter(lowPass = true))))
    }

    @Test fun stepsSitAtTheNearestValue() {
        assertEquals(11, SoundModel.nearest(SoundModel.LOW_HZ, 600))
        assertEquals(11, SoundModel.nearest(SoundModel.LOW_HZ, 610))
        // a tie keeps the first
        assertEquals(0, SoundModel.nearest(listOf(10, 20), 15))
    }

    @Test fun pages() = assertEquals(listOf("32–3k Hz", "3.5k–10k Hz", "11k–20k Hz"), SoundModel.pages())

    @Test fun dragPositions() {
        assertEquals(-12, SoundModel.bandAt(0f)); assertEquals(12, SoundModel.bandAt(1f)); assertEquals(0, SoundModel.bandAt(0.5f))
        assertEquals(12, SoundModel.bandAt(2f))
        assertEquals(-7 to 7, SoundModel.balanceAt(0f, 0f))
        assertEquals(7 to -7, SoundModel.balanceAt(1f, 1f))
        assertEquals(0 to 0, SoundModel.balanceAt(0.5f, 0.5f))
        assertEquals(0.5f, SoundModel.dotX(0), 0f); assertEquals(0f, SoundModel.dotY(7), 0f)
    }

    @Test fun seatsLit() {
        val front = SoundSettings(seat = ListeningSeat.FRONT)
        assertTrue(SoundModel.lit(front, ListeningSeat.FRONT_RIGHT)); assertFalse(SoundModel.lit(front, ListeningSeat.REAR_LEFT))
        assertFalse(SoundModel.lit(SoundSettings(seat = ListeningSeat.CUSTOM), ListeningSeat.FRONT_LEFT))
        assertTrue(SoundModel.lit(SoundSettings(seat = ListeningSeat.REAR_LEFT), ListeningSeat.REAR_LEFT))
    }

    @Test fun speakerLevels() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), SoundModel.speakerLevels(0, 0), 0f)
        // all the way right and to the rear: only the rear right plays
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), SoundModel.speakerLevels(7, -7), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0.5714286f, 1f, 0.5714286f), SoundModel.speakerLevels(-3, 0), 1e-6f)
    }

    @Test fun responseAxis() {
        assertEquals(0f, SoundModel.responseX(20), 1e-3f)
        assertEquals(1000f, SoundModel.responseX(20000), 1e-3f)
        assertEquals(-400f, SoundModel.responseLo(false, 63), 0f)
        assertEquals(1400f, SoundModel.responseHi(false, 20000), 0f)
    }
}
