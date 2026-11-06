package ro.rdstr.car

import ro.rdstr.car.CarEvent.Call.State
import ro.rdstr.car.CarEvent.PhoneCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CastTest {
    private val music = RulesState(source = AudioSource.BT, playing = true, btConnected = true)
    private val live = CarEvent.PhoneCast(PhoneCast.State.LIVE, "Galaxy A55")
    private val off = CarEvent.PhoneCast(PhoneCast.State.OFF)

    @Test
    fun `the phone's screen stops the music and plays it again when it goes`() {
        val (on, pause) = Rules.apply(music, live)
        assertEquals(listOf(Action.Send(CarCommand.Media.Pause)), pause)
        assertEquals(listOf(Action.Send(CarCommand.Media.Play)), Rules.apply(on, off).second)
    }

    @Test
    fun `the radio is paused and comes back, nothing comes back that was not playing`() {
        val radio = RulesState(source = AudioSource.RADIO, radioOn = true)
        val (on, pause) = Rules.apply(radio, live)
        assertEquals(listOf(Action.Send(CarCommand.Radio.Pause)), pause)
        assertEquals(listOf(Action.Send(CarCommand.Radio.Play)), Rules.apply(on, off).second)
        val (quiet, none) = Rules.apply(music.copy(playing = false), live)
        assertEquals(emptyList(), none)
        assertEquals(emptyList(), Rules.apply(quiet, off).second)
    }

    @Test
    fun `a call during the phone's screen does not bring the music back over it`() {
        val (casting, _) = Rules.apply(music, live)
        val (ringing, _) = Rules.apply(casting, CarEvent.Call(State.RINGING, "0721"))
        val (ended, afterCall) = Rules.apply(ringing, CarEvent.Call(State.IDLE))
        assertTrue(afterCall.none { it is Action.Send })
        assertEquals(listOf(Action.Send(CarCommand.Media.Play)), Rules.apply(ended, off).second)
    }

    @Test
    fun `the phone's screen ending during a call leaves the music to the call's end`() {
        val (casting, _) = Rules.apply(music, live)
        val (talking, _) = Rules.apply(casting, CarEvent.Call(State.ACTIVE, "0721"))
        val (stopped, none) = Rules.apply(talking, off)
        assertEquals(emptyList(), none)
        assertTrue(Action.Send(CarCommand.Media.Play) in Rules.apply(stopped, CarEvent.Call(State.IDLE)).second)
    }

    @Test
    fun `the phone screen app opens when the picture plays and leaves to home when it ends`() {
        val open = Shell.reduce(ShellState(), ShellAction.CastChanged(PhoneCast.State.LIVE))
        assertEquals(App.PHONE_SCREEN, open.app)
        assertEquals(App.HOME, Shell.reduce(open, ShellAction.CastChanged(PhoneCast.State.OFF)).app)
        // the phone stopped sharing, the unit stays visible for the next time
        assertEquals(App.HOME, Shell.reduce(open, ShellAction.CastChanged(PhoneCast.State.VISIBLE)).app)
        val elsewhere = Shell.reduce(open, ShellAction.OpenApp(App.MAPS))
        assertEquals(App.MAPS, Shell.reduce(elsewhere, ShellAction.CastChanged(PhoneCast.State.OFF)).app)
        // waiting for a phone keeps the app open
        val waiting = Shell.reduce(ShellState(), ShellAction.OpenApp(App.PHONE_SCREEN))
        assertEquals(App.PHONE_SCREEN, Shell.reduce(waiting, ShellAction.CastChanged(PhoneCast.State.CONNECTING)).app)
        val hidden = Shell.reduce(ShellState(), ShellAction.SetCastVisible(false))
        assertEquals(false, Shell.decodePreferences(Shell.encodePreferences(hidden)).castVisible)
    }
}
