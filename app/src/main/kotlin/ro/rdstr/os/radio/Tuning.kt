package ro.rdstr.os.radio

import ro.rdstr.car.CarEvent
import ro.rdstr.car.FreqDisplay
import ro.rdstr.car.Tuner
import ro.rdstr.ui.Emulates

/** The radio screen's figures, the same as the emulator's (core-car Tuner and FreqDisplay). */
object Tuning {
    /** The tuner's ranges and steps in kHz. */
    @Emulates("radio.BANDS")
    val BANDS: Map<CarEvent.Radio.Band, Tuner.Range> = Tuner.BANDS

    /** A frequency as the screen writes it: kHz on AM; MHz on FM, with a second decimal only off the 100 kHz grid. */
    @Emulates("radio.formatFreq")
    fun formatFreq(band: CarEvent.Radio.Band, khz: Int): String = FreqDisplay.format(band, khz)
}
