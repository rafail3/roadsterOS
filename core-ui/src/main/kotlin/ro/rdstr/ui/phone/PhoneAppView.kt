package ro.rdstr.ui.phone

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import ro.rdstr.car.CallRecord
import ro.rdstr.car.CallType
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Contact
import ro.rdstr.car.Conversation
import ro.rdstr.car.Messages
import ro.rdstr.car.PhoneBook
import ro.rdstr.car.PhoneFormat
import ro.rdstr.car.PhoneGate
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.GlassButton
import ro.rdstr.ui.kit.HoldRepeat
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

/** What the phone app shows: the emulator state PhoneApp.tsx reads, as the unit has it. */
data class PhoneState(
    val open: Boolean = false,
    val screen: PhoneGate.Screen = PhoneGate.Screen.BT_OFF,
    val btTurningOn: Boolean = false,
    /** The connected phone's Bluetooth name. */
    val device: String = "",
    val operator: String = "",
    /** 0..5 from the hands-free link. */
    val signal: Int = 0,
    val battery: Int = 0,
    val contacts: List<Contact> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
    val messages: List<CarEvent.Message> = emptyList(),
    val read: Set<String> = emptySet(),
    val messagesSupported: Boolean = true,
    /** The thread to open (from a message banner), null for the list. */
    val thread: String? = null,
    /** The car stands still: message words show. */
    val stopped: Boolean = true,
    val lockTypingWhileMoving: Boolean = false,
    val region: String = "RO",
    /** Saved phones that can take calls, for the screen that asks for a phone; the one connecting and the one that failed. */
    val saved: List<String> = emptyList(),
    val connecting: String? = null,
    val failed: String? = null,
)

/**
 * PhoneApp.tsx: the phone app. With Bluetooth off it offers to turn it on, without a phone it asks for one; with one
 * connected for calls (core-car PhoneGate) the history, the phone book and the messages on the left, the keypad on the
 * right, and one on-screen keyboard for the search and the message being written. It follows the theme, its chat
 * panel too.
 */
@Mirrors("PhoneApp.tsx")
@SuppressLint("ViewConstructor")
class PhoneAppView(context: Context, private val actions: Actions, private val keyboard: KeyboardSlot? = null) : Box(context, "music-app.phone-app") {
    /** Everything the app triggers: exactly the store calls and dispatches of PhoneApp.tsx. */
    interface Actions {
        fun shellAction(action: ShellAction)
        fun carCommand(command: CarCommand)
        fun turnOnBluetooth()
        fun openThread(number: String?)
        fun markRead(ids: List<String>)
    }

    enum class Tab { RECENTS, CONTACTS, MESSAGES }

    /** The time the lists are labelled at (`useNow`), ms since 1970. */
    var clock: () -> Long = System::currentTimeMillis

