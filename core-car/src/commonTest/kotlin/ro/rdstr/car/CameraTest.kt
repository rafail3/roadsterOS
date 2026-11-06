package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraTest {
    private fun w(call: String, arg: Int) = DspWrite(call, listOf(arg))

    @Test
    fun `a start writes every value, factory ones saved`() {
        assertEquals(
            listOf(
                w("FtSet.SetCamMir", 0), w("FtSet.SetCamLine", 1), w("FtSet.SetCamTrack", 0), w("FtSet.SetLinePos", 0),
                w("FtSet.Save", 1), w("StSet.SetParkMute", 2),
            ),
            Camera.writes(null, CameraSettings()),
        )
    }

    @Test
    fun `bending lines switch the fixed ones off, a changed dip alone saves nothing`() {
        val s = CameraSettings()
        assertEquals(
            listOf(w("FtSet.SetCamLine", 0), w("FtSet.SetCamTrack", 1), w("FtSet.Save", 1)),
            Camera.writes(s, s.copy(guidelines = Guidelines.STEERING)),
        )
        assertEquals(listOf(w("StSet.SetParkMute", 3)), Camera.writes(s, s.copy(dip = ReverseDip.STRONG)))
        assertEquals(emptyList(), Camera.writes(s, s))
    }

    @Test
    fun `the lines stay within the vendor's range and are kept across starts`() {
        val high = Shell.reduce(ShellState(), ShellAction.SetCamera(CameraSettings(linePos = -500, mirror = true)))
        assertEquals(Camera.LINE_POS_MIN, high.camera.linePos)
        assertEquals(high.camera, Shell.decodePreferences(Shell.encodePreferences(high)).camera)
    }
}
