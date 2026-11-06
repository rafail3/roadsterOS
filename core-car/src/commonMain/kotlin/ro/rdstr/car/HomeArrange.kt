package ro.rdstr.car

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Arranging the icons on home, as a phone's home screen does: a press held [HOLD_MS] on an icon starts it, unless the
 * finger first moves [SLOP_PX]; the held icon follows the finger along the row, never past its first or last place,
 * the others make room, and the drop moves it ([HomeApps.move]). A move ends the arranging; a hold without one keeps
 * it, so an icon can be taken off with its badge. An icon picked up never opens its app on the drop.
 * Distances are in the layout's pixels; the slot is one icon's step to the next.
 */
object HomeArrange {
    const val HOLD_MS = 500L
    const val SLOP_PX = 10.0

    /** The finger's offset kept inside the row: from the first place to the last. */
    fun clamp(from: Int, dx: Double, slot: Double, count: Int): Double =
        dx.coerceIn(-from * slot, (count - 1 - from) * slot)

    /** The place the held icon would drop into. */
    fun dropIndex(from: Int, dx: Double, slot: Double, count: Int): Int =
        (from + (clamp(from, dx, slot, count) / slot).roundToInt()).coerceIn(0, count - 1)

    /** Where icon [i] is drawn while one is held: the held one under the finger, the ones it passed a place aside. */
    fun offset(i: Int, from: Int, dx: Double, slot: Double, count: Int): Double {
        val to = dropIndex(from, dx, slot, count)
        return when {
            i == from -> clamp(from, dx, slot, count)
            i in (from + 1)..to -> -slot
            i in to until from -> slot
            else -> 0.0
        }
    }

    /** After the drop, the held icon starts this far from its new place and glides the rest of the way. */
    fun settle(from: Int, dx: Double, slot: Double, count: Int): Double =
        clamp(from, dx, slot, count) - (dropIndex(from, dx, slot, count) - from) * slot

    /** A press that has not turned into a hold yet becomes a swipe once the finger moves this far. */
    fun cancelsHold(dx: Double): Boolean = abs(dx) > SLOP_PX

    /** Whether the arranging goes on after a drop: only when the icon stayed in its place. */
    fun keepsArranging(from: Int, to: Int): Boolean = from == to

    /** An icon taken off shrinks away this long before it leaves the row. */
    const val REMOVE_MS = 180L
    /** The icons that stay glide this long to the row's new centre; one added grows in this long. */
    const val GLIDE_MS = 320L
    const val GROW_MS = 240L

    /** How an icon moves when the row changes: glides from [fromDx] pixels away, or grows in when [added]. */
    @kotlinx.serialization.Serializable
    data class Glide(val id: String, val fromDx: Double = 0.0, val added: Boolean = false)

    /**
     * When icons come or go, the row re-centres: each icon that stays starts where it was and glides to its new place,
     * a new one grows in. [before] and [after] are each icon's left edge, by id; a first layout moves nothing.
     */
    fun glides(before: Map<String, Double>, after: Map<String, Double>): List<Glide> {
        if (before.isEmpty()) return emptyList()
        return after.mapNotNull { (id, x) ->
            val was = before[id]
            when {
                was == null -> Glide(id, added = true)
                was != x -> Glide(id, fromDx = was - x)
                else -> null
            }
        }
    }

    /** A tap opens the app only outside the arranging and when the press did not pick the icon up. */
    fun opens(arranging: Boolean, pickedUp: Boolean): Boolean = !arranging && !pickedUp
}