    private var state = PhoneState()
    private var tab = Tab.RECENTS
    private var query = ""
    private var typing = false
    private var draft = ""
    private var writing = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refreshTimes(); ticker.postDelayed(this, 1000) } }

    private val close = add(Box(context, "glass-button.music-close")) { abs = true; top = 76f; left = 32f; w = 56f; h = 56f }.apply {
        add(GlassButton(context, Icons.CARET_DOWN)) { abs = true; left = 0f; top = 0f; w = 56f; h = 56f }
            .apply { contentDescription = "Close phone"; setOnClickListener { actions.shellAction(ShellAction.Home) } }
        add(Mark(context, "icon.i-caret-down")) { abs = true; left = 14f; top = 14f; w = 28f; h = 28f }
    }
    private val noPhone = NoPhone(context)
    private val side = Box(context, "phone-side")
    private val pad = Box(context, "phone-pad")
    private val tabs = Box(context, "phone-tabs")
    private val tabViews = ArrayList<TabButton>()
    private val badge = Box(context, "phone-badge")
    private val badgeTxt = txt(context, 13f, 700)
    private val chip = Box(context, "phone-chip")
    private val bars = Bars(context)
    private val chipName = txt(context, 16f, 600, "phone-chip-name")
    private val battery = Battery(context)
    private val panel = Box(context, "phone-panel")
    private val typed = txt(context, 40f, 500, "phone-typed-number", tracking = 0.02f)
    private val typedMatch = txt(context, 17f, 400, "phone-typed-match")
    private var number = ""
    private lateinit var padCall: CallKey
    private val padDelete = Box(context, "pad-delete")
    private val padDeleteIcon = icon(context, "i-backspace-fill", 32f)
    private val whenLabels = ArrayList<Pair<Txt, Long>>()

    init {
        contentDescription = "Phone"
        lp.w = 1280f; lp.h = 750f
        buildSide()
        buildPad()
        add(noPhone)
        keyboard?.let { add(it.view) { abs = true; left = 0f; right = 0f; bottom = 0f } }
        onTheme = { val ink = Ink(false); ink.solid(this, ink.ground); themeAll(ink) }
        visibility = INVISIBLE
        show(PhoneGate.Screen.BT_OFF)
    }

    // ---- building -------------------------------------------------------------------------------------------------

    private fun buildSide() {
        add(side) { shrink = true }
        side.dir = Dir.COLUMN; side.gap = 16f
        val head = side.add(Box(context, "phone-head")) {}
        head.dir = Dir.ROW; head.align = Align.CENTER; head.justify = Align.BETWEEN; head.gap = 16f
        head.add(tabs)
        tabs.dir = Dir.ROW; tabs.gap = 4f; tabs.padL = 4f; tabs.padT = 4f; tabs.padR = 4f; tabs.padB = 4f; tabs.css.radius = 999f
        tabs.contentDescription = "Phone"
        for ((t, ic, label) in listOf(Triple(Tab.RECENTS, "i-clock-fill", "Recents"), Triple(Tab.CONTACTS, "i-user-circle-fill", "Contacts"), Triple(Tab.MESSAGES, "i-chat-circle-text-fill", "Messages"))) {
            tabViews += tabs.add(TabButton(context, ic, label) { pick(t) })
        }
        badge.dir = Dir.STACK; badge.align = Align.CENTER; badge.justify = Align.CENTER
        badge.lp.minW = 22f; badge.lp.h = 22f; badge.padL = 6f; badge.padR = 6f; badge.css.radius = 11f
        badge.add(badgeTxt)
        tabViews[2].add(badge)
        head.add(chip)
        chip.dir = Dir.ROW; chip.align = Align.CENTER; chip.gap = 12f; chip.lp.h = 52f; chip.padL = 20f; chip.padR = 20f
        chip.css.radius = 999f; chip.css.border = 1f
        chip.add(bars); chip.add(chipName) { maxW = 180f; shrink = true }; chip.add(battery)
        chip.tappable { actions.shellAction(ShellAction.OpenDevices) }
        side.add(panel) { grow = 1f; shrink = true }
        panel.dir = Dir.STACK; panel.justify = Align.STRETCH
    }

    private fun buildPad() {
        add(pad)
        pad.dir = Dir.COLUMN; pad.align = Align.CENTER; pad.justify = Align.CENTER; pad.gap = 18f
        val typedBox = pad.add(Box(context, "phone-typed")) { self = Align.STRETCH; h = 80f }
        typedBox.dir = Dir.COLUMN; typedBox.align = Align.CENTER; typedBox.justify = Align.END; typedBox.gap = 4f
        typedBox.add(typed) { minH = 48f; shrink = true }
        typedBox.add(typedMatch) { minH = 22f }
        pad.add(padGrid(context, compact = false, tones = false, plus = true, immersive = false) { k -> setNumber(number + k) })
        val foot = pad.add(Box(context, "pad-foot"))
        foot.dir = Dir.GRID; foot.tracks = floatArrayOf(84f, 84f, 84f); foot.gap = 26f; foot.justifyItems = Align.CENTER; foot.align = Align.CENTER
        // the empty first slot: the phone's voice assistant key is not shown
        foot.add(View(context)) { w = 0f; h = 0f }
        padCall = foot.add(CallKey(context, accept = true, size = 84f))
        padCall.tappable {
            if (number.isNotEmpty()) {
                actions.carCommand(CarCommand.Phone.Dial(PhoneBook.suggest(state.contacts, number)?.number ?: number))
                setNumber("")
            } else actions.carCommand(CarCommand.Phone.Redial)
        }
        foot.add(padDelete)
        padDelete.dir = Dir.STACK; padDelete.align = Align.CENTER; padDelete.justify = Align.CENTER
        padDelete.lp.w = 64f; padDelete.lp.h = 64f
        padDelete.add(padDeleteIcon) { w = 32f; h = 32f }
        padDelete.contentDescription = "Delete"
        HoldRepeat.attach(padDelete, { if (number.isNotEmpty()) setNumber(number.dropLast(1)) })
        setNumber("")
    }

    private fun setNumber(n: String) {
        number = n
        typed.text = fmt(n)
        val m = PhoneBook.suggest(state.contacts, n)
        typedMatch.text = PhoneText.match(m)
        padCall.contentDescription = if (n.isNotEmpty()) "Call ${m?.name ?: n}" else "Redial"
        padDelete.visibility = if (n.isNotEmpty()) VISIBLE else INVISIBLE
        requestLayout()
    }

    private fun fmt(n: String) = PhoneFormat.format(n, state.region)

    // ---- state ------------------------------------------------------------------------------------------------------

    /** Shows [s]. */
    fun bind(s: PhoneState) {
        val before = state
        state = s
        if (s.thread != null && s.thread != before.thread) tab = Tab.MESSAGES
        if (tab != Tab.CONTACTS || !s.open) typing = false
        val locked = s.lockTypingWhileMoving && !s.stopped
        if (tab != Tab.MESSAGES || !s.open || s.thread == null || locked) writing = false
        if (s.open != before.open) open(s.open)
        show(s.screen)
        if (s.screen == PhoneGate.Screen.READY) {
            chipName.text = s.device
            chip.contentDescription = "${s.device}, ${s.operator}, signal ${s.signal} of 5, battery ${s.battery * 20}%, choose device"
            bars.signal = s.signal
            battery.level = s.battery
            val unread = if (s.messagesSupported) Messages.conversations(s.messages, s.read, s.contacts).sumOf { it.unread } else 0
            badge.visibility = if (unread > 0) VISIBLE else GONE
            badgeTxt.text = unread.toString()
            badge.contentDescription = "$unread unread"
            if (s.contacts != before.contacts || s.region != before.region) setNumber(number)
            fillPanel(before)
        } else if (s.screen == PhoneGate.Screen.CONNECT || s.screen == PhoneGate.Screen.BT_OFF) {
            noPhone.bind(s, actions)
        }
        keys()
        requestLayout()
    }

    private fun show(screen: PhoneGate.Screen) {
        val ready = screen == PhoneGate.Screen.READY
        side.visibility = if (ready) VISIBLE else GONE
        pad.visibility = if (ready) VISIBLE else GONE
        keyboard?.view?.visibility = if (ready) VISIBLE else GONE
        noPhone.visibility = if (ready) GONE else VISIBLE
        if (ready) {
            // .phone-app.is-ready: the list beside a 340 px keypad
            dir = Dir.GRID; tracks = floatArrayOf(-1f, 340f); gap = 56f; align = Align.STRETCH
            padL = 128f; padT = 76f; padR = 56f; padB = 20f
        } else {
            // .music-app: the message alone, in the middle
            dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER
            padL = 112f; padT = 56f; padR = 96f; padB = 0f
        }
        side.padB = if (writing) 350f else 0f
    }

    private fun pick(t: Tab) {
        if (tab == t) return
        tab = t
        if (t != Tab.CONTACTS) typing = false
        if (t != Tab.MESSAGES) writing = false
        fillPanel(null)
        keys()
        // .phone-panel is keyed by the tab: the new one fades in
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(UiTheme.duration(200)).setInterpolator(Motion.interpolator(UiTheme.base.seamless)).start()
    }

    private var shownTab: Tab? = null
    private var shownKey: Any? = null

    /** Rebuilds the panel when what it shows changed. */
    private fun fillPanel(before: PhoneState?) {
        val s = state
        val key: Any = when (tab) {
            Tab.RECENTS -> listOf(s.calls, s.contacts, s.region)
            Tab.CONTACTS -> listOf(s.contacts, s.region, s.device, query, typing)
            Tab.MESSAGES -> listOf(s.messages, s.read, s.contacts, s.region, s.device, s.thread, s.stopped, s.messagesSupported, draft, writing, s.lockTypingWhileMoving)
        }
        if (shownTab == tab && key == shownKey && before != null) return
        shownTab = tab; shownKey = key
        tabViews.forEachIndexed { i, b -> b.chosen = i == tab.ordinal }
        panel.removeAllViews()
        whenLabels.clear()
        panel.add(when (tab) {
            Tab.RECENTS -> recents()
            Tab.CONTACTS -> ContactsList(context, s.contacts, s.device, ::fmt, immersive = false, search = search(), onDialed = null) { actions.carCommand(CarCommand.Phone.Dial(it)) }
            Tab.MESSAGES -> messages()
        })
        refreshTimes()
    }

    private fun search() = ContactsList.Search(query, typing, onFocus = { typing = true; refresh() }, onClear = { query = ""; typing = false; refresh() })

    private fun refresh() { shownKey = null; fillPanel(state); keys(); show(state.screen); requestLayout() }

    private fun refreshTimes() {
        val now = clock()
        for ((t, at) in whenLabels) t.text = PhoneText.whenLabel(at, now)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.post(tick) }
    override fun onDetachedFromWindow() { ticker.removeCallbacks(tick); super.onDetachedFromWindow() }

    // ---- Recents ----------------------------------------------------------------------------------------------------

    private fun recents(): View {
        val rows = PhoneBook.recents(state.calls, state.contacts)
        if (rows.isEmpty()) return empty("No recent calls.")
        val list = list("Recent calls")
        for (r in rows) {
            val missed = r.type == CallType.MISSED
            val typeLabel = TYPE_LABEL.getValue(r.type)
            val row = row(list).tappable { actions.carCommand(CarCommand.Phone.Dial(r.number)) }
            val type = row.add(icon(context, TYPE_ICON.getValue(r.type), 24f, "phone-row-type")) { w = 24f; h = 24f }
            val text = rowText(row)
            val name = r.name.ifEmpty { fmt(r.number) }
            val nameView: View = if (r.count > 1) Box(context, "phone-row-name").apply {
                dir = Dir.ROW
                val n = add(txt(context, 20f, 600, null, name)) { shrink = true }
                val c = add(txt(context, 20f, 400, "phone-row-count", " (${r.count})"))
                onTheme = { val ink = Ink(false); n.color = if (missed) ink.red else ink.ink; c.color = ink.ink2 }
            } else txt(context, 20f, 600, "phone-row-name", name).also { t -> t.onInk { t.color = if (missed) it.red else it.ink } }
            text.add(nameView) { shrink = true }
            val sub = text.add(txt(context, 15f, 400, "phone-row-sub", if (r.name.isNotEmpty()) r.label.ifEmpty { fmt(r.number) } else typeLabel)) { shrink = true }
            val whenTxt = row.add(txt(context, 16f, 400, "phone-row-when"))
            whenLabels += whenTxt to r.atMs
            row.contentDescription = "Call ${r.name.ifEmpty { r.number }}, $typeLabel${if (r.count > 1) ", ${r.count} calls" else ""}"
            row.onTheme = { val ink = Ink(false); type.color = if (missed) ink.red else ink.ink2; sub.color = ink.ink2; whenTxt.color = ink.ink2; rowPress(this, ink) }
        }
        return list
    }

    // ---- Messages ---------------------------------------------------------------------------------------------------

    private fun messages(): View {
        val s = state
        if (!s.messagesSupported) return unsupported()
        val threads = Messages.conversations(s.messages, s.read, s.contacts)
        val open = PhoneText.open(threads, s.thread)
        if (open == null) {
            if (threads.isEmpty()) return empty("Messages to ${s.device} show here while it is connected.")
            val list = list("Messages")
            for (t in threads) {
                val row = row(list).tappable { actions.openThread(t.messages.last().number) }
                row.add(Avatar(context, "phone-avatar", 48f, 18f, 26f, 0.02f, false).apply { set(PhoneBook.initials(t.name), false) })
                val text = rowText(row)
                val name = text.add(txt(context, 20f, 600, "phone-row-name", t.name.ifEmpty { fmt(t.number) })) { shrink = true }
                val preview = text.add(txt(context, 15f, 400, "phone-row-sub.phone-row-preview", if (s.stopped) t.last else "Stop the car to read")) { shrink = true }
                val whenTxt = row.add(txt(context, 16f, 400, "phone-row-when"))
                whenLabels += whenTxt to t.atMs
                val dot = if (t.unread > 0) row.add(Box(context, "phone-dot")) { w = 10f; h = 10f }.apply { css.radius = 999f; fillWith(Ink.BLUE) } else null
                dot?.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                row.contentDescription = "${t.name.ifEmpty { t.number }}${if (t.unread > 0) ", ${t.unread} unread" else ""}"
                row.onTheme = { val ink = Ink(false); name.color = ink.ink; preview.color = ink.ink2; whenTxt.color = ink.ink2; rowPress(this, ink) }
            }
            return list
        }
        if (open.unread > 0) post { actions.markRead(open.messages.filter { !it.sent }.map { it.id }) }
        return thread(open)
    }

    private fun thread(open: Conversation): View {
        val s = state
        val locked = s.lockTypingWhileMoving && !s.stopped
        val to = PhoneText.replyTo(open)
        val root = Box(context, "phone-thread").apply { dir = Dir.COLUMN; gap = 12f }
        val head = root.add(Box(context, "phone-thread-head")).apply { dir = Dir.ROW; align = Align.CENTER; gap = 14f }
        val back = head.add(Box(context, "pad-side")) { w = 64f; h = 64f }.apply {
            dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER; css.radius = 999f
            contentDescription = "All messages"
            tappable { actions.openThread(null) }
        }
        val caret = back.add(icon(context, "i-caret-left", 28f)) { w = 28f; h = 28f }
        val name = head.add(txt(context, 20f, 600, "phone-row-name", open.name.ifEmpty { fmt(open.number) })) { grow = 1f; shrink = true }
        head.add(CallKey(context, accept = true, size = 56f)).apply {
            contentDescription = "Call ${open.name.ifEmpty { open.number }}"
            tappable { actions.carCommand(CarCommand.Phone.Dial(to)) }
        }
        val chat = root.add(Box(context, "phone-chat")) { grow = 1f; shrink = true }.apply {
            dir = Dir.COLUMN; gap = 12f; padL = 16f; padT = 16f; padR = 16f; padB = 16f; css.radius = 22f; css.border = 1f
        }
        val bubbles = chat.add(Box(context, "phone-bubbles")) { grow = 1f; shrink = true }.apply {
            dir = Dir.COLUMN; gap = 8f; padL = 4f; padT = 4f; padR = 4f; padB = 8f; align = Align.START; scrolls = true
            contentDescription = "Conversation"
        }
        val bubbleViews = open.messages.map { m ->
            bubbles.add(Box(context, "bubble")) { self = if (m.sent) Align.END else Align.START; maxFrac = 0.7f }.apply {
                dir = Dir.STACK; padL = 16f; padR = 16f; padT = 10f; padB = 10f; css.radius = 20f
                add(txt(context, 18f, 400, null, if (s.stopped || m.sent) m.text else "Stop the car to read", lineHeight = 1.4f).apply { multi = true }) { shrink = true }
            } to m.sent
        }
        val replies = if (!writing) chat.add(Box(context, "phone-replies")).apply {
            dir = Dir.WRAP; gap = 10f; gap2 = 10f
            contentDescription = "Quick replies"
            for (r in Messages.QUICK_REPLIES) add(Pill(context, r, ground = { chatGround() }, immersive = false) { actions.carCommand(CarCommand.Phone.SendMessage(to, r)) })
        } else null
        val compose = chat.add(Box(context, "phone-compose")).apply { dir = Dir.ROW; align = Align.CENTER; gap = 10f }
        val field = compose.add(Box(context, "phone-compose-field")) { grow = 1f; shrink = true; h = 52f }.apply {
            dir = Dir.ROW; align = Align.CENTER; gap = 10f; padL = 18f; padR = 18f; css.radius = 26f; css.border = 1f
            isEnabled = !locked
            alpha = if (locked) 0.6f else 1f
            contentDescription = if (locked) "Stop the car to type" else if (draft.isNotEmpty()) "Message, $draft" else "Write a message"
            tappable { if (!locked) { writing = true; refresh() } }
        }
        val fieldTxt = field.add(txt(context, 18f, 400, if (draft.isNotEmpty()) "phone-search-text" else "phone-search-placeholder",
            draft.ifEmpty { if (locked) "Stop the car to type" else "Message" })) { shrink = true }
        if (writing) field.add(Caret(context)) { w = 2f; h = 24f; ml = -8f }
        val send = compose.add(Box(context, "phone-send")) { w = 52f; h = 52f }.apply {
            dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER; css.radius = 999f
            pressScale = 0.92f
            contentDescription = "Send"
            val enabled = draft.isNotBlank()
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
            tappable { if (draft.isNotBlank()) sendDraft(to) }
        }
        val arrow = send.add(icon(context, "i-arrow-up-bold", 24f)) { w = 24f; h = 24f }
        root.onTheme = {
            val ink = Ink(false)
            name.color = ink.ink; caret.color = ink.ink; arrow.color = -1
            if (ink.glassy) ink.glass(back, ink.ground) else ink.solid(back, ink.on(0.1f), ink.on(0.22f))
            if (ink.glassy) ink.glass(chat, ink.ground)
            else { ink.solid(chat, 0); chat.css.gradient = UiTheme.base.phoneChat }
            chat.css.borderColor = ink.on(0.07f)
            for ((b, sent) in bubbleViews) {
                (b.getChildAt(0) as Txt).color = if (sent) -1 else ink.ink
                when { sent -> ink.solid(b, Ink.BLUE); ink.glassy -> ink.glass(b, chatGround()); else -> ink.solid(b, ink.on(0.14f)) }
            }
            if (ink.glassy) ink.glass(field, chatGround()) else ink.solid(field, ink.on(0.08f))
            field.css.borderColor = ink.on(0.12f)
            field.css.shadows = (if (ink.glassy) field.css.shadows else emptyList()) + if (writing) listOf(Shadow(true, 0f, 0f, 0f, 2f, Ink.BLUE)) else emptyList()
            fieldTxt.color = if (draft.isNotEmpty()) ink.ink else ink.ink2
            if (ink.glassy) ink.tinted(send, Ink.BLUE) else ink.solid(send, Ink.BLUE)
        }
        return root
    }

    /** What the chat panel's glass children frost: the panel over the app's ground. */
    private fun chatGround(): Int {
        val ink = Ink(false)
        return CssColor.over(ink.glassFill, ink.frosted(ink.ground))
    }

    private fun sendDraft(to: String) {
        actions.carCommand(CarCommand.Phone.SendMessage(to, draft.trim()))
        draft = ""; writing = false
        refresh()
    }

    private fun unsupported(): View = Box(context, "phone-unsupported").apply {
        dir = Dir.COLUMN; align = Align.CENTER; gap = 10f
        lp.maxW = 520f
        lp.self = Align.START
        padT = 48f
        val disc = add(Box(context, "music-nophone-icon")) { w = 96f; h = 96f }.apply { dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER; css.radius = 999f }
        val glyph = disc.add(icon(context, "i-chat-circle-text-fill", 44f)) { w = 44f; h = 44f }
        val h2 = add(txt(context, 24f, 600, "h2", "Messages are not available on this unit").apply { centered = true; multi = true }) { mt = 12f }
        val p = add(txt(context, 17f, 400, "p", "Its Bluetooth cannot receive or send text messages. Calls, contacts and recent calls work as usual.", lineHeight = 1.5f).apply { centered = true; multi = true })
        onTheme = {
            val ink = Ink(false)
            if (ink.glassy) ink.glass(disc, ink.ground) else ink.solid(disc, ink.on(0.12f))
            glyph.color = ink.ink; h2.color = ink.ink; p.color = ink.ink2
        }
    }

    // ---- shared rows ------------------------------------------------------------------------------------------------

    private fun empty(text: String): View = phoneEmpty(context, text, immersive = false)

    private fun list(label: String) = Box(context, "phone-list").apply {
        dir = Dir.COLUMN; padB = 24f; scrolls = true
        contentDescription = label
        onTheme = { thumbColor = Ink(false).on(0.24f) }
    }

    /** `li > button.phone-row`: the line between rows is the li's top border. */
    private fun row(list: Box): Box {
        val li = list.add(Box(context, "li"))
        li.dir = Dir.COLUMN
        if (list.childCount > 1) li.onTheme = { topLine = Ink(false).on(0.07f) }
        return li.add(Box(context, "phone-row")).apply {
            dir = Dir.ROW; align = Align.CENTER; gap = 16f; padL = 16f; padR = 16f; css.radius = 16f
            lp.minH = 76f
        }
    }

    private fun rowText(row: Box) = row.add(Box(context, "phone-row-text")) { grow = 1f; shrink = true }.apply { dir = Dir.COLUMN; gap = 2f }

    // ---- keyboard ---------------------------------------------------------------------------------------------------

    private fun keys() {
        val kb = keyboard ?: return
        kb.bind(typing || writing, if (typing) "Search" else "Send", typing || draft.isNotBlank(), object : KeyboardSlot.Keys {
            override fun onKey(char: String) { if (typing) query += char else draft += char; refresh() }
            override fun onBackspace() { if (typing) query = query.dropLast(1) else draft = draft.dropLast(1); refresh() }
            override fun onSubmit() {
                if (typing) { typing = false; refresh() }
                else if (draft.isNotBlank() && state.thread != null) {
                    PhoneText.open(Messages.conversations(state.messages, state.read, state.contacts), state.thread)
                        ?.let { sendDraft(PhoneText.replyTo(it)) } ?: sendDraft(state.thread!!)
                }
            }
        })
    }

    // ---- theme ------------------------------------------------------------------------------------------------------

    private fun themeAll(ink: Ink) {
        if (ink.glassy) ink.glass(tabs, ink.ground) else ink.solid(tabs, Ink.FIELD)
        if (ink.glassy) ink.glass(chip, ink.ground, ink.glassFill, ink.glassFillStrong) else ink.solid(chip, ink.on(0.08f), ink.on(0.16f))
        chip.css.borderColor = ink.on(0.1f)
        chipName.color = ink.ink
        ink.solid(badge, ink.red); badgeTxt.color = -1
        typed.color = ink.ink; typedMatch.color = ink.ink2
        padDeleteIcon.color = ink.ink2
    }

    // ---- open and close ---------------------------------------------------------------------------------------------

    private val frame = AppFrame(this)

    private fun open(on: Boolean) = frame.open(on)


    override fun draw(canvas: android.graphics.Canvas) {
        if (frame.clip(canvas)) { super.draw(canvas); canvas.restore() } else super.draw(canvas)
    }

    private companion object {
        val TYPE_ICON = mapOf(CallType.INCOMING to "i-phone-incoming", CallType.OUTGOING to "i-phone-outgoing", CallType.MISSED to "i-phone-x")
        val TYPE_LABEL = mapOf(CallType.INCOMING to "Incoming", CallType.OUTGOING to "Outgoing", CallType.MISSED to "Missed")
    }
}

