package ro.rdstr.car

import kotlinx.serialization.Serializable

/** The lines over the reverse camera's picture: none, fixed ones, or ones that bend as the steering wheel turns. */
enum class Guidelines { OFF, FIXED, STEERING }

/** How much the music and the radio are lowered while reverse gear is in; [code] is the vendor's level. */
enum class ReverseDip(val code: Int) { NONE(0), WEAK(1), MEDIUM(2), STRONG(3) }

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
