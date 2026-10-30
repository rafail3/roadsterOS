package ro.rdstr.car

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalTest {
    private val samples: List<CarEvent> = listOf(
        CarEvent.ReverseGear(on = true),
        CarEvent.SteeringKey(CarEvent.SteeringKey.Key.VOL_UP),
        CarEvent.Illumination(on = false),
        CarEvent.Power(acc = true),
        CarEvent.BtConnection(connected = true, device = "Phone"),
        CarEvent.Track(title = "Song", artist = "Artist", album = "Album", durationMs = 180_000),
        CarEvent.Playback(playing = true, positionMs = 12_000),
        CarEvent.Call(CarEvent.Call.State.RINGING, number = "+40000000000", name = "Test"),
        CarEvent.Radio(khz = 101_900, band = CarEvent.Radio.Band.FM, rds = "RADIO", signal = 7),
        CarEvent.CarDisplay(mode = "clock"),
    )

    @Test
    fun `every event survives a journal round trip`() {
        samples.forEachIndexed { i, event ->
            val line = JournalLine(t = i * 1000L, event = event)
            assertEquals(line, Journal.decode(Journal.encode(line)))
        }
    }

    @Test
    fun `journal line has the documented shape`() {
        val text = Journal.encode(JournalLine(1200, CarEvent.ReverseGear(on = true)))
        assertEquals("""{"t":1200,"event":{"type":"ReverseGear","on":true}}""", text)
    }

    @Test
    fun `event alone round trips`() {
        val event = CarEvent.SteeringKey(CarEvent.SteeringKey.Key.NEXT)
        assertEquals(event, Journal.decodeEvent(Journal.encodeEvent(event)))
        assertEquals("""{"type":"SteeringKey","key":"NEXT"}""", Journal.encodeEvent(event))
    }

    @Test
    fun `unknown fields are ignored`() {
        val line = Journal.decode("""{"t":5,"event":{"type":"Power","acc":false,"extra":1}}""")
        assertEquals(JournalLine(5, CarEvent.Power(acc = false)), line)
    }
}
