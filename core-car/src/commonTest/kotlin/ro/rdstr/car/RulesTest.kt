package ro.rdstr.car

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import ro.rdstr.car.CarEvent.Call.State
import ro.rdstr.car.CarEvent.PanelKey.Key as PanelKey
import ro.rdstr.car.CarEvent.SteeringKey.Key

class RulesTest {
    private val bt = RulesState(source = AudioSource.BT, playing = true, btConnected = true)
    private val radio = RulesState(source = AudioSource.RADIO, playing = true)

    @Test
    fun `reverse gear shows and hides the camera`() {
        val (on, a1) = Rules.apply(RulesState(), CarEvent.ReverseGear(true))
        assertTrue(on.camera)
        assertEquals(listOf(Action.ShowCamera), a1)
        val (off, a2) = Rules.apply(on, CarEvent.ReverseGear(false))
        assertFalse(off.camera)
        assertEquals(listOf(Action.HideCamera), a2)
    }

    @Test
    fun `ringing call shows the overlay`() {
        val call = CarEvent.Call(State.RINGING, "+40", "A")
        val (s, a) = Rules.apply(bt, call)
        assertEquals(State.RINGING, s.call)
        assertEquals(listOf(Action.ShowCall(call)), a)
    }

    @Test
    fun `active call pauses bt and idle resumes it`() {
        val (active, a1) = Rules.apply(bt, CarEvent.Call(State.ACTIVE))
        assertEquals(listOf(Action.Send(CarCommand.Media.Pause)), a1)
        assertTrue(active.resumeAfterCall)
        val (idle, a2) = Rules.apply(active, CarEvent.Call(State.IDLE))
        assertEquals(listOf(Action.HideCall, Action.Send(CarCommand.Media.Play)), a2)
        assertFalse(idle.resumeAfterCall)
    }

