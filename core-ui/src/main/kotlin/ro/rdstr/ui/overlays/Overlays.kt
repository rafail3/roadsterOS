package ro.rdstr.ui.overlays

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.View
import ro.rdstr.car.Camera
import ro.rdstr.car.CameraSettings
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CarEvent.Call.State
import ro.rdstr.car.Contact
import ro.rdstr.car.Guidelines
import ro.rdstr.car.PhoneBook
import ro.rdstr.car.PhoneFormat
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.CssGradient
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.phone.Align
import ro.rdstr.ui.phone.Avatar
import ro.rdstr.ui.phone.Box
import ro.rdstr.ui.phone.CallInfo
import ro.rdstr.ui.phone.CallKey
import ro.rdstr.ui.phone.ContactsList
import ro.rdstr.ui.phone.Dir
import ro.rdstr.ui.phone.Ink
import ro.rdstr.ui.phone.Pill
import ro.rdstr.ui.phone.PhoneText
import ro.rdstr.ui.phone.TabButton
import ro.rdstr.ui.phone.icon
import ro.rdstr.ui.phone.lp
import ro.rdstr.ui.phone.padGrid
import ro.rdstr.ui.phone.tappable
import ro.rdstr.ui.phone.txt
import ro.rdstr.ui.tokens.Gradient
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

/** What the overlays trigger: the store calls and dispatches of Overlays.tsx. */
interface OverlayActions {
    fun carCommand(command: CarCommand)
    fun shellAction(action: ShellAction)
    fun openThread(number: String)
    fun dismissMessage()
}

/** A screen-sized box that holds one overlay; it takes no touches itself, so what is under it stays usable. */
@SuppressLint("ViewConstructor")
open class Layer(context: Context) : Box(context) {
    init { dir = Dir.STACK; lp.w = 1280f; lp.h = 750f; isClickable = false }
}

/**
 * Overlays.tsx `CallOverlay`: the call over everything, always dark. Who it is on the left; on the right the answer and
 * decline keys while it rings, else the controls (mute, tones, where it is heard, hold or swap, add or merge), the
 * keypad for tones or a second number, the phone book to add a call from, and the end key. A second call shows
 * above the controls while it rings and under the name while it waits on hold.
 */
