package ro.rdstr.ui.sheets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import kotlin.math.roundToInt

/**
 * The screen's on-screen keyboard as the Wi-Fi password uses it (Keyboard.tsx's props): the keyboard view is built
 * apart and the screen connects it here. [open] shows it with where its keys go, [close] hides it, [submit] sets
 * the label and the state of its submit key.
 */
interface KeyboardSlot {
    fun open(onKey: (String) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit)
    fun close()
    fun submit(label: String, enabled: Boolean)
}

/** A network in range: [signal] in bars, 1 to 3. */
data class WifiItem(val ssid: String, val secure: Boolean, val signal: Int)

/**
 * What the networks list shows: the saved networks, the joined one and the one joining, the one whose last join
 * failed on the password, those in range, and whether a scan is still finding them ([searching]).
 */
data class WifiListState(
    val wifiOn: Boolean = false,
    val locationOn: Boolean = false,
    val saved: List<String> = emptyList(),
    val connected: String? = null,
    val connecting: String? = null,
    val wrongPassword: String? = null,
    val seen: List<WifiItem> = emptyList(),
    val searching: Boolean = false,
)

/**
 * NetworksSheet.tsx `WifiNetworks`, the networks list of the Wi-Fi sheet and of Settings > Wi-Fi: the saved networks
 * to join or forget, the ones in range to join, and a password typed on the screen's keyboard for a new secured one.
 * While [shown] with Wi-Fi and location on it scans now and every [RESCAN_MS]; a hidden list or Wi-Fi off leaves the
 * password view and closes any open row. [onJoining] says when the password view comes and goes (the sheet raises
 * its bottom over the keyboard).
 */
@Mirrors("NetworksSheet.tsx")
@SuppressLint("ViewConstructor")
class WifiNetworksView(context: Context) : Flex(context, null, column = true) {
    /** Exactly the calls `WifiNetworks` makes. */
    interface Actions {
        fun scan()
        fun connect(ssid: String, password: String?)
        fun forget(ssid: String)
        fun setLocation(on: Boolean)
    }

    var actions: Actions? = null
    var keyboard: KeyboardSlot? = null
    var onJoining: ((Boolean) -> Unit)? = null
    var shown = false; set(v) { if (field != v) { field = v; bind(state) } }

    private var state = WifiListState()
    private var openRow: String? = null
    private var joining: String? = null
    private var password = ""
    private var reveal = false
    private val handler = Handler(Looper.getMainLooper())
    private val rescan = object : Runnable { override fun run() { actions?.scan(); handler.postDelayed(this, RESCAN_MS) } }
    private var scanning = false

    private val off = OffPanel(context, "Wi-Fi is off", "Turn on Wi-Fi to see networks and get covers and updates online.", 0, bars = true)
    private val join = Flex(context, "wifi-join", column = true)
    private val back = Flex(context, "set-back.wifi-join-back")
    private val backIcon = icon(context, "icon.i-caret-left", Icons.CARET_LEFT, 22f)
    private val backText = KitText(context, TextStyle(18f, 600)).apply { text = "Networks"; paintFlags = paintFlags or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG }
    private val joinTitle = text(context, "h3", TextStyle(24f, 600))
    private val joinLine = text(context, "p", TextStyle(16f)).apply { text = "Enter the password" }
    private val field = Flex(context, "wifi-field")
    private val fieldText = Flex(context, "wifi-field-text")
    private val typed = KitText(context, TextStyle(22f, 400, 0.04f)).apply { isSingleLine = true; setHorizontallyScrolling(false); paintFlags = paintFlags or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG }
    private val caret = Flex(context, "wifi-caret")
    private val revealButton = TextButton(context, "wifi-reveal", TextStyle(17f, 600))
    private val note = text(context, "wifi-note", TextStyle(16f))
    private val scroll = Flex(context, "devices-scroll", column = true)
    private val scroller = ScrollView(context).apply { overScrollMode = OVER_SCROLL_NEVER }
    private val content = Flex(context, null, column = true)
    private val mine = Flex(context, "section", column = true)
    private val mineTitle = text(context, "devices-section", TextStyle(16f, 600)).apply { text = "My networks" }
    private val mineList = Flex(context, "devices-list", column = true)
    private val others = Flex(context, "section", column = true)
    private val othersTitle = text(context, "devices-section", TextStyle(16f, 600)).apply { text = "Other networks" }
    private val othersList = Flex(context, "devices-list", column = true)
    private val rows = LinkedHashMap<String, SavedRow>()
    private var blink: ValueAnimator? = null

