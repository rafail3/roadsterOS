package ro.rdstr.car

import ro.rdstr.car.CarEvent.Call.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneTest {
    private val ana = Contact("Ana Petrescu", listOf(PhoneNumber("+40 721 111 222", "mobile")))
    private val bogdan = Contact("bogdan Ionescu", listOf(PhoneNumber("0722 333 444", "mobile"), PhoneNumber("021 555 6666", "work")))
    private val stefan = Contact("Ștefan Matei", listOf(PhoneNumber("0733-777-888")))
    private val service = Contact("112 Urgențe", listOf(PhoneNumber("112")))
    private val book = listOf(stefan, bogdan, service, ana)

    @Test
    fun `the gate opens only on a phone connected for calls`() {
        assertEquals(PhoneGate.Screen.BT_OFF, PhoneGate.screen(btOn = false, hfpConnected = false))
        assertEquals(PhoneGate.Screen.CONNECT, PhoneGate.screen(btOn = true, hfpConnected = false))
        assertEquals(PhoneGate.Screen.READY, PhoneGate.screen(btOn = true, hfpConnected = true))
        assertTrue(PhoneGate.callCapable(PhoneGate.MAJOR_PHONE))
        assertFalse(PhoneGate.callCapable(0x0100))
    }

    @Test
    fun `numbers match across spaces, dashes and the country prefix`() {
        assertTrue(PhoneBook.same("+40 721 111 222", "0721111222"))
        assertTrue(PhoneBook.same("0733-777-888", "+40733777888"))
        assertTrue(PhoneBook.same("112", "112"))
        assertFalse(PhoneBook.same("112", "0721111112"))
        assertFalse(PhoneBook.same("", ""))
    }

    @Test
    fun `the caller is named from the phone book`() {
        assertEquals(PhoneBook.Match("bogdan Ionescu", "021 555 6666", "work"), PhoneBook.find(book, "+40215556666"))
        assertNull(PhoneBook.find(book, "+40799000000"))
    }

    @Test
    fun `contacts are sorted by letter with accents folded and digits last`() {
        val sections = PhoneBook.sections(book)
        assertEquals(listOf("A", "B", "S", "#"), sections.map { it.letter })
        assertEquals(listOf(stefan), sections[2].contacts)
    }

    @Test
    fun `typed digits suggest a contact from three digits on`() {
        assertNull(PhoneBook.suggest(book, "07"))
        assertEquals("Ștefan Matei", PhoneBook.suggest(book, "0733")?.name)
        assertEquals("Ana Petrescu", PhoneBook.suggest(book, "0721111222")?.name)
    }

    @Test
    fun `recents merge repeated calls of one kind and take names from the book`() {
        val calls = listOf(
            CallRecord("0721111222", type = CallType.MISSED, atMs = 50),
            CallRecord("+40721111222", type = CallType.MISSED, atMs = 40),
            CallRecord("0721111222", type = CallType.OUTGOING, atMs = 30),
            CallRecord("0799000000", name = "Courier", type = CallType.INCOMING, atMs = 20),
            CallRecord("0721111222", type = CallType.MISSED, atMs = 10),
        )
        val rows = PhoneBook.recents(calls.shuffled(), book)
        assertEquals(listOf(2, 1, 1, 1), rows.map { it.count })
        assertEquals(listOf("Ana Petrescu", "Ana Petrescu", "Courier", "Ana Petrescu"), rows.map { it.name })
        assertEquals("mobile", rows[0].label)
        assertEquals(50L, rows[0].atMs)
    }

    @Test
    fun `initials and call durations`() {
        assertEquals("AP", PhoneBook.initials("Ana Petrescu"))
        assertEquals("ȘM", PhoneBook.initials("ștefan matei"))
        assertEquals("B", PhoneBook.initials("Bogdan"))
        assertEquals("", PhoneBook.initials("+40 721"))
        assertEquals("0:07", PhoneBook.duration(7_400))
        assertEquals("12:34", PhoneBook.duration(754_000))
        assertEquals("1:02:03", PhoneBook.duration(3_723_000))
    }

    @Test
    fun `hfp call states fold into one call state, an incoming call first`() {
        assertEquals(State.IDLE, Hfp.state(emptyList()))
        assertEquals(State.IDLE, Hfp.state(listOf(Hfp.TERMINATED)))
        assertEquals(State.OUTGOING, Hfp.state(listOf(Hfp.DIALING)))
        assertEquals(State.OUTGOING, Hfp.state(listOf(Hfp.ALERTING)))
        assertEquals(State.RINGING, Hfp.state(listOf(Hfp.INCOMING)))
        assertEquals(State.ACTIVE, Hfp.state(listOf(Hfp.ACTIVE, Hfp.WAITING)))
        assertEquals(State.ACTIVE, Hfp.state(listOf(Hfp.HELD)))
        assertEquals(State.RINGING, Hfp.state(listOf(Hfp.TERMINATED, Hfp.INCOMING)))
    }

    @Test
    fun `an outgoing call pauses the music once and idle resumes it`() {
        val bt = RulesState(source = AudioSource.BT, btConnected = true, playing = true)
        val dial = CarEvent.Call(State.OUTGOING, "0721111222")
        val (out, a1) = Rules.apply(bt, dial)
        assertEquals(State.OUTGOING, out.call)
        assertEquals(listOf(Action.ShowCall(dial), Action.Send(CarCommand.Media.Pause)), a1)
        // the phone reports the pause before the other side answers: the music must still come back afterwards
        val paused = Rules.apply(out, CarEvent.Playback(false)).first
        val (active, _) = Rules.apply(paused, CarEvent.Call(State.ACTIVE))
        assertTrue(active.resumeAfterCall)
        assertEquals(listOf(Action.HideCall, Action.Send(CarCommand.Media.Play)), Rules.apply(active, CarEvent.Call(State.IDLE)).second)
    }

    @Test
    fun `the phone key ends an outgoing call and phone details change nothing`() {
        val dialing = RulesState(call = State.OUTGOING)
        assertEquals(listOf(Action.Send(CarCommand.Phone.HangUp)), Rules.apply(dialing, CarEvent.SteeringKey(CarEvent.SteeringKey.Key.PHONE)).second)
        assertEquals(dialing to emptyList(), Rules.apply(dialing, CarEvent.PhoneStatus(battery = 4, signal = 3, operator = "Net")))
        assertEquals(dialing to emptyList(), Rules.apply(dialing, CarEvent.CallAudio(inCar = false)))
    }

    @Test
    fun `a phone that drops mid-call ends the call`() {
        val talking = RulesState(source = AudioSource.BT, playing = false, call = State.ACTIVE, resumeAfterCall = true, hfpConnected = true)
        val (s, a) = Rules.apply(talking, CarEvent.HfpConnection(false))
        assertEquals(State.IDLE, s.call)
        assertFalse(s.hfpConnected)
        assertEquals(listOf(Action.HideCall, Action.Send(CarCommand.Media.Play)), a)
        assertTrue(Rules.apply(RulesState(), CarEvent.HfpConnection(true)).first.hfpConnected)
    }

    @Test
    fun `the car answers a dial and an audio switch`() {
        assertEquals(CarEvent.Call(State.OUTGOING, "112"), CarEcho.respond(CarCommand.Phone.Dial("112")))
        assertEquals(CarEvent.CallAudio(false), CarEcho.respond(CarCommand.Phone.AudioInCar(false)))
    }
}
