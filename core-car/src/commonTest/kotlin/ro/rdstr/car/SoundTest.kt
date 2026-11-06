package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundTest {
    private val flat = SoundSettings()
    private fun w(call: String, vararg args: Int) = DspWrite(call, args.toList())

    @Test
    fun `a preset is one call, a moved band makes the curve the User preset`() {
        assertEquals(listOf(w("DspSetEqm", 3)), Sound.writes(flat, Sound.withPreset(flat, EqPreset.ROCK)))
        val band = Sound.withBand(flat, 2, 5)
        assertEquals(EqPreset.USER, band.preset)
        val calls = Sound.writes(flat, band)
        assertEquals(w("DspSetEqm", 0), calls.first())
        assertEquals(31, calls.size)
        assertTrue(w("DspSetEqBand", 2, 17) in calls)
        // already Custom: only the band that moved
        assertEquals(listOf(w("DspSetEqBand", 2, 4)), Sound.writes(band, Sound.withBand(band, 2, -8)))
    }

    @Test
    fun `balance and fader on the vendor's grid, 0 the left and the front`() {
        assertEquals(listOf(w("DspBalSet", 4), w("DspFadSet", 5)), Sound.writes(flat, flat.copy(balance = -3, fader = 2)))
        assertEquals(7, Sound.clamp(flat.copy(balance = 20)).balance)
    }

    @Test
    fun `filters at the front and rear offsets, back to their widest when switched off`() {
        val on = flat.copy(front = SpeakerFilter(highPass = true, highPassHz = 80))
        assertEquals(listOf(w("DspSetFilter", 5, 1), w("DspSetFilter", 7, 80)), Sound.writes(flat, on))
        assertEquals(listOf(w("DspSetFilter", 5, 0), w("DspSetFilter", 7, 20)), Sound.writes(on, flat))
        val rear = flat.copy(rear = SpeakerFilter(lowPass = true, lowPassHz = 8000))
        assertEquals(listOf(w("DspSetFilter", 8, 1), w("DspSetFilter", 10, 8000)), Sound.writes(flat, rear))
    }

    @Test
    fun `seat, delays, loudness, bass and antenna in the vendor's units`() {
        val s = flat.copy(seat = ListeningSeat.CUSTOM, delays = listOf(0, 15, 0, 0), loudLowHz = 180, bass = true, bassGain = 12, subHz = 120, antennaPower = false)
        assertEquals(
            listOf(w("DspSetPosMode", 0), w("DspSetPosDelay", 1, 15), w("DspSetLoud", 1, 180), w("DspSetVBass", 0, 1),
                w("DspSetVBass", 1, 400), w("SubFreqSet", 120), w("SetExAmp", 0)),
            Sound.writes(flat, s),
        )
        assertEquals(Sound.resetBass(flat), Sound.resetBass(s.copy(antennaPower = true)).copy(seat = flat.seat, delays = flat.delays, loudLowHz = 600))
    }

    @Test
    fun `presets spread over the thirty bands, and the settings are kept across starts`() {
        assertEquals(30, Sound.presetBands(EqPreset.POP)!!.size)
        assertEquals(5, Sound.presetBands(EqPreset.ROCK)!!.first())
        val s = Shell.reduce(ShellState(), ShellAction.SetSound(Sound.withBand(flat, 0, 9)))
        assertEquals(9, Shell.decodePreferences(Shell.encodePreferences(s)).dsp.bands[0])
    }
}
