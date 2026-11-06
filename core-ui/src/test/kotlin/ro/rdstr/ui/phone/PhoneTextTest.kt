package ro.rdstr.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CarEvent.Call.State
import ro.rdstr.car.Contact
import ro.rdstr.car.PhoneNumber
import java.util.TimeZone

class PhoneTextTest {
    private val zone = TimeZone.getTimeZone("Europe/Bucharest")
    private val now = 1793440800000L // Saturday 31 October 2026, 12:00 in Bucharest
    private val hour = 3_600_000L

    @Test fun whenLabels() {
        assertEquals("11:35", PhoneText.whenLabel(now - 25 * 60_000, now, zone))
        assertEquals("Yesterday", PhoneText.whenLabel(now - 27 * hour, now, zone))
        assertEquals("Thursday", PhoneText.whenLabel(now - 48 * hour, now, zone))
        assertEquals("22 Oct", PhoneText.whenLabel(now - 9 * 24 * hour, now, zone))
    }

    @Test fun callLines() {
        val c = CallInfo(State.ACTIVE, "0721", since = now - 83_000, micMuted = true, inCar = false)
        assertEquals("1:23 · Muted · On the phone", c.statusLine(now))
        assertEquals("1:23 · Muted", c.barLine(now))
        assertEquals("Incoming call", c.copy(state = State.RINGING).statusLine(now).substringBefore(" ·"))
        assertEquals("On Hold", c.copy(held = true).status(now))
        assertEquals("Conference", c.copy(conference = true).title { it })
    }

    @Test fun trackKeepsTheClockAndTheMicrophone() {
        val book = listOf(Contact("Ana Petrescu", listOf(PhoneNumber("+40 721 000 101", "mobile"))))
        val t = CallTrack()
        val ringing = t.update(CarEvent.Call(State.RINGING, "+40721000101"), true, book, now)!!
        assertNull(ringing.since)
        assertEquals("Ana Petrescu", ringing.name)
        assertEquals("mobile", ringing.label)
        assertEquals(now + 5000, t.update(CarEvent.Call(State.ACTIVE, "+40721000101"), true, book, now + 5000)!!.since)
        t.sent(CarCommand.Phone.Mute(true))
        val later = t.update(CarEvent.Call(State.ACTIVE, "+40721000101"), true, book, now + 9000)!!
        assertEquals(now + 5000, later.since)
        assertEquals(true, later.micMuted)
        assertNull(t.update(CarEvent.Call(State.IDLE), true, book, now))
        assertEquals(false, t.update(CarEvent.Call(State.ACTIVE, "1"), true, book, now)!!.micMuted)
    }

    @Test fun castStatus() {
        assertEquals("Hidden from Phones", PhoneText.castStatus(false, CarEvent.PhoneCast.State.VISIBLE, "", "x").first)
        assertEquals("Connecting to the phone…", PhoneText.castStatus(true, CarEvent.PhoneCast.State.CONNECTING, "", "x").first)
        assertEquals("On the phone, open Smart View or Screen Mirroring and choose roadsterOS.", PhoneText.castStatus(true, CarEvent.PhoneCast.State.VISIBLE, "", "roadsterOS").second)
    }
}