@Mirrors("Overlays.tsx")
@SuppressLint("ViewConstructor")
class CallOverlayView(context: Context, private val actions: OverlayActions) : Box(context, "overlay.call") {
    var clock: () -> Long = System::currentTimeMillis
    private var call = CallInfo(State.IDLE, "")
    private var contacts: List<Contact> = emptyList()
    private var region = "RO"
    private var tones: String? = null
    private var adding: String? = null
    private var addFromContacts = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { status.text = call.statusLine(clock()); ticker.postDelayed(this, 1000) } }

    private val who = add(Box(context, "call-who")) { shrink = true }
    private val avatar = who.add(Avatar(context, "call-avatar", 128f, 48f, 64f, 0f, immersive = true)) { mb = 16f }
    private val name = who.add(txt(context, 44f, 600, "call-name", tracking = -0.02f)) { shrink = true }
    private val sub = who.add(txt(context, 18f, 400, "call-sub"))
    private val status = who.add(txt(context, 22f, 400, "call-status")) { minH = 30f; mt = 8f }
    private val held = who.add(Box(context, "call-held")) { mt = 12f }
    private val heldIcon = held.add(icon(context, "i-pause-fill", 18f)) { w = 18f; h = 18f }
    private val heldTxt = held.add(txt(context, 17f, 400))
    private val side = add(Box(context, "call-side"))

    init {
        lp.w = 1280f; lp.h = 750f
        // .overlay { place-items: center }
        dir = Dir.GRID; tracks = floatArrayOf(-1f, -1f); align = Align.CENTER; justifyItems = Align.CENTER
        padL = 96f; padT = 64f; padR = 96f; padB = 40f
        isClickable = true
        contentDescription = "Call"
        who.dir = Dir.COLUMN; who.align = Align.CENTER; who.gap = 8f
        avatar.inkColor = -1
        held.dir = Dir.ROW; held.align = Align.CENTER; held.gap = 8f; held.padL = 16f; held.padR = 16f; held.padT = 8f; held.padB = 8f; held.css.radius = 999f
        side.dir = Dir.COLUMN; side.align = Align.CENTER; side.gap = 36f
        onTheme = { theme() }
    }

    private fun theme() {
        val ink = Ink(true)
        ink.solid(this, 0); css.gradient = GROUND
        name.color = -1; sub.color = ink.ink2; status.color = ink.on(0.86f)
        if (ink.glassy) ink.glass(held, groundAt(368f, 560f)) else ink.solid(held, ink.on(0.1f))
        heldIcon.color = ink.on(0.86f); heldTxt.color = ink.on(0.86f)
    }

    fun bind(c: CallInfo, contacts: List<Contact>, region: String) {
        val restyle = c != call || contacts != this.contacts || region != this.region
        call = c; this.contacts = contacts; this.region = region
        if (c.state == State.IDLE) { tones = null; adding = null }
        if (restyle) build()
    }

    private fun fmt(n: String) = PhoneFormat.format(n, region)

    private fun send(cmd: CarCommand) = actions.carCommand(cmd)

    private fun build() {
        val c = call
        val ringing = c.state == State.RINGING
        avatar.set(if (c.conference) "" else PhoneBook.initials(c.name), c.conference)
        name.text = c.title(::fmt)
        sub.text = if (c.conference) "Calls merged" else if (c.name.isNotEmpty()) listOf(c.label, fmt(c.number)).filter { it.isNotEmpty() }.joinToString(" · ") else "Not in contacts"
        status.text = c.statusLine(clock())
        val onHold = c.onHold
        held.visibility = if (onHold != null) VISIBLE else GONE
        heldTxt.text = onHold?.let { "${it.name.ifEmpty { fmt(it.number) }} on hold" }.orEmpty()
        side.removeAllViews()
        val add = adding
        if (add != null) side.add(Box(context, "phone-tabs.call-add-tabs")).apply {
            dir = Dir.ROW; gap = 4f; padL = 4f; padT = 4f; padR = 4f; padB = 4f; css.radius = 999f
            contentDescription = "Add a call from"
            add(TabButton(context, "i-dots-nine-fill", "Keypad", immersive = true) { addFromContacts = false; build() }).chosen = !addFromContacts
            add(TabButton(context, "i-user-circle-fill", "Contacts", immersive = true) { addFromContacts = true; build() }).chosen = addFromContacts
            onTheme = { val ink = Ink(true); if (ink.glassy) ink.glass(this, groundAt(912f, 150f)) else ink.solid(this, Ink.FIELD) }
        }
        c.other?.takeIf { it.waiting }?.let { side.add(waiting(it)) }
        if (ringing) {
            side.add(Box(context, "call-answer")).apply {
                dir = Dir.ROW; gap = 112f
                add(labelled(false, "Decline") { send(CarCommand.Phone.HangUp) })
                add(labelled(true, "Accept") { send(CarCommand.Phone.Answer) })
            }
        } else {
            when {
                add != null && addFromContacts -> side.add(Box(context, "call-add-contacts")) { self = Align.STRETCH; h = 400f }.apply {
                    dir = Dir.STACK
                    add(ContactsList(context, contacts, "", ::fmt, immersive = true, search = null, onDialed = { adding = null; build() }) { send(CarCommand.Phone.Dial(it)) })
                }
                add != null -> side.add(tonesPad(fmt(add), PhoneText.match(PhoneBook.suggest(contacts, add)).ifEmpty { "Add a call, then merge" }, "Number to add", plus = true) { k ->
                    adding = (adding ?: "") + k; build()
                })
                tones != null -> side.add(tonesPad(tones!!, null, "Tones sent", plus = false) { k ->
                    send(CarCommand.Phone.Dtmf(k)); tones = (tones ?: "") + k; build()
                })
                else -> side.add(controls(c))
            }
            side.add(endRow(c))
        }
        requestLayout()
    }

    private fun labelled(accept: Boolean, label: String, onTap: () -> Unit) = Box(context, "call-key-labelled").apply {
        dir = Dir.COLUMN; align = Align.CENTER; gap = 12f
        contentDescription = label
        tappable(onTap)
        add(CallKey(context, accept, 96f, immersive = true).also { it.isClickable = false; it.isDuplicateParentStateEnabled = true })
        val t = add(txt(context, 17f, 600, null, label))
        onTheme = { t.color = -1 }
    }

    private fun waiting(o: CarEvent.Call.Other) = Box(context, "call-waiting").apply {
        dir = Dir.COLUMN; align = Align.CENTER; gap = 14f; padL = 20f; padR = 20f; padT = 18f; padB = 18f; css.radius = 22f
        contentDescription = "Second call, ${o.name.ifEmpty { o.number }}"
        val text = add(Box(context, "call-waiting-text")).apply { dir = Dir.ROW }
        val strong = text.add(txt(context, 19f, 700, "strong", o.name.ifEmpty { fmt(o.number) }))
        val rest = text.add(txt(context, 19f, 400, null, " is calling"))
        val g = { groundAt(912f, 200f) }
        add(Box(context, "call-waiting-actions")).apply {
            dir = Dir.ROW; gap = 10f
            add(Pill(context, "Decline", Pill.Kind.DECLINE, g, immersive = true) { send(CarCommand.Phone.DeclineWaiting) })
            add(Pill(context, "End & Accept", Pill.Kind.PLAIN, g, immersive = true) { send(CarCommand.Phone.AnswerWaiting(keepCurrent = false)) })
            add(Pill(context, "Hold & Accept", Pill.Kind.ACCEPT, g, immersive = true) { send(CarCommand.Phone.AnswerWaiting(keepCurrent = true)) })
        }
        onTheme = {
            val ink = Ink(true)
            if (ink.glassy) ink.glass(this, g()) else ink.solid(this, ink.on(0.1f))
            strong.color = -1; rest.color = ink.on(0.86f)
        }
    }

    private fun tonesPad(shown: String, match: String?, label: String, plus: Boolean, onPress: (String) -> Unit) = Box(context, "call-tones").apply {
        dir = Dir.COLUMN; align = Align.CENTER; gap = 12f
        val typed = add(txt(context, 32f, 400, "call-tones-typed", shown, tracking = 0.06f)) { minH = 40f }
        typed.contentDescription = label
        val m = match?.let { add(txt(context, 17f, 400, "phone-typed-match", it)) { minH = 22f } }
        add(padGrid(context, compact = true, tones = true, plus = plus, immersive = true, onPress = onPress))
        onTheme = { val ink = Ink(true); typed.color = ink.ink; m?.color = ink.ink2 }
    }

    private fun controls(c: CallInfo) = Box(context, "call-controls").apply {
        dir = Dir.GRID; tracks = floatArrayOf(96f, 96f, 96f); gap = 28f; gap2 = 28f
        add(Toggle(context, "i-microphone-slash-fill", "Mute", c.micMuted) { send(CarCommand.Phone.Mute(!c.micMuted)) })
        add(Toggle(context, "i-dots-nine-fill", "Keypad", false) { tones = ""; build() })
        add(Toggle(context, "i-device-mobile-speaker-fill", "Phone Audio", !c.inCar) { send(CarCommand.Phone.AudioInCar(!c.inCar)) })
        if (c.onHold != null) {
            add(Toggle(context, "i-arrows-left-right", "Swap", false) { send(CarCommand.Phone.Hold) })
            add(Toggle(context, "i-arrows-merge", "Merge", false) { send(CarCommand.Phone.Merge) })
        } else {
            add(Toggle(context, "i-pause-fill", if (c.held) "Resume" else "Hold", c.held) { send(CarCommand.Phone.Hold) })
            // the current call goes on hold while the new one is placed; the two then merge into a conference
            if (c.other == null) add(Toggle(context, "i-user-plus-fill", "Add Call", false) { adding = ""; addFromContacts = false; build() })
        }
    }

    private fun endRow(c: CallInfo) = Box(context, "call-end-row").apply {
        dir = Dir.ROW; justify = Align.CENTER
        val add = adding
        if (add != null) {
            if (addFromContacts) add(Pill(context, "Cancel", Pill.Kind.PLAIN, { groundAt(912f, 650f) }, immersive = true) { adding = null; build() })
            else {
                add(hide("Cancel", left = true) { adding = null; build() })
                val s = PhoneBook.suggest(contacts, add)
                add(CallKey(context, accept = true, size = 84f, immersive = true)).apply {
                    contentDescription = s?.let { "Call ${it.name}" } ?: "Call"
                    isEnabled = add.isNotEmpty()
                    tappable { if (add.isNotEmpty()) { send(CarCommand.Phone.Dial(s?.number ?: add)); adding = null; build() } }
                }
            }
        } else {
            if (tones != null) add(hide("Hide", left = false) { tones = null; build() })
            add(CallKey(context, accept = false, size = 84f, immersive = true)).apply {
                contentDescription = if (c.conference) "End conference" else "End call"
                tappable { send(CarCommand.Phone.HangUp) }
            }
        }
    }

    /** `.call-hide`: a text button beside the key, 72 px off the row's middle (`.is-left` on the other side). */
    private fun hide(label: String, left: Boolean, onTap: () -> Unit) = Box(context, "call-hide").apply {
        dir = Dir.STACK; align = Align.CENTER; padL = 20f; padR = 20f
        lp.minH = 56f; lp.abs = true; lp.top = 14f
        if (left) lp.right = 42f + 72f else lp.left = 42f + 72f
        contentDescription = label
        tappable(onTap)
        val t = add(txt(context, 18f, 600, null, label))
        onTheme = { t.color = -1 }
    }

    /** Overlays.tsx `CallToggle`: a round control with its label under it; a toggle that is on is white. */
    @SuppressLint("ViewConstructor")
    private inner class Toggle(context: Context, iconCls: String, label: String, on: Boolean, onTap: () -> Unit) : Box(context, "call-toggle") {
        init {
            dir = Dir.COLUMN; align = Align.CENTER; gap = 10f
            contentDescription = label
            isSelected = on
            tappable(onTap)
            val disc = add(Box(context, "call-toggle-disc")) { w = 84f; h = 84f }.apply {
                dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER; css.radius = 999f
                pressScale = 0.94f; isDuplicateParentStateEnabled = true
            }
            val glyph = disc.add(icon(context, iconCls, 34f)) { w = 34f; h = 34f }
            val text = add(txt(context, 15f, 600, "call-toggle-label", label))
            onTheme = {
                val ink = Ink(true)
                when {
                    on -> { ink.solid(disc, -1); if (ink.glassy) disc.css.shadows = ink.glassShadow }
                    ink.glassy -> ink.glass(disc, groundAt(912f, 330f), ink.glassFill, ink.glassFillStrong)
                    else -> ink.solid(disc, ink.on(0.16f))
                }
                glyph.color = if (on) 0xFF111111.toInt() else -1
                text.color = ink.ink2
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.post(tick) }
    override fun onDetachedFromWindow() { ticker.removeCallbacks(tick); super.onDetachedFromWindow() }

    companion object {
        /** `.call { background: linear-gradient(160deg, #1b2028 0%, #0b0d11 70%) }` */
        val GROUND = Gradient(160f, intArrayOf(0xFF1B2028.toInt(), 0xFF0B0D11.toInt()), floatArrayOf(0f, 0.7f))

        /** The call's ground at ([x], [y]) of the screen: what a glass surface there frosts. */
        fun groundAt(x: Float, y: Float): Int {
            val l = CssGradient.line(GROUND.angle, 1280f, 750f)
            val dx = l[2] - l[0]; val dy = l[3] - l[1]
            val t = ((x - l[0]) * dx + (y - l[1]) * dy) / (dx * dx + dy * dy)
            return CssColor.lerp(GROUND.colors[0], GROUND.colors[1], (t / 0.7f).coerceIn(0f, 1f))
        }
    }
}

/** `.call-banner` / `.message-banner` / `.voice`: the raised pill over the screen, with its hairline and drop. */
private fun raised(box: Box, ink: Ink) {
    if (ink.glassy) ink.glassOverScreen(box)
    else { ink.solid(box, ink.panelRaised); box.css.shadows = listOf(Shadow(false, 0f, 8f, 24f, 0f, 0x59000000)) }
    box.css.borderColor = ink.on(0.14f)
}

/** `@keyframes banner-in`: from 24 px up and transparent, 360 ms on `--sheet-in`. */
private fun bannerIn(v: View) {
    if (UiTheme.speed <= 0f) return
    val d = v.resources.displayMetrics.density
    v.translationY = -24 * d; v.alpha = 0f
    v.animate().translationY(0f).alpha(1f).setDuration(UiTheme.duration(360)).setInterpolator(Motion.interpolator(UiTheme.base.sheetIn)).start()
}

/**
 * Overlays.tsx `CallBar`: the call folded away by Home or Back, under the status bar: a ringing call with its two
 * answers, any other with its clock and the key that ends it; a tap brings the call screen back.
 */
@Mirrors("Overlays.tsx")
@SuppressLint("ViewConstructor")
class CallBarView(context: Context, private val actions: OverlayActions) : Layer(context) {
    var clock: () -> Long = System::currentTimeMillis
    private val banner = add(Box(context, "call-banner")) { abs = true; top = 60f; left = 440f; w = 400f }
    private val open = banner.add(Box(context, "call-banner-open")) { grow = 1f; shrink = true }
    private val avatar = open.add(Avatar(context, "phone-avatar", 48f, 18f, 26f, 0.02f, immersive = false))
    private val text = open.add(Box(context, "message-banner-text")) { shrink = true }
    private val name = text.add(txt(context, 18f, 600, "message-banner-name"))
    private val body = text.add(txt(context, 16f, 400, "message-banner-body")) { shrink = true }
    private val decline = banner.add(CallKey(context, accept = false, size = 56f))
    private val accept = banner.add(CallKey(context, accept = true, size = 56f))
    private var call = CallInfo(State.IDLE, "")
    private var region = "RO"
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { if (call.state != State.RINGING) body.text = call.barLine(clock()); ticker.postDelayed(this, 1000) } }

    init {
        banner.dir = Dir.ROW; banner.align = Align.CENTER; banner.gap = 12f
        banner.padL = 10f; banner.padT = 10f; banner.padR = 12f; banner.padB = 10f
        banner.css.radius = 26f; banner.css.border = 1f
        open.dir = Dir.ROW; open.align = Align.CENTER; open.gap = 14f
        open.tappable { actions.shellAction(ShellAction.ExpandCall) }
        text.dir = Dir.COLUMN; text.gap = 2f
        decline.contentDescription = "Decline"
        decline.tappable { actions.carCommand(CarCommand.Phone.HangUp) }
        accept.contentDescription = "Accept"
        accept.tappable { actions.carCommand(CarCommand.Phone.Answer) }
        banner.onTheme = { theme() }
    }

    private fun theme() {
        val ink = Ink(false)
        raised(banner, ink)
        name.color = ink.ink
        body.color = if (call.state == State.RINGING) ink.ink2 else ink.green
    }

    fun bind(c: CallInfo, region: String) {
        val was = call.state
        call = c; this.region = region
        val ringing = c.state == State.RINGING
        val title = c.title { PhoneFormat.format(it, region) }
        avatar.set(if (c.conference) "" else PhoneBook.initials(c.name), c.conference)
        name.text = title
        body.uiKey = if (ringing) "message-banner-body" else "message-banner-body.call-banner-time"
        body.text = if (ringing) "Incoming call" else c.barLine(clock())
        accept.visibility = if (ringing) VISIBLE else GONE
        decline.contentDescription = if (ringing) "Decline" else if (c.conference) "End conference" else "End call"
        banner.contentDescription = if (ringing) "Incoming call, $title" else "Call with $title"
        open.contentDescription = if (ringing) "Open the call from $title" else "Open the call with $title, ${c.status(clock())}"
        if (isAttachedToWindow) theme()
        if (was == State.IDLE && c.state != State.IDLE) bannerIn(banner)
        requestLayout()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.post(tick) }
    override fun onDetachedFromWindow() { ticker.removeCallbacks(tick); super.onDetachedFromWindow() }
}

