package ro.rdstr.carsim

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.JournalLine

@OptIn(ExperimentalCoroutinesApi::class)
class SimCarBusTest {

    @Test
    fun `replay emits events at the recorded times`() = runTest {
        val bus = SimCarBus(this)
        val received = mutableListOf<CarEvent>()
        val collector = launch { bus.events.collect { received += it } }
        bus.replay(
            listOf(
                JournalLine(0, CarEvent.Power(acc = true)),
                JournalLine(1000, CarEvent.BtConnection(true, "Phone")),
                JournalLine(3000, CarEvent.ReverseGear(true)),
            ),
        )
        advanceTimeBy(1)
        assertEquals(listOf<CarEvent>(CarEvent.Power(acc = true)), received)
        advanceTimeBy(1000)
        assertEquals(2, received.size)
        advanceTimeBy(2000)
        assertEquals(CarEvent.ReverseGear(true), received.last())
        collector.cancel()
    }

    @Test
    fun `commands echo the events a real car would send`() = runTest {
        val bus = SimCarBus(this)
        val received = mutableListOf<CarEvent>()
        val collector = launch { bus.events.collect { received += it } }
        runCurrent()
        bus.send(CarCommand.Media.Play)
        bus.send(CarCommand.Media.Pause)
        bus.send(CarCommand.Phone.Answer)
        bus.send(CarCommand.Phone.HangUp)
        bus.send(CarCommand.Audio.Volume(1))
        advanceTimeBy(1)
        assertEquals(
            listOf(
                CarEvent.Playback(true),
                CarEvent.Playback(false),
                CarEvent.Call(CarEvent.Call.State.ACTIVE),
                CarEvent.Call(CarEvent.Call.State.IDLE),
            ),
            received,
        )
        collector.cancel()
    }
}
