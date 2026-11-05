package ro.rdstr.os.maps

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The over-limit chime, the emulator's two notes: 880 Hz, then 1318.5 Hz 120 ms later, each rising in 10 ms to 0.18 and
 * fading to 0.001 by 350 ms. Played as navigation guidance, which the unit mixes over the music.
 */
class Chime {
    private val track: AudioTrack

    init {
        val samples = ShortArray((RATE * LENGTH_S).toInt())
        for ((hz, at) in NOTES) {
            val start = (at * RATE).toInt()
            for (i in 0 until (NOTE_S * RATE).toInt()) {
                val j = start + i
                if (j >= samples.size) break
                val t = i.toDouble() / RATE
                val gain = if (t < 0.01) GAIN * t / 0.01 else GAIN * exp(ln(0.001 / GAIN) * (t - 0.01) / 0.34)
                samples[j] = (samples[j] + gain * sin(2 * PI * hz * t) * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
    }

    fun play() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        track.reloadStaticData()
        track.play()
    }

    fun release() = track.release()

    private companion object {
        const val RATE = 44100
        const val LENGTH_S = 0.52
        const val NOTE_S = 0.4
        const val GAIN = 0.18
        val NOTES = listOf(880.0 to 0.0, 1318.5 to 0.12)
    }
}