/** Overlays.tsx `VoiceOverlay`: the phone's assistant listening through the car's microphone, and the key that stops it. */
@Mirrors("Overlays.tsx")
@SuppressLint("ViewConstructor")
class VoiceView(context: Context, actions: OverlayActions) : Layer(context) {
    private val pill = add(Box(context, "voice")) { abs = true; bottom = 40f; self = Align.CENTER }

    init {
        pill.dir = Dir.ROW; pill.align = Align.CENTER; pill.gap = 16f
        pill.padL = 14f; pill.padT = 10f; pill.padR = 10f; pill.padB = 10f; pill.css.radius = 999f; pill.css.border = 1f
        pill.contentDescription = "Listening on your phone…"
        val mic = pill.add(Box(context, "voice-mic")) { w = 56f; h = 56f }.apply { dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER; css.radius = 999f }
        val glyph = mic.add(icon(context, "i-microphone-fill", 28f)) { w = 28f; h = 28f }
        val text = pill.add(txt(context, 19f, 600, "voice-text", "Listening on your phone…"))
        pill.add(Pill(context, "Stop", Pill.Kind.PLAIN, { Ink(false).panelRaised }, immersive = false) { actions.carCommand(CarCommand.Phone.VoiceAssistant(false)) })
        pill.onTheme = {
            val ink = Ink(false)
            raised(this, ink)
            if (ink.glassy) ink.tinted(mic, Ink.BLUE) else ink.solid(mic, Ink.BLUE)
            glyph.color = ink.ink; text.color = ink.ink
        }
        // ponytail: the 1.4 s pulse ring around the microphone is not drawn
    }
}

