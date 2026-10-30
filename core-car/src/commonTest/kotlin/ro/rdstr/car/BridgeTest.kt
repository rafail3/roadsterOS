package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarEchoTest {
    @Test
    fun `play and pause echo playback`() {
        assertEquals(CarEvent.Playback(true), CarEcho.respond(CarCommand.Media.Play))
        assertEquals(CarEvent.Playback(false), CarEcho.respond(CarCommand.Media.Pause))
    }

    @Test
    fun `answer and hang up echo call states`() {
        assertEquals(CarEvent.Call(CarEvent.Call.State.ACTIVE), CarEcho.respond(CarCommand.Phone.Answer))
        assertEquals(CarEvent.Call(CarEvent.Call.State.IDLE), CarEcho.respond(CarCommand.Phone.HangUp))
    }

    @Test
    fun `other commands have no echo`() {
        assertNull(CarEcho.respond(CarCommand.Audio.Volume(1)))
    }
}

class BridgeTest {
    @Test
    fun `reverse gear through json shows the camera`() {
        val step = Bridge.apply(Bridge.initial(), """{"type":"ReverseGear","on":true}""")
        assertTrue(step.contains(""""camera":true"""), step)
        assertTrue(step.contains(""""type":"ShowCamera""""), step)
    }

    @Test
    fun `source command through json updates the state`() {
        val s = Bridge.command(Bridge.initial(), """{"type":"Audio.Source","source":"RADIO"}""")
        assertTrue(s.contains(""""source":"RADIO""""), s)
    }

    @Test
    fun `echo through json`() {
        assertEquals("""{"type":"Playback","playing":true}""", Bridge.echo("""{"type":"Media.Play"}"""))
        assertEquals("null", Bridge.echo("""{"type":"Audio.Volume","delta":1}"""))
    }

    @Test
    fun `shell through json`() {
        val s = Bridge.shell(Bridge.shellInitial(), """{"type":"CycleTheme"}""")
        assertTrue(s.contains(""""theme":"LIGHT""""), s)
        assertEquals("DARK", Bridge.effectiveTheme(Bridge.shellInitial(), 22))
        assertEquals("LIGHT", Bridge.effectiveTheme(Bridge.shellInitial(), 12))
    }
}