    init {
        add(off, Item(grow = 1f))
        add(join)
        join.gap = 10f
        join.add(back, Item(alignSelf = Align.START, mT = -12f, mB = -12f, mL = -8f, minMain = 0f, h = 48f))
        back.align = Align.CENTER; back.gap = 4f; back.pad(0f, 8f); back.box.radius = 12f
        back.isClickable = true
        back.setOnClickListener { setJoining(null) }
        back.add(backIcon, Item(w = 22f, h = 22f))
        back.add(backText)
        join.add(joinTitle)
        join.add(joinLine, Item(mB = 6f))
        join.add(field, Item(h = 64f))
        field.align = Align.CENTER; field.gap = 12f; field.pad(0f, 8f, 0f, 18f)
        field.box.radius = 16f; field.box.border = 1f
        field.add(fieldText, Item(grow = 1f, shrink = true))
        fieldText.align = Align.CENTER
        fieldText.add(typed, Item(shrink = true))
        fieldText.add(caret, Item(w = 2f, h = 26f, mL = 2f))
        field.add(revealButton, Item(h = 48f))
        revealButton.setOnClickListener { reveal = !reveal; showJoin() }
        join.add(note)
        add(scroll, Item(grow = 1f, mL = -8f, mR = -8f))
        scroll.pad(0f, 8f)
        scroll.add(scroller, Item(grow = 1f))
        scroller.addView(content)
        content.add(mine, Item(mT = 4f))
        mine.add(mineTitle, Item(mB = 12f))
        mine.add(mineList)
        mineList.gap = 12f
        content.add(others, Item(mT = 20f))
        others.add(othersTitle, Item(mB = 12f))
        others.add(othersList)
        othersList.gap = 12f
        UiTheme.link(this) { restyle() }
        bind(state)
    }

    fun bind(s: WifiListState) {
        state = s
        // a hidden list or Wi-Fi off leaves nothing half typed
        if (!shown || !s.wifiOn) { if (joining != null) setJoining(null); if (openRow != null) { openRow = null } }
        // a join that worked closes the password view
        if (joining != null && s.connected == joining) setJoining(null)
        scan(shown && s.wifiOn && s.locationOn)
        off.visibility = if (!s.wifiOn) VISIBLE else GONE
        join.visibility = if (s.wifiOn && joining != null) VISIBLE else GONE
        scroll.visibility = if (s.wifiOn && joining == null) VISIBLE else GONE
        if (joining != null) showJoin()
        mine.visibility = if (s.saved.isNotEmpty()) VISIBLE else GONE
        (others.layoutParams as Item).mT = if (s.saved.isNotEmpty()) 20f else 4f
        for (gone in rows.keys - s.saved.toSet()) mineList.removeView(rows.remove(gone))
        for ((i, ssid) in s.saved.withIndex()) {
            val row = rows.getOrPut(ssid) { SavedRow(context, Icons.WIFI_HIGH).also { r ->
                r.onSelect = { actions?.connect(ssid, null) }
                r.onForget = { actions?.forget(ssid) }
                r.onOpen = { o -> openRow = if (o) ssid else null; rows.forEach { (n, v) -> v.isOpen = n == openRow } }
            } }
            if (mineList.indexOfChild(row) != i) { mineList.removeView(row); mineList.addView(row, i, Item()) }
            val seen = s.seen.find { it.ssid == ssid }
            val (key, text) = when {
                s.connected == ssid -> "connected" to "Connected"
                s.connecting == ssid -> "connecting" to "Connecting…"
                else -> "saved" to if (seen != null) "Saved" else "Saved · not in range"
            }
            row.show(ssid, key, text, s.connecting != null, canDrop = false)
            row.signal(seen?.secure ?: true, seen?.signal ?: 0)
            row.isOpen = openRow == ssid
        }
        othersList.removeAllViews()
        for (n in s.seen.filter { it.ssid !in s.saved }) {
            val row = FoundRow(context, Icons.WIFI_HIGH)
            row.main.label.name.text = n.ssid
            row.main.label.status.text = if (s.connecting == n.ssid) "Connecting…" else if (n.secure) "Secured" else "Open"
            row.main.lock(s.connecting != null, false)
            row.signal.visibility = VISIBLE
            row.signal.set(n.secure, n.signal)
            row.main.setOnClickListener { if (n.secure) setJoining(n.ssid) else actions?.connect(n.ssid, null) }
            othersList.add(row)
        }
        if (!s.locationOn) {
            // a scan needs location on this Android; saved networks above still join
            val row = Flex(context, "devices-empty.wifi-nolocation", column = true).apply { align = Align.START; gap = 12f; pad(16f) }
            row.add(text(context, "span", TextStyle(16f)).apply { text = "Turn on Location to find networks."; setTextColor(on(0.62f)) })
            val turnOn = TextButton(context, "wifi-location-on", TextStyle(16f, 600)).apply {
                text = "Turn on Location"; setTextColor(-1); box.radius = 999f
                val p = (22 * resources.displayMetrics.density).roundToInt(); setPadding(p, 0, p, 0)
                box.look(0xFF0A84FF.toInt(), tinted = true); fill(box.fill); pressedFill = 0xFF0070E0.toInt()
                setOnClickListener { actions?.setLocation(true) }
            }
            row.add(turnOn, Item(h = 48f))
            othersList.add(row)
        } else if (s.searching) othersList.add(emptyLine(context).apply { text = "Searching…" })
        else if (s.seen.none { it.ssid !in s.saved }) othersList.add(emptyLine(context).apply { text = "No other networks around." })
        restyle()
        requestLayout()
    }