/** phone.css `.phone-row` pressed: `rgb(var(--on) / .08)` over `--fast`. */
internal fun rowPress(row: Box, ink: Ink) { row.fillWith(0, ink.on(0.08f)) }

/** Runs [block] with the theme's [Ink] at attach and after each theme change. */
internal fun Txt.onInk(block: Txt.(Ink) -> Unit) = UiTheme.link(this) { block(Ink(false)) }

/** A tab of `.phone-tabs`: its icon and label, filled while chosen; Glassy adds the sheen and rim to the chosen one. */
@SuppressLint("ViewConstructor")
class TabButton(context: Context, iconCls: String, label: String, private val immersive: Boolean = false, onTap: () -> Unit) : Box(context, "button") {
    private val glyph = add(icon(context, iconCls, 22f)) { w = 22f; h = 22f }
    private val text = add(txt(context, 18f, 600, null, label))
    var chosen = false
        set(v) { if (field != v) { field = v; isSelected = v; restyle(true) } }

    init {
        dir = Dir.ROW; align = Align.CENTER; gap = 10f; padL = 24f; padR = 24f; css.radius = 999f
        lp.h = 52f
        contentDescription = label
        tappable(onTap)
        onTheme = { restyle(false) }
    }

    private var inkAnim: android.animation.ValueAnimator? = null

