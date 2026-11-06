package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals

class DebugJsonTest {
    @Test
    fun `actions, events and commands read the emulator's json`() {
        assertEquals(ShellAction.OpenApp(App.SETTINGS), DebugJson.shellAction("""{"type":"OpenApp","app":"SETTINGS"}"""))
        assertEquals(ShellAction.OpenDrawer, DebugJson.shellAction("""{"type":"OpenDrawer"}"""))
        assertEquals(CarCommand.Bluetooth.Connect("Pixel"), DebugJson.command("""{"type":"Bt.Connect","device":"Pixel"}"""))
    }
}
