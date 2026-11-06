package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals

class StartupTest {
    @Test
    fun `the radio comes back at once, paused if it was`() {
        val (s, commands) = Startup.resume(Startup.kept(RulesState(source = AudioSource.RADIO, radioOn = true)))
        assertEquals(AudioSource.RADIO, s.source)
        assertEquals(listOf<CarCommand>(CarCommand.Audio.Source(AudioSource.RADIO)), commands)
        val (paused, pause) = Startup.resume(RulesState(source = AudioSource.RADIO, radioOn = false))
        assertEquals(false, paused.radioOn)
        assertEquals(listOf(CarCommand.Audio.Source(AudioSource.RADIO), CarCommand.Radio.Pause), pause)
    }

    @Test
    fun `bluetooth waits for the phone, then plays again if it played`() {
        val (s, commands) = Startup.resume(RulesState(source = AudioSource.BT, playing = true, btConnected = true))
        assertEquals(emptyList(), commands)
        assertEquals(
            listOf(Action.Send(CarCommand.Audio.Source(AudioSource.BT)), Action.Send(CarCommand.Media.Play)),
            Rules.apply(s, CarEvent.BtConnection(true, "Phone")).second,
        )
        val (stopped, _) = Startup.resume(RulesState(source = AudioSource.BT, playing = false))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Source(AudioSource.BT))), Rules.apply(stopped, CarEvent.BtConnection(true)).second)
    }

    @Test
    fun `a phone that drops while playing plays again when it comes back`() {
        val playing = RulesState(source = AudioSource.BT, playing = true, btConnected = true)
        val (gone, _) = Rules.apply(playing, CarEvent.BtConnection(false))
        val (back, actions) = Rules.apply(gone, CarEvent.BtConnection(true))
        assertEquals(Action.Send(CarCommand.Media.Play), actions.last())
        assertEquals(false, back.playOnConnect)
    }

    @Test
    fun `what is kept reads back, a damaged text gives nothing`() {
        val s = RulesState(source = AudioSource.RADIO, radioOn = false, volume = 25, night = true)
        assertEquals(Startup.kept(s), Startup.decodeKept(Startup.encodeKept(s)))
        assertEquals(null, Startup.decodeKept("{oops"))
        assertEquals(null, Startup.decodeKept(null))
    }

    @Test
    fun `the last phone is asked first, a forgotten one not at all`() {
        assertEquals(listOf("B", "A", "C"), Startup.phonesToTry(listOf("A", "B", "C"), "B"))
        assertEquals(listOf("A", "C"), Startup.phonesToTry(listOf("A", "C"), "Gone"))
        assertEquals(listOf("A"), Startup.phonesToTry(listOf("A"), null))
    }
}