    private fun restyle(animate: Boolean) {
        val ink = Ink(immersive)
        val c = if (chosen) ink.ink else ink.ink2
        inkAnim?.cancel()
        val ms = ro.rdstr.ui.kit.UiTheme.duration(220L)
        // `transition: color 220ms var(--seamless)`
        if (animate && ms > 0L && text.color != c) {
            inkAnim = android.animation.ValueAnimator.ofArgb(text.color, c).apply {
                duration = ms
                interpolator = ro.rdstr.ui.kit.Motion.interpolator(ro.rdstr.ui.tokens.Base.DARK.seamless)
                addUpdateListener { val v = it.animatedValue as Int; glyph.color = v; text.color = v }
                start()
            }
        } else { glyph.color = c; text.color = c }
        val fill = if (chosen) ink.on(0.18f) else 0
        css.gradient = if (chosen && ink.glassy) ink.sheen else null
        css.shadows = if (chosen && ink.glassy) ink.glassShadow else emptyList()
        if (animate) fadeTo(fill, 220) else fillWith(fill)
    }
}

/** `.phone-bars`: five bars 4 px wide, 5 to 16 px high; the first [signal] in the ink, the rest faint. */
@SuppressLint("ViewConstructor")
internal class Bars(context: Context) : Box(context, "phone-bars") {
    private val bars = listOf(5f, 8f, 11f, 14f, 16f).map { h -> add(Box(context, "i")) { w = 4f; this.h = h }.apply { css.radius = 1f } }
    var signal = 0; set(v) { field = v; restyle() }

