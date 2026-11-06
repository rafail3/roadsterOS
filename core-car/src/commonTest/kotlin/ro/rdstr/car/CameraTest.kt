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
    fun `the camera opens from its icon, Back or Home close it, it is not kept across starts`() {
        val open = Shell.reduce(ShellState(drawer = true), ShellAction.OpenCamera)
        assertEquals(true, open.cameraOpen)
        assertEquals(false, open.drawer)
        assertEquals(false, Shell.reduce(open, ShellAction.Back).cameraOpen)
        assertEquals(false, Shell.reduce(open, ShellAction.Home).cameraOpen)
        assertEquals(false, Shell.decodePreferences(Shell.encodePreferences(open)).cameraOpen)
    }

    @Test
    fun `the lines stay straight unless they follow the steering past one step, then slide towards full lock`() {
        val straight = Camera.guides(Guidelines.FIXED, 900)
        assertEquals(Point(408.0, 240.0), straight.leftFar)
        assertEquals(straight, Camera.guides(Guidelines.STEERING, Camera.EPS_STEP))
        val lock = Camera.guides(Guidelines.STEERING, -5000)
        assertEquals(Point(901.0, 276.0), lock.rightFar)
        assertEquals(Point(36.0, 506.0), lock.leftFix)
        val left = Camera.guides(Guidelines.STEERING, Camera.EPS_MAX)
        assertEquals(Point(123.0, 276.0).x, left.leftFar.x, 0.001)
    }

    @Test
    fun `the lines stay within the vendor's range and are kept across starts`() {
        val high = Shell.reduce(ShellState(), ShellAction.SetCamera(CameraSettings(linePos = -500, mirror = true)))
        assertEquals(Camera.LINE_POS_MIN, high.camera.linePos)
        assertEquals(high.camera, Shell.decodePreferences(Shell.encodePreferences(high)).camera)
    }
}