    private fun scan(on: Boolean) {
        if (on == scanning) return
        scanning = on
        handler.removeCallbacks(rescan)
        if (on) rescan.run()
    }

    private fun sending() = joining != null && state.connecting == joining

    internal fun setJoining(ssid: String?) {
        if (ssid == joining) return
        joining = ssid
        password = ""; reveal = false
        onJoining?.invoke(ssid != null)
        if (ssid != null && shown) keyboard?.open(
            { c -> password += c; showJoin() },
            { password = password.dropLast(1); showJoin() },
            { val j = joining; if (j != null && password.length >= MIN_PASSWORD && !sending()) actions?.connect(j, password) },
        ) else keyboard?.close()
        bind(state)
    }

    private fun showJoin() {
        val j = joining ?: return
        joinTitle.text = j
        typed.text = if (reveal) password else "•".repeat(password.length)
        revealButton.text = if (reveal) "Hide" else "Show"
        val wrong = state.wrongPassword == j
        note.text = if (sending()) "Connecting…" else if (wrong) "Incorrect password. Try again." else "At least $MIN_PASSWORD characters"
        keyboard?.submit(if (sending()) "Connecting…" else "Connect", password.length >= MIN_PASSWORD && !sending())
        restyle()
        requestLayout()
    }

    private fun restyle() {
        val b = UiTheme.base
        off.restyle()
        // --set-blue of `.networks` (settings.css), the same blue as Settings
        val blue = if (UiTheme.light) 0xFF0A6CDC.toInt() else 0xFF0A84FF.toInt()
        backIcon.color = blue; backText.setTextColor(blue)
        back.pressedFill = on(0.08f); back.fill(0)
        joinTitle.setTextColor(b.ink); joinLine.setTextColor(on(0.62f))
        val wrong = joining != null && state.wrongPassword == joining
        field.box.look(on(0.08f)); field.box.borderColor = if (wrong) 0xFFFF453A.toInt() else on(0.16f)
        typed.setTextColor(b.ink)
        caret.box.fill = 0xFF0A84FF.toInt()
        blinkCaret()
        revealButton.setTextColor(0xFF0A84FF.toInt()); revealButton.box.radius = 12f
        val p = (14 * resources.displayMetrics.density).roundToInt(); revealButton.setPadding(p, 0, p, 0)
        revealButton.fill(0); revealButton.pressedFill = on(0.1f)
        note.setTextColor(if (wrong) 0xFFFF8A80.toInt() else on(0.62f))
        mineTitle.setTextColor(on(0.62f)); othersTitle.setTextColor(on(0.62f))
        for (r in rows.values) r.restyle()
        for (i in 0 until othersList.childCount) when (val v = othersList.getChildAt(i)) {
            is FoundRow -> v.restyle()
            is KitText -> v.setTextColor(on(0.62f))
        }
        invalidate()
    }

    /** `caret-blink`: on half a second, off half a second, while the password view shows. */
    private fun blinkCaret() {
        val run = joining != null && UiTheme.speed > 0f && isAttachedToWindow
        if (run == (blink != null)) return
        blink?.cancel(); blink = null; caret.alpha = 1f
        if (!run) return
        blink = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { caret.alpha = if (it.animatedFraction < 0.5f) 1f else 0f }
            start()
        }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); scan(false); blink?.cancel(); blink = null }

    private companion object {
        const val RESCAN_MS = 10_000L
        const val MIN_PASSWORD = 8
    }
}

/** NetworksSheet.tsx `NetworksSheet`: the Wi-Fi sheet, its switch and the networks list over whatever is on screen. */
@Mirrors("NetworksSheet.tsx")
@SuppressLint("ViewConstructor")
class NetworksSheetView(context: Context) : SheetFrame(context, "devices.networks", "Wi-Fi") {
    /** `setWifi`, `CloseNetworks`. */
    interface Actions {
        fun setWifi(on: Boolean)
        fun close()
    }

    var actions: Actions? = null
    val networks = WifiNetworksView(context)

    init {
        scrim.setOnClickListener { actions?.close() }
        close.setOnClickListener { actions?.close() }
        switch.onChange = { actions?.setWifi(it) }
        switch.contentDescription = "Wi-Fi"
        panel.add(networks, Flex.Item(grow = 1f))
        networks.onJoining = { keyboard(it) }
    }

    fun bind(open: Boolean, list: WifiListState) {
        switch.checked = list.wifiOn
        networks.shown = open
        networks.bind(list)
        show(open)
    }
}
