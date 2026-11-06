package ro.rdstr.ui.phone

import android.annotation.SuppressLint
import android.content.Context
import ro.rdstr.car.CarCommand
import ro.rdstr.car.PhoneGate
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow

/**
 * The phone app before it can call: PhoneApp.tsx `BluetoothOff` (turn Bluetooth on) and MusicApp.tsx `NoPhone`
 * for calls (the saved phones that take calls, or pairing). music.css `.music-nophone` and its parts.
 */
@SuppressLint("ViewConstructor")
internal class NoPhone(context: Context) : Box(context, "music-nophone") {
    private val disc = add(Box(context, "music-nophone-icon")) { w = 96f; h = 96f }
    private val glyph = disc.add(IconView(context, Icons.BLUETOOTH, 44f)) { w = 44f; h = 44f }
    private val title = add(txt(context, 32f, 600, "h2", tracking = -0.01f).apply { centered = true; multi = true }) { mt = 16f }
    private val line = add(txt(context, 18f, 400, "p").apply { centered = true; multi = true })
    private val list = add(Box(context, "music-nophone-list")) { mt = 24f; self = Align.STRETCH }
    private val more = add(Box(context, "music-nophone-more")) { mt = 12f; h = 56f }
    private val moreTxt = more.add(txt(context, 18f, 600, null, "Other devices"))
    private val pair = add(Box(context, "music-nophone-pair")) { mt = 24f; h = 64f }
    private val pairIcon = pair.add(icon(context, "i-bluetooth", 24f)) { w = 24f; h = 24f }
    private val pairTxt = pair.add(txt(context, 20f, 600))
    private var actions: PhoneAppView.Actions? = null
    private var turnOn = false

    init {
        dir = Dir.COLUMN; align = Align.CENTER; gap = 12f
        lp.w = 560f
        disc.dir = Dir.STACK; disc.align = Align.CENTER; disc.justify = Align.CENTER; disc.css.radius = 999f
        list.dir = Dir.COLUMN; list.gap = 12f
        list.contentDescription = "Saved phones"
        more.dir = Dir.STACK; more.align = Align.CENTER; more.justify = Align.CENTER; more.padL = 28f; more.padR = 28f; more.css.radius = 999f
        more.tappable { actions?.shellAction(ShellAction.OpenDevices) }
        pair.dir = Dir.ROW; pair.align = Align.CENTER; pair.gap = 12f; pair.padL = 36f; pair.padR = 36f; pair.css.radius = 999f
        pair.pressScale = 0.96f; pair.pressOutMs = 420
        pair.tappable { val a = actions ?: return@tappable; if (turnOn) a.turnOnBluetooth() else a.shellAction(ShellAction.OpenDevices) }
        onTheme = { theme() }
    }

    private fun theme() {
        val ink = Ink(false)
        if (ink.glassy) ink.glass(disc, ink.ground) else ink.solid(disc, ink.on(0.12f))
        glyph.color = ink.ink
        title.color = ink.ink
        line.color = ink.on(0.62f)
        ink.solid(more, 0, ink.on(0.12f)); moreTxt.color = ink.on(0.86f)
        if (ink.glassy) ink.tinted(pair, Ink.BLUE, PAIR_DOWN) else ink.solid(pair, Ink.BLUE, PAIR_DOWN)
        pairIcon.color = -1; pairTxt.color = -1
        for (i in 0 until list.childCount) ((list.getChildAt(i) as Box).getChildAt(0) as PhoneRow).theme()
    }

    fun bind(s: PhoneState, actions: PhoneAppView.Actions) {
        this.actions = actions
        turnOn = s.screen == PhoneGate.Screen.BT_OFF
        if (turnOn) {
            glyph.setIcon(Icons.BLUETOOTH)
            title.text = if (s.btTurningOn) "Turning on Bluetooth…" else "Bluetooth is off"
            line.text = "Calls go through a phone connected over Bluetooth."
            list.visibility = GONE; more.visibility = GONE
            pair.visibility = if (s.btTurningOn) GONE else VISIBLE
            pairTxt.text = "Turn On Bluetooth"
        } else {
            glyph.setIcon(Icons.DEVICE_MOBILE)
            title.text = "No phone connected"
            line.text = if (s.saved.isNotEmpty()) "Connect a phone to make calls." else "Pair a phone to make calls."
            list.removeAllViews()
            for (name in s.saved) {
                val li = list.add(Box(context, "li"))
                li.dir = Dir.COLUMN
                li.add(PhoneRow(context, name, s.connecting, s.failed) { actions.carCommand(CarCommand.Bluetooth.Connect(name)) })
            }
            list.visibility = if (s.saved.isNotEmpty()) VISIBLE else GONE
            more.visibility = if (s.saved.isNotEmpty()) VISIBLE else GONE
            pair.visibility = if (s.saved.isEmpty()) VISIBLE else GONE
            pairTxt.text = "Pair a phone"
        }
        if (isAttachedToWindow) theme()
    }

    /** `.music-nophone-phone`: a saved phone to connect, disabled while one connects. */
    @SuppressLint("ViewConstructor")
    private class PhoneRow(context: Context, name: String, connecting: String?, failed: String?, onTap: () -> Unit) : Box(context, "music-nophone-phone") {
        private val bt = add(icon(context, "i-bluetooth", 26f)) { w = 26f; h = 26f }
        private val label = add(txt(context, 20f, 600, "span", when (name) { connecting -> "Connecting…"; failed -> "$name · not available"; else -> name })) { shrink = true }

        init {
            dir = Dir.ROW; align = Align.CENTER; gap = 16f; padL = 28f; padR = 28f; css.radius = 999f
            lp.h = 72f
            pressScale = 0.97f; pressOutMs = 420
            isEnabled = connecting == null
            alpha = if (connecting == null) 1f else 0.55f
            tappable { if (isEnabled) onTap() }
            onTheme = { theme() }
        }

        fun theme() {
            val ink = Ink(false)
            if (ink.glassy) ink.glass(this, ink.ground, ink.glassFill, ink.on(0.22f)) else ink.solid(this, ink.on(0.14f), ink.on(0.22f))
            if (!ink.glassy) css.shadows = listOf(Shadow(true, 0f, 1f, 0f, 0f, ink.on(0.14f)))
            bt.color = Ink.BLUE; label.color = ink.ink
        }
    }

    private companion object {
        /** `.music-nophone-pair:active { background: #0070e0 }` */
        const val PAIR_DOWN = 0xFF0070E0.toInt()
    }
}