/**
 * Overlays.tsx `MessageBanner`: a new text at the top of the screen, its words only while the car stands; a tap opens
 * the thread; it goes back into the phone app after 6000 ms.
 */
@Mirrors("Overlays.tsx")
@SuppressLint("ViewConstructor")
class MessageBannerView(context: Context, private val actions: OverlayActions) : Layer(context) {
    private val banner = add(Box(context, "message-banner")) { abs = true; top = 64f; left = 380f; w = 520f }
    private val disc = banner.add(Box(context, "message-banner-icon")) { w = 48f; h = 48f }
    private val glyph = disc.add(icon(context, "i-chat-circle-text-fill", 28f)) { w = 28f; h = 28f }
    private val text = banner.add(Box(context, "message-banner-text")) { shrink = true }
    private val name = text.add(txt(context, 18f, 600, "message-banner-name"))
    private val body = text.add(txt(context, 16f, 400, "message-banner-body")) { shrink = true }
    private var shown: String? = null
    private val dismiss = Runnable { actions.dismissMessage() }

    init {
        banner.dir = Dir.ROW; banner.align = Align.CENTER; banner.gap = 14f
        banner.padL = 18f; banner.padR = 18f; banner.padT = 14f; banner.padB = 14f; banner.css.radius = 24f; banner.css.border = 1f
        disc.dir = Dir.STACK; disc.align = Align.CENTER; disc.justify = Align.CENTER; disc.css.radius = 12f
        text.dir = Dir.COLUMN; text.gap = 2f
        banner.onTheme = {
            val ink = Ink(false)
            raised(this, ink)
            if (ink.glassy) ink.tinted(disc, ink.green) else ink.solid(disc, ink.green)
            glyph.color = ink.ink; name.color = ink.ink; body.color = ink.ink2
        }
        visibility = GONE
    }

