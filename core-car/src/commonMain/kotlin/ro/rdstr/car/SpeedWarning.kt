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

    /** How the speed dial's ring is lit: [lit] of [TICKS] ticks from the bottom clockwise, all in one [tone]. */
    data class Ring(val lit: Int, val tone: Tone)
    enum class Tone { WHITE, ORANGE, RED }

    companion object {
        const val HOLD_MS = 4000L
        const val TICKS = 60
        /** With no limit known, the ring is full at this speed. */
        const val FULL_KMH = 240
        /** From this share of the limit up, the lit ticks turn orange: the car is close to it. */
        const val NEAR_LIMIT = 0.85

        /**
         * The ring in proportion to the limit: it fills as the speed nears the posted limit and is full at it, white
         * below [NEAR_LIMIT] of it and orange above; over the limit the whole ring is red. With no limit known it
         * follows the speed alone, in white.
         */
        fun ring(kmh: Int, state: State): Ring {
            if (state.over) return Ring(TICKS, Tone.RED)
            val share = kmh.toDouble() / (state.limit ?: FULL_KMH)
            val lit = (minOf(1.0, share) * TICKS).let { kotlin.math.round(it).toInt() }
            return Ring(lit, if (state.limit != null && share >= NEAR_LIMIT) Tone.ORANGE else Tone.WHITE)
        }
    }
}
