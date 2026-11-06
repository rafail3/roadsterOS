package ro.rdstr.car

import kotlinx.serialization.Serializable

/** The lines over the reverse camera's picture: none, fixed ones, or ones that bend as the steering wheel turns. */
enum class Guidelines { OFF, FIXED, STEERING }

/** How much the music and the radio are lowered while reverse gear is in; [code] is the vendor's level. */
enum class ReverseDip(val code: Int) { NONE(0), WEAK(1), MEDIUM(2), STRONG(3) }

@Serializable
data class Point(val x: Double, val y: Double)

/** The points of the two guidelines on the 1024x600 screen (Camera.guides). */
@Serializable
data class Guides(
    val leftFix: Point, val leftControl: Point, val leftFar: Point, val leftMiddle: Point, val leftNear: Point,
    val rightFix: Point, val rightControl: Point, val rightFar: Point, val rightMiddle: Point, val rightNear: Point,
)

@Serializable
data class CameraSettings(
    /** The picture flipped left to right. */
    val mirror: Boolean = false,
    val guidelines: Guidelines = Guidelines.FIXED,
    /** How far the lines sit from where the vendor draws them, in pixels of the 1024x600 screen, negative higher up. */
    val linePos: Int = 0,
    val dip: ReverseDip = ReverseDip.MEDIUM,
)

/**
 * The reverse camera's settings and the vendor calls that apply them (MainUI com.ts.set.SettingVideoActivity and the
 * factory menu, libts50xhw): the camera's own screen stays the vendor's and draws the lines from these values; fixed
 * and bending lines are never both on; the factory values (FtSet) are kept only after FtSet.Save(1).
 */
object Camera {
    const val LINE_POS_MIN = -200
    const val LINE_POS_MAX = 70
    const val LINE_POS_STEP = 10

    /** The steering angle as the CAN box reports it (CAN_EpsInfo.Eps): positive to the left, up to this either way. */
    const val EPS_MAX = 1080
    /** Within this the lines stay straight; each such step past it is one of [TURN_STEPS]. */
    const val EPS_STEP = 46
    private const val TURN_STEPS = 23

    // the vendor's points on the 1024x600 screen (MainUI com.ts.can.CameraUIView), in its order: far, middle and
    // near ends of the left and right lines, then the two curve controls; straight, at full left lock, at full right
    private val STRAIGHT = points(408, 240, 615, 240, 360, 273, 660, 273, 250, 350, 770, 350, 305, 311, 715, 311)
    private val FULL_LEFT = points(123, 276, 292, 242, 165, 337, 485, 280, 120, 440, 678, 340, 280, 345, 680, 295)
    private val FULL_RIGHT = points(742, 242, 901, 276, 564, 275, 834, 337, 366, 335, 922, 455, 344, 300, 740, 337)
    private val LEFT_FIX = Point(36.0, 506.0)
    private val RIGHT_FIX = Point(988.0, 506.0)

    /**
     * The lines as the vendor draws them: each side a curve from its fixed point at the bottom through its control to
     * its far end, three bars across (far green, middle yellow, near red); drawn moved down by [CameraSettings.linePos].
     * Bending lines follow [eps] in the vendor's 23 steps, each point sliding towards its full-lock place.
     */
    fun guides(guidelines: Guidelines, eps: Int): Guides {
        val step = if (guidelines != Guidelines.STEERING || kotlin.math.abs(eps) <= EPS_STEP) 0
            else minOf(kotlin.math.abs(eps), EPS_MAX) / EPS_STEP
        val p = if (step == 0) STRAIGHT else {
            val left = eps > 0
            val lock = if (left) FULL_LEFT else FULL_RIGHT
            STRAIGHT.indices.map { i ->
                var dx = (STRAIGHT[i].x - lock[i].x) / TURN_STEPS
                val dy = (STRAIGHT[i].y - lock[i].y) / TURN_STEPS
                // the vendor's own correction of the inner middle point
                if (left && i == 2) dx -= (step - TURN_STEPS / 2.0) / (TURN_STEPS / 2.0)
                if (!left && i == 3) dx -= (TURN_STEPS - step).toDouble() / TURN_STEPS * 2.3
                Point(STRAIGHT[i].x - dx * step, STRAIGHT[i].y - dy * step)
            }
        }
        return Guides(
            leftFix = LEFT_FIX, leftControl = p[6], leftFar = p[0], leftMiddle = p[2], leftNear = p[4],
            rightFix = RIGHT_FIX, rightControl = p[7], rightFar = p[1], rightMiddle = p[3], rightNear = p[5],
        )
    }

    private fun points(vararg xy: Int) = xy.toList().chunked(2) { (x, y) -> Point(x.toDouble(), y.toDouble()) }

    fun clamp(s: CameraSettings) = s.copy(linePos = s.linePos.coerceIn(LINE_POS_MIN, LINE_POS_MAX))

    /** The calls that bring the vendor from [a] to [b]; [a] null writes every value, as at each start of our app. */
    fun writes(a: CameraSettings?, b: CameraSettings): List<DspWrite> = buildList {
        fun w(call: String, arg: Int) = add(DspWrite(call, listOf(arg)))
        fun bit(on: Boolean) = if (on) 1 else 0
        if (a?.mirror != b.mirror) w("FtSet.SetCamMir", bit(b.mirror))
        if (a?.guidelines != b.guidelines) {
            w("FtSet.SetCamLine", bit(b.guidelines == Guidelines.FIXED))
            w("FtSet.SetCamTrack", bit(b.guidelines == Guidelines.STEERING))
        }
        if (a?.linePos != b.linePos) w("FtSet.SetLinePos", b.linePos)
        if (isNotEmpty()) w("FtSet.Save", 1)
        if (a?.dip != b.dip) w("StSet.SetParkMute", b.dip.code)
    }
}
