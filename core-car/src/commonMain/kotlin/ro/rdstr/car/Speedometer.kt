package ro.rdstr.car

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The speed the map shows, from the GPS fixes. A GPS chip measures its speed itself, from the Doppler shift of the
 * satellite signals, to within about 1 km/h and without the jitter of the positions; that speed is used as it is.
 * Only a fix without it falls back to the distance driven over the last [WINDOW_MS]. Standing still, the chip drifts
 * by a few km/h, so the car counts as stopped below [STOP_KMH] and moving again only above [START_KMH].
 */
class Speedometer {
    data class Reading(val kmh: Int, val stopped: Boolean)

    private class Fix(val lon: Double, val lat: Double, val timeMs: Long)

    var reading = Reading(0, true)
        private set

    private val fixes = ArrayDeque<Fix>()

    /** A new fix; [chipKmh] is the chip's own speed when the fix carries one. */
    fun update(lon: Double, lat: Double, timeMs: Long, chipKmh: Double?): Reading {
        fixes.addLast(Fix(lon, lat, timeMs))
        // the window starts at the last fix at least WINDOW_MS old, so a gap in the fixes still gives a speed
        while (fixes.size > 2 && timeMs - fixes[1].timeMs >= WINDOW_MS) fixes.removeFirst()
        val kmh = chipKmh ?: fromPositions() ?: return reading
        val stopped = if (reading.stopped) kmh <= START_KMH else kmh < STOP_KMH
        reading = Reading(if (stopped) 0 else kmh.roundToInt(), stopped)
        return reading
    }

    private fun fromPositions(): Double? {
        if (fixes.size < 2) return null
        val a = fixes.first()
        val b = fixes.last()
        val seconds = (b.timeMs - a.timeMs) / 1000.0
        if (seconds <= 0) return null
        val dx = (b.lon - a.lon) * M_PER_DEG * cos((a.lat + b.lat) / 2 * PI / 180)
        val dy = (b.lat - a.lat) * M_PER_DEG
        return sqrt(dx * dx + dy * dy) / seconds * 3.6
    }

    companion object {
        const val WINDOW_MS = 3000L
        const val STOP_KMH = 3.0
        const val START_KMH = 5.0
        private const val M_PER_DEG = 111_320.0
    }
}