    init {
        dir = Dir.ROW; align = Align.END; gap = 2f; lp.h = 16f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        onTheme = { restyle() }
    }

    private fun restyle() {
        val ink = Ink(false)
        bars.forEachIndexed { i, b -> b.fillWith(if (i < signal) ink.ink else ink.on(0.26f)) }
    }
}

/** `.phone-battery`: an outlined cell with its nub, filled to [level] of 5, red at the last step. */
@SuppressLint("ViewConstructor")
internal class Battery(context: Context) : Box(context, "phone-battery") {
    private val cell = add(Box(context, "i")) { self = Align.STRETCH }.apply { css.radius = 1.5f }
    private val nub = add(Box(context)) { abs = true; top = 3f; right = -4f; w = 2f; h = 5f }
    var level = 0; set(v) { field = v; restyle(); requestLayout() }

    init {
        dir = Dir.ROW
        lp.w = 28f; lp.h = 14f
        padL = 2f; padT = 2f; padR = 2f; padB = 2f
        css.radius = 4f; css.border = 1.5f
        painted = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        onTheme = { restyle() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // transform: scaleX(level / 5) from the left: the cell's box is that share of the room inside
        val room = 28f - 4f - 2 * ro.rdstr.ui.kit.UiScale.borderPx(1.5f, resources.displayMetrics.density) / resources.displayMetrics.density
        cell.lp.w = room * level / 5f
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun restyle() {
        val ink = Ink(false)
        css.borderColor = ink.on(0.55f)
        cell.fillWith(if (level <= 1) ink.red else ink.ink)
        nub.fillWith(ink.on(0.55f))
        nub.css.radius = 1f
    }
}

/** `.phone-search-caret`: the blue caret, blinking once a second. */
internal class Caret(context: Context) : Box(context, "phone-search-caret") {
    private val blink = object : Runnable { override fun run() { alpha = if (alpha > 0f) 0f else 1f; postDelayed(this, 500) } }

    init { fillWith(Ink.BLUE) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (UiTheme.speed > 0f) postDelayed(blink, 500) }
    override fun onDetachedFromWindow() { removeCallbacks(blink); alpha = 1f; super.onDetachedFromWindow() }
}
