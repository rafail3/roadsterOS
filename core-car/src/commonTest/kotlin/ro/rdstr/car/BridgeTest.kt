package ro.rdstr.car

import kotlinx.serialization.json.Json

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
    fun `bluetooth connect commands through json`() {
        assertEquals(CarCommand.Bluetooth.Connect("Phone"), Json.decodeFromString(CarCommand.serializer(), """{"type":"Bt.Connect","device":"Phone"}"""))
        assertEquals(CarCommand.Bluetooth.Disconnect, Json.decodeFromString(CarCommand.serializer(), """{"type":"Bt.Disconnect"}"""))
        assertEquals(CarCommand.Bluetooth.Scan, Json.decodeFromString(CarCommand.serializer(), """{"type":"Bt.Scan"}"""))
        assertEquals(CarCommand.Bluetooth.Pair("Pixel"), Json.decodeFromString(CarCommand.serializer(), """{"type":"Bt.Pair","device":"Pixel"}"""))
        assertEquals(CarCommand.Bluetooth.ConfirmPairing(true), Json.decodeFromString(CarCommand.serializer(), """{"type":"Bt.ConfirmPairing","accept":true}"""))
    }

    @Test
    fun `echo through json`() {
        assertEquals("""{"type":"Playback","playing":true}""", Bridge.echo("""{"type":"Media.Play"}"""))
        assertEquals("null", Bridge.echo("""{"type":"Audio.Volume","delta":1}"""))
    }

    @Test
    fun `cover colours through json`() {
        val pixels = List(48 * 48) { 0xFF808080.toInt() }.joinToString(",", "[", "]")
        val answer = Bridge.coverColors(pixels, 48)
        assertTrue(answer.startsWith("""{"top":"#"""), answer)
        assertTrue(answer.contains(""""bottom":"#"""), answer)
    }

    @Test
    fun `shell through json`() {
        val s = Bridge.shell(Bridge.shellInitial(), """{"type":"CycleTheme"}""")
        assertTrue(s.contains(""""theme":"AUTO""""), s)
        assertEquals("DARK", Bridge.effectiveTheme(s, 22))
        assertEquals("LIGHT", Bridge.effectiveTheme(s, 12))
        assertEquals("DARK", Bridge.effectiveTheme(Bridge.shellInitial(), 12))
    }
}
