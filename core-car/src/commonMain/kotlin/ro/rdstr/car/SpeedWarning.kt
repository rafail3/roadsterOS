package ro.rdstr.car

/**
 * The speed limit sign and the warning beside the speed, with a threshold of 0: the car is over from the first km/h
 * above the posted limit, and a short chime sounds once each time it goes over (a turned-down limit under a fast car
 * counts too). A limit the road lookup misses for a moment, between two fixes, stays for [HOLD_MS].
 */
class SpeedWarning {
    data class State(val limit: Int?, val over: Boolean, val chime: Boolean)

    /** The chime, a setting the user turns off later. */
    var chimeOn = true

    private var wasOver = false
    private var limit: Int? = null
    private var limitAt = 0L

    /** [roadLimit]: the limit of the road the car is on now, null when it has none or none was found. */
    fun update(reading: Speedometer.Reading, roadLimit: Int?, timeMs: Long): State {
        if (roadLimit != null) { limit = roadLimit; limitAt = timeMs } else if (timeMs - limitAt >= HOLD_MS) limit = null
        val l = limit
        val over = l != null && !reading.stopped && reading.kmh > l
        val chime = over && !wasOver && chimeOn
        wasOver = over
        return State(l, over, chime)
    }

    companion object {
        const val HOLD_MS = 4000L
    }
}