    /** [message] (null for none), named from the book, its words while [stopped]. */
    fun bind(message: CarEvent.Message?, contacts: List<Contact>, region: String, stopped: Boolean) {
        if (message == null) { visibility = GONE; shown = null; removeCallbacks(dismiss); return }
        val who = message.name.ifEmpty { PhoneBook.find(contacts, message.number)?.name ?: PhoneFormat.format(message.number, region) }
        name.text = who
        body.text = if (stopped) message.text else "New message"
        banner.contentDescription = "Message from $who, open"
        banner.tappable { actions.openThread(message.number) }
        visibility = VISIBLE
        if (shown != message.id) {
            shown = message.id
            removeCallbacks(dismiss)
            postDelayed(dismiss, BANNER_MS)
            bannerIn(banner)
        }
        requestLayout()
    }

    private companion object { const val BANNER_MS = 6000L }
}

/**
 * Overlays.tsx `CameraOverlay`: the reverse camera over everything, its picture (the vendor's AV_IN video on the unit,
 * put in [feed]) with the guidelines, the close key when it was opened by hand, and the label under it.
 */
@Mirrors("Overlays.tsx")
@SuppressLint("ViewConstructor")
class CameraView(context: Context, private val actions: OverlayActions, feed: View? = null) : Box(context, "overlay.camera") {
    private val feedBox = add(Box(context, "camera-feed")) { grow = 1f; shrink = true }
    val guides = GuideLines(context)
    private val close = add(Box(context, "camera-close")) { abs = true; top = 16f; right = 16f; w = 56f; h = 56f }
    private val closeIcon = close.add(icon(context, "i-x", 24f)) { w = 24f; h = 24f }
    private val label = add(Box(context, "camera-label"))
    private val labelTxt = label.add(txt(context, 16f, 400))

