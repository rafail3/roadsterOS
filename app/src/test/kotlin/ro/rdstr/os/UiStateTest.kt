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
    fun `night action sets night`() {
        val ui = UiState().reduce(RulesState(night = true), listOf(Action.Night(true)), CarEvent.Illumination(true))
        assertTrue(ui.night)
    }
}
