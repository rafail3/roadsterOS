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
    fun `night action sets night`() {
        val ui = UiState().reduce(RulesState(night = true), listOf(Action.Night(true)), CarEvent.Illumination(true))
        assertTrue(ui.night)
    }
}