    init {
        lp.w = 1280f; lp.h = 750f
        dir = Dir.COLUMN
        isClickable = true
        contentDescription = "Reverse camera"
        feedBox.dir = Dir.STACK; feedBox.justify = Align.STRETCH
        feed?.let { feedBox.add(it) { self = Align.STRETCH } }
        feedBox.add(guides) { self = Align.STRETCH }
        close.dir = Dir.STACK; close.align = Align.CENTER; close.justify = Align.CENTER; close.css.radius = 999f; close.css.border = 1f
        close.contentDescription = "Close camera"
        close.tappable { actions.shellAction(ShellAction.CloseCamera) }
        label.dir = Dir.STACK; label.padL = 24f; label.padR = 24f; label.padT = 12f; label.padB = 12f
        onTheme = {
            val b = UiTheme.base
            val ink = Ink(false)
            ink.solid(this, 0xFF000000.toInt())
            if (ink.glassy) ink.glassOverScreen(close) else ink.solid(close, b.glassStrong)
            close.css.borderColor = b.hairlineStrong
            closeIcon.color = b.ink
            ink.solid(label, b.canvas); labelTxt.color = b.inkMuted
        }
    }

    /** In reverse gear ([reverse]) the gear closes it; opened by hand, the close key does. */
    fun bind(reverse: Boolean, camera: CameraSettings, eps: Int) {
        close.visibility = if (reverse) GONE else VISIBLE
        labelTxt.text = if (reverse) "Reverse gear" else "Camera"
        guides.set(camera, eps)
        requestLayout()
    }
}

