package ro.rdstr.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.rdstr.car.Action
import ro.rdstr.car.CarEvent
import ro.rdstr.car.RulesState

class UiStateTest {
    @Test
    fun `camera and call actions toggle flags`() {
        val call = CarEvent.Call(CarEvent.Call.State.RINGING, "+40")
        var ui = UiState()
        ui = ui.reduce(RulesState(), listOf(Action.ShowCamera, Action.ShowCall(call)), CarEvent.ReverseGear(true))
        assertTrue(ui.camera)
        assertEquals(call, ui.call)
        ui = ui.reduce(RulesState(), listOf(Action.HideCamera, Action.HideCall), CarEvent.ReverseGear(false))
        assertEquals(false, ui.camera)
        assertNull(ui.call)
        assertEquals("ReverseGear(on=false)", ui.lastEvent)
    }

    @Test
    fun `a caller is named from the phone book and the phone's details are kept`() {
        val book = listOf(ro.rdstr.car.Contact("Ana", listOf(ro.rdstr.car.PhoneNumber("0721 111 222"))))
        val call = CarEvent.Call(CarEvent.Call.State.RINGING, "+40721111222")
        var ui = UiState(contacts = book).reduce(RulesState(), listOf(Action.ShowCall(call)), call)
        assertEquals("Ana", ui.call?.name)
        ui = ui.reduce(RulesState(), emptyList(), CarEvent.CallAudio(false))
        assertEquals(false, ui.callInCar)
        ui = ui.reduce(RulesState(), emptyList(), CarEvent.PhoneStatus(battery = 3))
        assertEquals(3, ui.phoneStatus?.battery)
        ui = ui.reduce(RulesState(), listOf(Action.HideCall), CarEvent.Call(CarEvent.Call.State.IDLE))
        assertTrue(ui.callInCar)
    }

    @Test
    fun `the phone's name and network come and go with its link`() {
        var ui = UiState().reduce(RulesState(), emptyList(), CarEvent.HfpConnection(true, "Pixel 8"))
        assertEquals("Pixel 8", ui.phoneName)
        ui = ui.reduce(RulesState(), emptyList(), CarEvent.PhoneStatus(operator = "Net", roaming = true))
        assertEquals("Net", ui.phoneStatus?.operator)
        ui = ui.reduce(RulesState(), emptyList(), CarEvent.HfpConnection(false))
        assertEquals("", ui.phoneName)
        assertNull(ui.phoneStatus)
    }

    @Test
    fun `a second call is named too, and messages and the assistant are kept`() {
        val book = listOf(ro.rdstr.car.Contact("Ana", listOf(ro.rdstr.car.PhoneNumber("0721 111 222"))))
        val call = CarEvent.Call(CarEvent.Call.State.ACTIVE, "0799", other = CarEvent.Call.Other("+40721111222", waiting = true))
        var ui = UiState(contacts = book).reduce(RulesState(), listOf(Action.ShowCall(call)), call)
        assertEquals("Ana", ui.call?.other?.name)
        ui = ui.reduce(RulesState(), emptyList(), CarEvent.VoiceAssistant(true))
        assertTrue(ui.voiceAssistant)
        ui = ui.reduce(RulesState(), emptyList(), CarEvent.Message("m", "0721", "hi"))
        ui = ui.reduce(RulesState(), emptyList(), CarEvent.Message("s", "0721", "OK", sent = true))
        assertEquals(2, ui.messages.size)
        assertEquals(setOf("s"), ui.read)
    }

    @Test
    fun `night action sets night`() {
        val ui = UiState().reduce(RulesState(night = true), listOf(Action.Night(true)), CarEvent.Illumination(true))
        assertTrue(ui.night)
    }
}