    @Test
    fun `active call mutes radio and idle unmutes it`() {
        val (active, a1) = Rules.apply(radio, CarEvent.Call(State.ACTIVE))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Mute(true))), a1)
        val (_, a2) = Rules.apply(active, CarEvent.Call(State.IDLE))
        assertEquals(listOf(Action.HideCall, Action.Send(CarCommand.Audio.Mute(false))), a2)
    }

    @Test
    fun `idle call does not resume a paused source`() {
        val paused = bt.copy(playing = false)
        val (active, _) = Rules.apply(paused, CarEvent.Call(State.ACTIVE))
        val (_, a) = Rules.apply(active, CarEvent.Call(State.IDLE))
        assertEquals(listOf(Action.HideCall), a)
    }

    @Test
    fun `volume keys send relative volume`() {
        assertEquals(listOf(Action.Send(CarCommand.Audio.Volume(1)), Action.ShowVolume), Rules.apply(bt, CarEvent.SteeringKey(Key.VOL_UP)).second)
        assertEquals(listOf(Action.Send(CarCommand.Audio.Volume(-1)), Action.ShowVolume), Rules.apply(bt, CarEvent.SteeringKey(Key.VOL_DOWN)).second)
    }

    @Test
    fun `panel volume keys send relative volume like the steering wheel`() {
        assertEquals(listOf(Action.Send(CarCommand.Audio.Volume(1)), Action.ShowVolume), Rules.apply(bt, CarEvent.PanelKey(PanelKey.VOL_UP)).second)
        assertEquals(listOf(Action.Send(CarCommand.Audio.Volume(-1)), Action.ShowVolume), Rules.apply(bt, CarEvent.PanelKey(PanelKey.VOL_DOWN)).second)
        assertEquals(Rules.DEFAULT_VOLUME + 1, Rules.apply(bt, CarEvent.PanelKey(PanelKey.VOL_UP)).first.volume)
        assertEquals(Rules.DEFAULT_VOLUME - 1, Rules.apply(bt, CarEvent.SteeringKey(Key.VOL_DOWN)).first.volume)
    }

    @Test
    fun `panel home and back go to the shell`() {
        assertEquals(listOf(Action.Home), Rules.apply(bt, CarEvent.PanelKey(PanelKey.HOME)).second)
        assertEquals(listOf(Action.Back), Rules.apply(bt, CarEvent.PanelKey(PanelKey.BACK)).second)
    }

    @Test
    fun `a short press on panel power toggles mute and shows the volume bar`() {
        val (muted, actions) = Rules.apply(bt, CarEvent.PanelKey(PanelKey.POWER))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Mute(true)), Action.ShowVolume), actions)
        assertTrue(muted.muted)
        val (unmuted, again) = Rules.apply(muted, CarEvent.PanelKey(PanelKey.POWER))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Mute(false)), Action.ShowVolume), again)
        assertFalse(unmuted.muted)
    }

    @Test
    fun `volume covers the unit's level range`() {
        assertEquals(30, Rules.MAX_VOLUME)
    }

    @Test
    fun `volume keys mute at zero and unmute on the way up`() {
        val (atZero, down) = Rules.apply(bt.copy(volume = 1), CarEvent.PanelKey(PanelKey.VOL_DOWN))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Volume(-1)), Action.Send(CarCommand.Audio.Mute(true)), Action.ShowVolume), down)
        assertEquals(0, atZero.volume)
        assertTrue(atZero.muted)
        // nothing left to lower, but the key still brings the volume bar up
        assertEquals(listOf(Action.ShowVolume), Rules.apply(atZero, CarEvent.SteeringKey(Key.VOL_DOWN)).second)
        val (up, upActions) = Rules.apply(atZero, CarEvent.SteeringKey(Key.VOL_UP))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Volume(1)), Action.Send(CarCommand.Audio.Mute(false)), Action.ShowVolume), upActions)
        assertFalse(up.muted)
    }

    @Test
    fun `volume command moves the level within range`() {
        assertEquals(Rules.DEFAULT_VOLUME + 1, Rules.onCommand(bt, CarCommand.Audio.Volume(1)).volume)
        assertEquals(Rules.MAX_VOLUME, Rules.onCommand(bt.copy(volume = Rules.MAX_VOLUME), CarCommand.Audio.Volume(1)).volume)
        assertEquals(0, Rules.onCommand(bt.copy(volume = 0), CarCommand.Audio.Volume(-1)).volume)
    }

    @Test
    fun `a level picked on screen mutes at zero and unmutes above it`() {
        val s = bt.copy(volume = 5)
        assertEquals(listOf(CarCommand.Audio.Volume(3)), Rules.setVolume(s, 8))
        assertEquals(listOf(CarCommand.Audio.Volume(-5), CarCommand.Audio.Mute(true)), Rules.setVolume(s, 0))
        assertEquals(emptyList(), Rules.setVolume(s.copy(volume = 0, muted = true), 0))
        assertEquals(listOf(CarCommand.Audio.Volume(2), CarCommand.Audio.Mute(false)), Rules.setVolume(s.copy(volume = 0, muted = true), 2))
        assertEquals(listOf(CarCommand.Audio.Volume(Rules.MAX_VOLUME - 5)), Rules.setVolume(s, Rules.MAX_VOLUME + 50))
    }

    @Test
    fun `mute key toggles`() {
        val (muted, a1) = Rules.apply(bt, CarEvent.SteeringKey(Key.MUTE))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Mute(true))), a1)
        assertTrue(muted.muted)
        val (_, a2) = Rules.apply(muted, CarEvent.SteeringKey(Key.MUTE))
        assertEquals(listOf(Action.Send(CarCommand.Audio.Mute(false))), a2)
    }

    @Test
    fun `next and prev go to the active source`() {
        assertEquals(listOf(Action.Send(CarCommand.Media.Next)), Rules.apply(bt, CarEvent.SteeringKey(Key.NEXT)).second)
        assertEquals(listOf(Action.Send(CarCommand.Media.Prev)), Rules.apply(bt, CarEvent.SteeringKey(Key.PREV)).second)
        assertEquals(listOf(Action.Send(CarCommand.Radio.Seek(true))), Rules.apply(radio, CarEvent.SteeringKey(Key.NEXT)).second)
        assertEquals(listOf(Action.Send(CarCommand.Radio.Seek(false))), Rules.apply(radio, CarEvent.SteeringKey(Key.PREV)).second)
        assertEquals(emptyList<Action>(), Rules.apply(RulesState(), CarEvent.SteeringKey(Key.NEXT)).second)
    }

    @Test
    fun `mode key switches source and remembers the previous one`() {
        val (s1, a1) = Rules.apply(bt, CarEvent.SteeringKey(Key.MODE))
        assertEquals(AudioSource.RADIO, s1.source)
        assertEquals(AudioSource.BT, s1.lastSource)
        assertEquals(listOf(Action.Send(CarCommand.Audio.Source(AudioSource.RADIO))), a1)
        val (s2, _) = Rules.apply(s1, CarEvent.SteeringKey(Key.MODE))
        assertEquals(AudioSource.BT, s2.source)
    }

    @Test
    fun `mode key without bluetooth stays on radio`() {
        val (s, a) = Rules.apply(radio, CarEvent.SteeringKey(Key.MODE))
        assertEquals(AudioSource.RADIO, s.source)
        assertEquals(emptyList<Action>(), a)
    }

    @Test
    fun `phone key answers when ringing and hangs up when active`() {
        val ringing = bt.copy(call = State.RINGING)
        assertEquals(listOf(Action.Send(CarCommand.Phone.Answer)), Rules.apply(ringing, CarEvent.SteeringKey(Key.PHONE)).second)
        val active = bt.copy(call = State.ACTIVE)
        assertEquals(listOf(Action.Send(CarCommand.Phone.HangUp)), Rules.apply(active, CarEvent.SteeringKey(Key.PHONE)).second)
        assertEquals(emptyList<Action>(), Rules.apply(bt, CarEvent.SteeringKey(Key.PHONE)).second)
    }

    @Test
    fun `illumination switches night mode`() {
        val (s, a) = Rules.apply(RulesState(), CarEvent.Illumination(true))
        assertTrue(s.night)
        assertEquals(listOf(Action.Night(true)), a)
    }

    @Test
    fun `acc off saves and acc on restores`() {
        assertEquals(listOf(Action.SaveState), Rules.apply(bt, CarEvent.Power(false)).second)
        assertEquals(
            listOf(Action.RestoreState, Action.Send(CarCommand.Audio.Source(AudioSource.BT)), Action.Send(CarCommand.Media.Play)),
            Rules.apply(bt, CarEvent.Power(true)).second,
        )
        assertEquals(listOf(Action.RestoreState), Rules.apply(RulesState(), CarEvent.Power(true)).second)
    }

    @Test
    fun `bluetooth reconnect resumes bt when it was the last source`() {
        val disconnected = RulesState(source = AudioSource.NONE, lastSource = AudioSource.BT)
        val (s, a) = Rules.apply(disconnected, CarEvent.BtConnection(true, "Phone"))
        assertTrue(s.btConnected)
        assertEquals(AudioSource.BT, s.source)
        assertEquals(listOf(Action.Send(CarCommand.Audio.Source(AudioSource.BT))), a)
    }

    @Test
    fun `bluetooth disconnect drops the bt source`() {
        val (s, a) = Rules.apply(bt, CarEvent.BtConnection(false))
        assertEquals(AudioSource.NONE, s.source)
        assertEquals(AudioSource.BT, s.lastSource)
        assertFalse(s.playing)
        assertEquals(emptyList<Action>(), a)
    }

    @Test
    fun `playback updates state only`() {
        val (s, a) = Rules.apply(bt, CarEvent.Playback(false))
        assertFalse(s.playing)
        assertEquals(emptyList<Action>(), a)
    }

    @Test
    fun `playback with no source selects bt`() {
        val (s, a) = Rules.apply(RulesState(btConnected = true), CarEvent.Playback(true))
        assertEquals(AudioSource.BT, s.source)
        assertTrue(s.playing)
        assertEquals(emptyList<Action>(), a)
    }

    @Test
    fun `playback does not take the source away from radio`() {
        val (s, _) = Rules.apply(radio, CarEvent.Playback(true))
        assertEquals(AudioSource.RADIO, s.source)
    }

    @Test
    fun `informational events change nothing`() {
        for (e in listOf(CarEvent.Track("x"), CarEvent.Radio(100_000, CarEvent.Radio.Band.FM), CarEvent.CarDisplay("clock"))) {
            val (s, a) = Rules.apply(bt, e)
            assertEquals(bt, s)
            assertEquals(emptyList<Action>(), a)
        }
    }

    @Test
    fun `source command from the ui updates state`() {
        val s = Rules.onCommand(bt, CarCommand.Audio.Source(AudioSource.RADIO))
        assertEquals(AudioSource.RADIO, s.source)
        assertEquals(AudioSource.BT, s.lastSource)
        assertEquals(bt, Rules.onCommand(bt, CarCommand.Media.Next))
    }
}