/**
 * Overlays.tsx `GuideLines`: the vendor's lines on its 1024x600 picture (core-car Camera.guides), fitted into the
 * feed as the SVG's viewBox is, moved down by the line position: the two curves yellow, red near the car and green
 * far inside the vendor's bands, and three bars across.
 */
class GuideLines(context: Context) : View(context) {
    private var g = Camera.guides(Guidelines.FIXED, 0)
    private var on = true
    private var linePos = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f }
    private val curves = Path()
    private val red = Path()
    private val green = Path()

    init { uiKey = "guide-lines"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(camera: CameraSettings, eps: Int) {
        on = camera.guidelines != Guidelines.OFF
        visibility = if (on) VISIBLE else GONE
        linePos = camera.linePos
        g = Camera.guides(if (on) camera.guidelines else Guidelines.FIXED, eps)
        curves.reset()
        for ((fix, control, far) in listOf(Triple(g.leftFix, g.leftControl, g.leftFar), Triple(g.rightFix, g.rightControl, g.rightFar))) {
            curves.moveTo(fix.x.toFloat(), fix.y.toFloat())
            curves.quadTo(control.x.toFloat(), control.y.toFloat(), far.x.toFloat(), far.y.toFloat())
        }
        red.reset()
        rect(red, 0.0, g.leftNear.y - 5, g.leftNear.x + 5, g.leftFix.y + 5)
        rect(red, g.rightNear.x - 5, g.rightNear.y - 5, W, g.rightFix.y + 5)
        green.reset()
        rect(green, 0.0, g.leftFar.y - 5, maxOf(g.leftFar.x, g.leftMiddle.x) + 8, g.leftMiddle.y - 3)
        rect(green, minOf(g.rightFar.x, g.rightMiddle.x) - 8, g.rightFar.y - 5, W, g.rightMiddle.y - 3)
        invalidate()
    }

    private fun rect(p: Path, x1: Double, y1: Double, x2: Double, y2: Double) {
        if (x2 > x1 && y2 > y1) p.addRect(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        if (!on || width == 0) return
        // viewBox 0 0 1024 600, preserveAspectRatio xMidYMid meet; transform: translateY(linePos / 600 of its height)
        val s = minOf(width / W.toFloat(), height / H.toFloat())
        canvas.save()
        canvas.translate((width - W.toFloat() * s) / 2f, (height - H.toFloat() * s) / 2f + height * linePos / H.toFloat())
        canvas.scale(s, s)
        paint.color = YELLOW; canvas.drawPath(curves, paint)
        canvas.save(); canvas.clipPath(red); paint.color = RED; canvas.drawPath(curves, paint); canvas.restore()
        canvas.save(); canvas.clipPath(green); paint.color = GREEN; canvas.drawPath(curves, paint); canvas.restore()
        bar(canvas, g.leftFar, g.rightFar, GREEN)
        bar(canvas, g.leftMiddle, g.rightMiddle, YELLOW)
        bar(canvas, g.leftNear, g.rightNear, RED)
        canvas.restore()
    }

    private fun bar(c: Canvas, a: ro.rdstr.car.Point, b: ro.rdstr.car.Point, color: Int) {
        paint.color = color
        c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
    }

    private companion object {
        const val W = 1024.0
        const val H = 600.0
        // the vendor's colours (MainUI com.ts.can.CameraUIView)
        const val GREEN = 0xFF4CAF50.toInt()
        const val YELLOW = 0xFFFFFF00.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}

