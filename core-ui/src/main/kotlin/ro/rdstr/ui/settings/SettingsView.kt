package ro.rdstr.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import ro.rdstr.car.AnimationSpeed
import ro.rdstr.car.AodFace
import ro.rdstr.car.CarCommand
import ro.rdstr.car.DashClock
import ro.rdstr.car.Design
import ro.rdstr.car.FaceMode
import ro.rdstr.car.Guidelines
import ro.rdstr.car.HomeApps
import ro.rdstr.car.LaunchableApp
import ro.rdstr.car.MapPacks
import ro.rdstr.car.PhoneFormat
import ro.rdstr.car.ReverseDip
import ro.rdstr.car.Rules
import ro.rdstr.car.RulesState
import ro.rdstr.car.Settings
import ro.rdstr.car.Shell
import ro.rdstr.car.ShellAction
import ro.rdstr.car.ShellState
import ro.rdstr.car.ThemeMode
import ro.rdstr.car.TimeSource
import ro.rdstr.car.TimeZones
import ro.rdstr.car.CarEvent
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.R
import ro.rdstr.ui.keyboard.KeyboardView
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Gap
import ro.rdstr.ui.kit.GlassButton
import ro.rdstr.ui.kit.KitPillSlider
import ro.rdstr.ui.kit.KitSegmented
import ro.rdstr.ui.kit.KitSwitch
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.SettingsGroup
import ro.rdstr.ui.kit.SettingsRow
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import java.util.Locale

/**
 * The settings app (SettingsApp.tsx `SettingsApp`, `.music-app.settings-app`): the sections on the left (340 px), the
 * chosen one on the right with its name on a pill, on the app's own ground (`--sheet`), 76 px 64 px 24 px 128 px in;
 * the round close button at (32, 76). The section shown is the shell's `settingsPage` (OpenSettingsPage), About for one
 * not listed, and it is the one highlighted in the list.
 *
 * Laid out over the whole 1280 x 750 canvas. [bind] takes the shell's state, the rules' state and [SettingsState];
 * everything it triggers goes to [actions]. Each page is built again from the state on each bind, its views kept by
 * key so a switch or a slider keeps its motion and its finger (as React keeps elements); a page's own state (a
 * picker open, a confirmation asked) lives here and starts over when the section changes, as the detail remounts.
 *
 * [open] runs the app's way in (rising from the bottom in 420 ms on `--sheet-in`) and out (its rectangle closing onto
 * [closeTo], the home card, in 280 ms while it fades, its content gone in 100 ms).
 */
@Mirrors("SettingsApp.tsx")
@SuppressLint("ViewConstructor")
class SettingsView(context: Context, private val slots: SettingsSlots? = null) : FrameLayout(context) {
    var actions: SettingsActions? = null

    private var shell = ShellState()
    private var rules = RulesState()
    private var s = SettingsState()

    private val keep = Keep(context)
    private val close = GlassButton(context, Icons.CARET_DOWN).apply { uiKey = "glass-button.music-close"; contentDescription = "Close settings" }
    private val nav = LinearLayout(context)
    private val navTitle = text(context, "set-title", TextStyle(34f, 700, -0.02f)).apply { text = "Settings" }
    private val navList = LinearLayout(context)
    private val navScroll = ScrollView(context)
    private val items = Section.entries.associateWith { NavItem(context, it) }
    private val detail = ScrollView(context)
    private val content = ro.rdstr.ui.kit.CssColumn(context, 28f)
    private val pill = TitlePill(context)
    private val keyboard = KeyboardView(context)
    private val networks by lazy { slots?.wifiNetworks(context) }
    private val unitPicture by lazy { slots?.unitPicture(context, 300f) }

    // the page's own state (useState in each page), reset with the page
    private var page: String? = null
    private var picking = false
    private var part = SettingsLogic.DatePart.DAY
    private var query = ""
    private var typing = false
    private var confirmPack: String? = null
    private var confirmReset = false
    private var updating = false
    private var opened = false
    private var erasing = false
    private var zones: List<Pair<String, String>>? = null
    private var regions: List<Triple<String, String, Int>>? = null
    private var stickyHead: View? = null

    init {
        uiKey = "music-app.settings-app"
        contentDescription = "Settings"
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d + 0.5f).toInt()
        isClickable = true
        addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = px(32f); topMargin = px(76f) })
        close.setOnClickListener { actions?.shell(ShellAction.Home) }

        nav.uiKey = "set-nav"
        nav.orientation = LinearLayout.VERTICAL
        nav.dividerDrawable = Gap(px(16f)); nav.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        nav.addView(navTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f); topMargin = px(4f) })
        navScroll.uiKey = "ul"
        navScroll.isVerticalScrollBarEnabled = false
        navScroll.overScrollMode = OVER_SCROLL_NEVER
        navList.orientation = LinearLayout.VERTICAL
        navList.dividerDrawable = Gap(px(4f)); navList.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        navScroll.addView(navList)
        nav.addView(navScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        for ((section, item) in items) item.setOnClickListener { actions?.shell(ShellAction.OpenSettingsPage(section.id)) }
        addView(nav, LayoutParams(px(340f), px(650f)).apply { leftMargin = px(128f); topMargin = px(76f) })

        detail.uiKey = "set-detail"
        detail.isVerticalScrollBarEnabled = false
        detail.overScrollMode = OVER_SCROLL_NEVER
        detail.clipToPadding = false
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(4f), 0, px(4f), px(24f))
        content.clipChildren = false
        detail.addView(content)
        detail.setOnScrollChangeListener { _, _, _, _, _ -> stick() }
        addView(detail, LayoutParams(px(700f), px(650f)).apply { leftMargin = px(516f); topMargin = px(76f) })

        keyboard.submitLabel = "Search"
        keyboard.onKey = { c -> query += c; render() }
        keyboard.onBackspace = { query = query.dropLast(1); render() }
        keyboard.onSubmit = { typing = false; render() }
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // .set-page-title { align-self: flex-start; margin: 4px 0 -8px }
        pill.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f); bottomMargin = -px(8f) }
        UiTheme.link(this) { setBackgroundColor(UiTheme.settings.sheet); navTitle.setTextColor(UiTheme.settings.ink) }
        setBackgroundColor(UiTheme.settings.sheet)
        navTitle.setTextColor(UiTheme.settings.ink)
        visibility = INVISIBLE
    }

    fun bind(shell: ShellState, rules: RulesState, state: SettingsState) {
        this.shell = shell; this.rules = rules; s = state
        render()
    }

    // ---- open and close (music.css .music-app) ----

    /** Where the app closes onto (the home card), in this view's px; null: `inset(20% 2% 2% 72%)`. */
    var closeTo: RectF? = null
    private val rise = Transition(0f)
    private val shut = Transition(0f)
    private val fade = Transition(1f)
    private val clip = Path()
    private val clipRect = RectF()

    var open = false
        set(v) {
            if (field == v) return
            field = v
            val now = Motion.now()
            if (v) {
                visibility = VISIBLE
                shut.snap(0f); fade.snap(1f)
                rise.snap(1f); rise.to(0f, now, UiTheme.duration(420), Motion.sheetIn)
                for (i in 0 until childCount) getChildAt(i).fadeTo(1f, 100, Motion.easeOut)
            } else {
                shut.to(1f, now, UiTheme.duration(280), Motion.sheetIn)
                fade.to(0f, now, UiTheme.duration(280), ro.rdstr.ui.kit.CssBezier(0.4f, 0f, 1f, 1f))
                for (i in 0 until childCount) getChildAt(i).fadeTo(0f, 100, Motion.easeOut)
                postDelayed({ if (!this.open) visibility = INVISIBLE }, UiTheme.duration(280))
            }
            invalidate()
        }

    override fun draw(canvas: Canvas) {
        val now = Motion.now()
        val k = shut.value(now)
        val w = width.toFloat(); val h = height.toFloat()
        alpha = fade.value(now)
        canvas.save()
        canvas.translate(0f, rise.value(now) * h)
        if (k > 0f) {
            val to = closeTo ?: RectF(0.72f * w, 0.2f * h, 0.98f * w, 0.98f * h)
            clipRect.set(to.left * k, to.top * k, w + (to.right - w) * k, h + (to.bottom - h) * k)
            val r = UiTheme.base.radius * resources.displayMetrics.density * k
            clip.reset(); clip.addRoundRect(clipRect, r, r, Path.Direction.CW)
            canvas.clipPath(clip)
        }
        super.draw(canvas)
        canvas.restore()
        if (rise.running(now) || shut.running(now) || fade.running(now)) postInvalidateOnAnimation()
    }

    // ---- render ----

    private fun render() {
        val sections = SettingsLogic.sections(shell)
        val shown = SettingsLogic.shown(shell)
        // the list: Developer only while it is on; the shown section is the current one
        navList.children(sections.map { sec ->
            items.getValue(sec).also { item ->
                item.current = sec == shown
                item.value.setIfChanged(SettingsLogic.summary(sec, s, rules.btConnected, shell))
                item.value.visibility = if (item.value.text.isEmpty()) GONE else VISIBLE
            }.let { li(it) }
        })
        // the detail remounts per page (key={page}): its own state starts over and it fades in
        if (shell.settingsPage != page) {
            page = shell.settingsPage
            picking = false; part = SettingsLogic.DatePart.DAY; query = ""; typing = false; confirmPack = null
            confirmReset = false; updating = false; opened = false; erasing = false; zones = null; regions = null; drag = null
            keep.clear()
            detail.scrollTo(0, 0)
            content.alpha = 0f
            content.fadeTo(1f, 200, Motion.seamless)
        }
        pill.title.setIfChanged(shown.label)
        val out = arrayListOf<View>(pill)
        stickyHead = null
        val b = Page(out)
        when (shown) {
            Section.WIFI -> b.wifi()
            Section.BLUETOOTH -> b.bluetooth()
            Section.LOCATION -> b.location()
            Section.DISPLAY -> b.display()
            Section.DATETIME -> if (picking) b.zonePicker() else b.dateTime()
            Section.SOUND -> b.sound()
            Section.PHONE -> if (picking) b.regionPicker() else b.phone()
            Section.DASH -> b.carDisplay()
            Section.CAMERA -> b.camera()
            Section.HOME -> b.homeScreen()
            Section.MAPS -> b.maps()
            Section.ABOUT -> if (updating) b.softwareUpdate() else b.about()
            Section.DEVELOPER -> b.developer()
        }
        content.children(out)
        keep.sweep()
        keyboard.open = typing
        post { stick() }
    }

    private val lis = HashMap<NavItem, FrameLayout>()
    private fun li(item: NavItem) = lis.getOrPut(item) { FrameLayout(context).apply { uiKey = "li"; addView(item) } }

    /** The picker's head stays at the top of the detail while the list scrolls (`position: sticky; top: 0`). */
    private fun stick() {
        val head = stickyHead ?: return
        val top = (head.parent as View).top + head.top
        head.translationY = maxOf(0, detail.scrollY - top).toFloat()
    }

    private fun android.widget.TextView.setIfChanged(t: CharSequence) { if (text.toString() != t.toString()) text = t }

    // ---- the builder ----

    /** One page's views in order, kept by key across renders. */
    private inner class Page(val out: MutableList<View>) {
        fun group(key: String, title: String? = null, footer: String? = null, rows: Rows.() -> Unit) {
            val g = keep.get("g:$key") { SettingsGroup(it) }
            if (g.title?.toString() != title) g.title = title
            if (g.footer?.toString() != footer) g.footer = footer
            val r = Rows(key).apply(rows)
            g.rows.children(r.list)
            g.rows.visibility = if (r.list.isEmpty()) GONE else VISIBLE
            out += g
        }

        fun view(key: String, make: (Context) -> View) { out += keep.get(key, make) }
    }

    private inner class Rows(val g: String) {
        val list = ArrayList<View>()

        fun row(key: String, label: CharSequence, value: CharSequence? = null, onPress: (() -> Unit)? = null, danger: Boolean = false): SettingsRow {
            val r = keep.get("$g/$key") { SettingsRow(it, label) }
            if (r.label.text.toString() != label.toString()) r.label.text = label
            if (r.value?.toString() != value?.toString()) r.value = value
            if (r.danger != danger) r.danger = danger
            r.onPress = onPress
            list += r
            return r
        }

        fun switch(key: String, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
            val sw = keep.get("$g/$key.switch") { KitSwitch(it).apply { uiKey = "switch" } }
            row(key, label).also { if (sw.parent == null) it.addControl(sw) }
            sw.checked = on; sw.onChange = onChange; sw.contentDescription = label
        }

        fun <T> seg(key: String, label: String, rowLabel: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
            val seg = keep.get("$g/$key.seg") { KitSegmented(it).apply { uiKey = "set-seg"; labels = options.map { o -> o.second } } }
            row(key, rowLabel).also { if (seg.parent == null) it.addControl(seg) }
            seg.selected = options.indexOfFirst { it.first == value }
            seg.onChange = { i -> onChange(options[i].first) }
            seg.contentDescription = label
        }

        fun slider(key: String, label: String, min: Int, max: Int, value: Int, readout: String, icon: String, muted: Boolean = false, onValue: (Int) -> Unit) {
            val sl = keep.get("$g/$key.slider") { KitPillSlider(it).apply { uiKey = "pill-slider" } }
            val r = keep.get("$g/$key") { SettingsRow(it, "", slider = true).apply { addControl(sl, fill = true) } }
            sl.min = min; sl.max = max; sl.value = value; sl.readout = readout; sl.muted = muted
            if (sl.tag != icon) { sl.tag = icon; sl.setIcon(iconRes(icon)) }
            sl.onValue = onValue
            sl.contentDescription = label
            list += r
        }

        fun inline(key: String, label: CharSequence, button: String, kind: InlineButton.Kind = InlineButton.Kind.PLAIN, enabled: Boolean = true, description: String? = null, onClick: () -> Unit): SettingsRow {
            val bt = keep.get("$g/$key.inline") { InlineButton(it, button) }
            val r = row(key, label).also { if (bt.parent == null) { it.addControl(bt); it.clipChildren = false } }
            if (bt.label.text.toString() != button) bt.label.text = button
            bt.kind = kind; bt.isEnabled = enabled; bt.onClick = onClick; bt.contentDescription = description
            return r
        }

        fun add(view: View) { list += view }
    }

    // ---- pages ----

    private fun Page.wifi() {
        group("wifi", footer = if (!s.locationOn && s.wifiOn) "Finding networks needs Location on." else null) {
            switch("wifi", "Wi-Fi", s.wifiOn) { actions?.setWifi(it) }
        }
        if (s.wifiOn) view("networks") { c ->
            LinearLayout(c).apply {
                uiKey = "set-networks"
                orientation = LinearLayout.VERTICAL
                networks?.let { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
            }
        }
    }

    private fun Page.bluetooth() {
        group("bt", footer = "Phones connect for music and calls. A tablet or a laptop plays music only.") {
            switch("bt", "Bluetooth", s.btOn) { actions?.setBluetooth(it) }
            if (s.btOn) row("connected", "Connected", if (rules.btConnected) s.device else "None")
            if (s.btOn) row("devices", "Devices", "${s.savedDevices} saved", onPress = { actions?.shell(ShellAction.OpenDevices) })
        }
        group("auto", footer = "When the car starts or Bluetooth comes on, the unit connects to the phone connected last. Off, you pick the phone each time.") {
            switch("auto", "Connect to Last Phone", shell.btAutoConnect) { actions?.shell(ShellAction.SetBtAutoConnect(it)) }
        }
    }

    private fun Page.location() {
        group("location", footer = "Maps, the speed limits and the Wi-Fi search need the unit's location. It stays on the unit.") {
            switch("location", "Location", s.locationOn) { actions?.setLocation(it) }
        }
    }

    private fun Page.display() {
        group("theme", "Theme", "Auto is light from 05:00 to 19:00 and dark otherwise; with the headlights on, the screen dims a little.") {
            seg("theme", "Appearance", "Appearance", listOf(ThemeMode.AUTO to "Auto", ThemeMode.LIGHT to "Day", ThemeMode.DARK to "Night"), shell.theme) {
                actions?.shell(ShellAction.SetTheme(it))
            }
        }
        group("design", "Design", if (shell.design == Design.GLASSY) "Frosted glass panels and liquid controls. The layout, the icons and the colours stay as they are."
            else "Flat, solid panels. Glassy gives every panel and control a glass look.") {
            seg("design", "Design", "Design", listOf(Design.SOLID to "Solid", Design.GLASSY to "Glassy"), shell.design) { actions?.shell(ShellAction.SetDesign(it)) }
        }
        group("brightness", "Brightness") {
            slider("brightness", "Brightness", Shell.MIN_BRIGHTNESS, Shell.MAX_BRIGHTNESS, shell.brightness, "${shell.brightness}%", "sun-fill") {
                actions?.shell(ShellAction.SetBrightness(it))
            }
        }
        group("clock", "Clock", if (shell.faceMode == FaceMode.BOTH) "A tap on the clock on home switches between the two faces." else null) {
            seg("face", "Clock face", "Face", listOf(FaceMode.BOTH to "Both", FaceMode.DIGITAL to "Digital", FaceMode.ANALOG to "Analog"), shell.faceMode) {
                actions?.shell(ShellAction.SetFaceMode(it))
            }
        }
        group("aod", "Always On Display", if (shell.aodEnabled)
            "Left alone ${shell.aodAfterMin} min, the screen goes black with only the clock and the date. A touch, a key, a call or reverse gear brings it back; it waits during a call, the camera and the map."
            else "The screen stays as it is when left alone.") {
            switch("aod", "Always On Display", shell.aodEnabled) { actions?.shell(ShellAction.SetAod(it)) }
            if (shell.aodEnabled) {
                seg("after", "Always on display after", "After", Shell.AOD_MINUTES.map { it to "$it min" }, shell.aodAfterMin) { actions?.shell(ShellAction.SetAodAfter(it)) }
                seg("face", "Always on display clock", "Clock", listOf(AodFace.HOME to "As Home", AodFace.DIGITAL to "Digital", AodFace.ANALOG to "Analog"), shell.aodFace) {
                    actions?.shell(ShellAction.SetAodFace(it))
                }
            }
        }
    }

    private fun clock() = SettingsLogic.clock(s.now, s.timeZone)
    private fun clockText(h: Int, m: Int, h24: Boolean) = Settings.clockText(h, m, h24)

    private fun Page.dateTime() {
        val c = clock()
        val h24 = shell.clock24h
        group("now") {
            if (shell.autoTime) row("today", "Today", SettingsLogic.longDate(c))
            else {
                // tap the day, the month or the year, then step it
                val r = row("today.manual", "Today")
                val date = keep.get("now/date") { DateParts(it) }
                val step = keep.get("now/date.step") { Stepper(it, "minus", "plus", withValue = false) }
                if (date.parent == null) { r.addControl(date); r.addControl(step) }
                date.show(c, part) { part = it; render() }
                step.minus.contentDescription = "Earlier ${part.id}"; step.plus.contentDescription = "Later ${part.id}"
                step.minus.onClick = { actions?.shiftClock(SettingsLogic.dateStep(s.now, s.timeZone, part, -1)) }
                step.plus.onClick = { actions?.shiftClock(SettingsLogic.dateStep(s.now, s.timeZone, part, 1)) }
            }
            if (shell.autoTime) row("time", "Time", clockText(c.hour, c.minute, h24) + if (h24) "" else " ${Settings.meridiem(c.hour)}")
            else {
                val r = row("time.manual", "Time")
                val hour = keep.get("now/hour") { Stepper(it, "minus", "plus", withValue = true) }
                val minute = keep.get("now/minute") { Stepper(it, "minus", "plus", withValue = true) }
                if (hour.parent == null) {
                    r.addControl(hour); r.addControl(minute)
                    // .set-stepper + .set-stepper { margin-left: 8px }, inside the row's 16 px gap
                    (minute.layoutParams as LinearLayout.LayoutParams).leftMargin = minute.dp(8f)
                }
                hour.output.setIfChanged(clockText(c.hour, 0, h24).substringBefore(':'))
                minute.output.setIfChanged(SettingsLogic.pad(c.minute))
                for ((st, what, ms) in listOf(Triple(hour, "hour", SettingsLogic.HOUR), Triple(minute, "minute", SettingsLogic.MINUTE))) {
                    st.minus.contentDescription = "Earlier $what"; st.plus.contentDescription = "Later $what"
                    st.output.contentDescription = what
                    st.minus.onClick = { actions?.shiftClock(-ms) }
                    st.plus.onClick = { actions?.shiftClock(ms) }
                }
            }
        }
        group("h24") { switch("h24", "24-Hour Time", h24) { actions?.shell(ShellAction.SetClock24h(it)) } }
        group("sync", footer = SettingsLogic.syncFooter(shell.autoTime, shell.timeSource)) {
            switch("auto", "Set Automatically", shell.autoTime) { actions?.setTimeSync(it, shell.timeSource) }
            if (shell.autoTime) seg("source", "Sync with", "Sync With", listOf(TimeSource.NETWORK to "Internet", TimeSource.GPS to "GPS"), shell.timeSource) {
                actions?.setTimeSync(true, it)
            }
        }
        group("zone") {
            val offset = TimeZones.offset(actions?.zoneOffsetMinutes(s.timeZone) ?: 0)
            row("zone", "Time Zone", "${TimeZones.city(s.timeZone)}, $offset", onPress = { picking = true; render() })
        }
    }

    /** A picker: the back button and the search over a group of rows with a check on the current one. */
    private fun Page.picker(back: String, emptyLabel: String, placeholder: String, title: String, footer: String?, rows: Rows.() -> Unit) {
        val zonesBox = keep.get("picker") { c ->
            LinearLayout(c).apply {
                uiKey = "set-zones"
                orientation = LinearLayout.VERTICAL
                dividerDrawable = Gap(dp(16f)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            }
        }
        val head = keep.get("picker/head") { c ->
            LinearLayout(c).apply {
                uiKey = "set-zones-head"
                orientation = LinearLayout.VERTICAL
                dividerDrawable = Gap(dp(12f)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                setPadding(0, dp(4f), 0, dp(4f))
                outlineProvider = null
                translationZ = 2f
            }
        }
        head.setBackgroundColor(UiTheme.settings.sheet)
        val backButton = keep.get("picker/back") { c -> BackButton(c, back).apply { onClick = { picking = false; query = ""; typing = false; render() } } }
        val search = keep.get("picker/search") { c -> SearchBar(c, placeholder, emptyLabel) }
        search.onField = { typing = true; render() }
        search.onCancel = { query = ""; typing = false; render() }
        search.show(query, typing)
        head.children(listOf(backButton, search))
        (backButton.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; leftMargin = -head.dp(8f) }
        val list = keep.get("picker/list") { SettingsGroup(it) }
        list.title = title
        if (list.footer?.toString() != footer) list.footer = footer
        list.rows.children(Rows("picker").apply(rows).list)
        zonesBox.children(listOf(head, list))
        zonesBox.setPadding(0, 0, 0, if (typing) zonesBox.dp(340f) else 0)
        stickyHead = head
        out += zonesBox
    }

    /** A picker's row: the name (and a second line), the value and a check that keeps its place when hidden. */
    private fun Rows.pick(key: String, label: String, sub: String?, value: String, current: Boolean, onPick: () -> Unit) {
        val r = keep.get("$g/$key") { c ->
            SettingsRow(c, label).apply {
                if (sub != null) {
                    removeView(this.label)
                    val col = LinearLayout(c).apply { uiKey = "set-row-label"; orientation = LinearLayout.VERTICAL }
                    this.label.uiKey = null
                    col.addView(this.label)
                    col.addView(text(c, "set-row-sub", TextStyle(15f)).apply { text = sub; setTextColor(UiTheme.settings.inkMuted) },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })
                    addView(col, 0, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                this.value = value
                addControl(icon(c, "check", 22f).apply { uiKey = "icon.i-check.set-check" })
            }
        }
        val check = r.getChildAt(r.childCount - 2) as ro.rdstr.ui.kit.IconView
        check.color = UiTheme.settings.setBlue
        check.visibility = if (current) VISIBLE else INVISIBLE
        // a list to pick from: pressable, no chevron
        r.onPress = onPick
        r.getChildAt(r.childCount - 1).visibility = GONE
        list += r
    }

    private fun Page.zonePicker() {
        val all = zones ?: actions?.timeZones().orEmpty().let { ids -> TimeZones.places(ids).map { it to TimeZones.offset(actions?.zoneOffsetMinutes(it) ?: 0) } }.also { zones = it }
        val found = TimeZones.search(all.map { it.first }, query).toHashSet()
        val shown = all.filter { it.first in found }
        // ponytail: every matching zone is a row (about 400 without a query), as the browser lists them; a recycled list if the unit finds it slow
        picker("Date & Time", "Search time zones", "Search a city", "Time Zone", if (shown.isNotEmpty()) "${shown.size} zones" else null) {
            for ((id, offset) in shown) pick(id, TimeZones.city(id), TimeZones.region(id), offset, id == s.timeZone) {
                actions?.setTimeZone(id); picking = false; query = ""; typing = false; render()
            }
            if (shown.isEmpty()) row("none", "No time zone matches “$query”")
        }
    }

    private fun regionName(code: String) = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }

    private fun Page.regionPicker() {
        val all = regions ?: PhoneFormat.regions().map { Triple(it, regionName(it), PhoneFormat.callingCode(it) ?: 0) }.sortedBy { it.second }.also { regions = it }
        val shown = all.filter { SettingsLogic.regionMatches(it.second, it.first, it.third, query) }
        picker("Phone", "Search countries", "Search a country or +code", "Number Country", if (shown.isNotEmpty()) "${shown.size} countries" else null) {
            for ((id, name, code) in shown) pick(id, name, null, "+$code", id == shell.phoneRegion) {
                actions?.shell(ShellAction.SetPhoneRegion(id)); picking = false; query = ""; typing = false; render()
            }
            if (shown.isEmpty()) row("none", "No country matches “$query”")
        }
    }

    private fun Page.carDisplay() {
        val c = clock()
        val unit = clockText(c.hour, c.minute, true)
        fun press(key: DashClock.Key) = actions?.car(CarCommand.DashKey(key))
        group("switch", footer = "Switches the car's small display between the clock and the instant fuel consumption.") {
            inline("switch", "Clock or Fuel", "Switch") { press(DashClock.Key.CLOCK) }
        }
        if (shell.dashMatched) group("matched", "Clock", "Changes of the unit's time, its zone and summer time go to the display. Match again after the car's battery was disconnected, or when the display has drifted.") {
            row("follows", "Follows the Unit's Clock", unit)
            row("again", "Match Again", onPress = { actions?.shell(ShellAction.SetDashMatched(false)) })
        } else group("match", "Match the Clock", "The display only counts up. Step it until it shows the unit's time, then tap Done; from then on it follows the unit's clock.") {
            row("unit", "Unit Time", unit)
            inline("hour", "Display Hour", "+1 Hour") { press(DashClock.Key.HOUR) }
            inline("minute", "Display Minute", "+1 Minute") { press(DashClock.Key.MINUTE) }
            inline("done", "The Display Shows This Time", "Done") { actions?.shell(ShellAction.SetDashMatched(true)) }
        }
    }

    private fun Page.sound() {
        val muted = rules.muted
        val dsp = shell.dsp
        group("volume", "Volume") {
            slider("volume", "Volume", 0, Rules.MAX_VOLUME, if (muted) 0 else rules.volume, if (muted) "Muted" else "${rules.volume}",
                if (muted) "speaker-slash-fill" else "speaker-high-fill", muted) { actions?.setVolume(it) }
        }
        group("effects", footer = "Equalizer, balance, the listening position, loudness, bass and the speakers' filters.") {
            row("effects", "Sound Effects", onPress = { actions?.shell(ShellAction.OpenSound) })
        }
        group("calls", "Calls", "How loud the other side of a call is in the car, for this drive: each start of the unit goes back to 20. Music keeps its own level.") {
            slider("call", "Call volume", 0, 30, dsp.callVolume, "${dsp.callVolume}", "phone-fill") { actions?.setSound(shell.dsp.copy(callVolume = it)) }
        }
        group("radio", "Radio", "Powers an amplified antenna through the antenna lead. Off for a passive antenna.") {
            switch("antenna", "Antenna Power", dsp.antennaPower) { actions?.setSound(shell.dsp.copy(antennaPower = it)) }
        }
        val limit = shell.startVolume > 0
        group("start", footer = SettingsLogic.startVolumeFooter(shell.startVolume)) {
            switch("limit", "Limit Start Volume", limit) { actions?.shell(ShellAction.SetStartVolume(if (it) SettingsLogic.START_VOLUME_DEFAULT else 0)) }
            if (limit) {
                val r = row("level", "Start Volume")
                val st = keep.get("start/level.step") { Stepper(it, "minus", "plus", withValue = true) }
                if (st.parent == null) r.addControl(st)
                st.output.setIfChanged("${shell.startVolume}")
                st.output.contentDescription = "Start volume"
                st.minus.contentDescription = "Lower start volume"; st.plus.contentDescription = "Raise start volume"
                st.minus.isEnabled = shell.startVolume > 1; st.plus.isEnabled = shell.startVolume < Rules.MAX_VOLUME
                st.minus.onClick = { actions?.shell(ShellAction.SetStartVolume(maxOf(1, shell.startVolume - 1))) }
                st.plus.onClick = { actions?.shell(ShellAction.SetStartVolume(maxOf(1, shell.startVolume + 1))) }
            }
        }
    }

    private fun Page.camera() {
        val camera = shell.camera
        fun put(next: ro.rdstr.car.CameraSettings) = actions?.setCamera(next)
        group("picture", "Picture", "Flips the picture left to right. Turn it on when things on the screen sit on the other side than in the car's mirrors.") {
            switch("mirror", "Mirror Image", camera.mirror) { put(shell.camera.copy(mirror = it)) }
        }
        group("guides", "Guidelines", SettingsLogic.GUIDE_FOOTERS.getValue(camera.guidelines)) {
            seg("lines", "Guidelines", "Lines", listOf(Guidelines.OFF to "Off", Guidelines.FIXED to "Fixed", Guidelines.STEERING to "Steering"), camera.guidelines) {
                put(shell.camera.copy(guidelines = it))
            }
            if (camera.guidelines != Guidelines.OFF) {
                val preview = keep.get("guides/preview.view") { CameraPreview(it) }
                val r = keep.get("guides/preview") { c ->
                    SettingsRow(c, "").apply {
                        removeView(label)
                        setPadding(paddingLeft, dp(12f), paddingRight, dp(12f))
                        addControl(preview, fill = true)
                    }
                }
                preview.camera = camera; preview.eps = s.eps
                add(r)
                val h = row("height", "Height")
                val st = keep.get("guides/height.step") { Stepper(it, "caret-down", "caret-up", withValue = true) }
                if (st.parent == null) h.addControl(st)
                st.output.setIfChanged(SettingsLogic.lineHeight(camera.linePos))
                st.output.contentDescription = "Guideline height"
                st.minus.contentDescription = "Move the lines down"; st.plus.contentDescription = "Move the lines up"
                st.minus.isEnabled = camera.linePos < SettingsLogic.LINE_POS_MAX; st.plus.isEnabled = camera.linePos > SettingsLogic.LINE_POS_MIN
                st.minus.onClick = { put(shell.camera.copy(linePos = minOf(SettingsLogic.LINE_POS_MAX, shell.camera.linePos + SettingsLogic.LINE_POS_STEP))) }
                st.plus.onClick = { put(shell.camera.copy(linePos = maxOf(SettingsLogic.LINE_POS_MIN, shell.camera.linePos - SettingsLogic.LINE_POS_STEP))) }
            }
        }
        group("music", "Music", "While reverse gear is in, music and the radio play quieter; they go back to their level when it comes out.") {
            seg("dip", "Lower the music while reversing", "Lower the Music",
                listOf(ReverseDip.NONE to "Off", ReverseDip.WEAK to "A Little", ReverseDip.MEDIUM to "Medium", ReverseDip.STRONG to "A Lot"), camera.dip) {
                put(shell.camera.copy(dip = it))
            }
        }
    }

    private fun Page.phone() {
        val region = shell.phoneRegion
        group("numbers", "Numbers", "Numbers written without + are grouped as in ${regionName(region)}. Calls dial the digits as they are.") {
            row("country", "Number Country", "${regionName(region)} (+${PhoneFormat.callingCode(region) ?: 0})", onPress = { picking = true; render() })
        }
        group("messages", "Messages", "With this on, a message can be typed only while the car stands. Quick replies and calls work either way.") {
            switch("lock", "Block Typing While Driving", shell.lockTypingWhileMoving) { actions?.shell(ShellAction.SetTypingLock(it)) }
        }
        group("cast", "Phone Screen", if (shell.castVisible)
            "While the car is on, phones find the unit as ${CarEvent.PhoneCast.NAME} in Smart View or Screen Mirroring. Sharing pauses the music and the radio; they play again after."
            else "Phones cannot find the unit to share their screen.") {
            switch("visible", "Visible to Phones", shell.castVisible) { actions?.setCastVisible(it) }
        }
    }

    private fun Page.maps() {
        val dsp = shell.dsp
        group("voice", "Voice Guidance", "The spoken directions of a navigation app, through the unit. ${SettingsLogic.musicUnderNav(dsp.musicUnderNav)}") {
            slider("nav", "Voice guidance volume", 0, 100, dsp.navVolume, "${dsp.navVolume}%", "navigation-arrow-fill") { actions?.setSound(shell.dsp.copy(navVolume = it)) }
            val r = row("under", "Music Under the Voice")
            val st = keep.get("voice/under.step") { Stepper(it, "minus", "plus", withValue = true, minValueWidth = 64f).apply { output.uiKey = "output.set-mix" } }
            if (st.parent == null) r.addControl(st)
            st.output.setIfChanged(if (dsp.musicUnderNav == 0) "Off" else "${dsp.musicUnderNav}%")
            st.output.contentDescription = "Music under the navigation voice"
            st.minus.contentDescription = "Quieter music under the navigation voice"; st.plus.contentDescription = "Louder music under the navigation voice"
            st.minus.isEnabled = dsp.musicUnderNav > 0; st.plus.isEnabled = dsp.musicUnderNav < 100
            st.minus.onClick = { actions?.setSound(shell.dsp.copy(musicUnderNav = maxOf(0, shell.dsp.musicUnderNav - 10))) }
            st.plus.onClick = { actions?.setSound(shell.dsp.copy(musicUnderNav = minOf(100, shell.dsp.musicUnderNav + 10))) }
        }
        group("chime", footer = "Two short notes when the car goes over the speed limit. The limit sign and the red dial stay either way.") {
            switch("chime", "Speed Limit Chime", shell.speedChime) { actions?.shell(ShellAction.SetSpeedChime(it)) }
        }
        group("sign", footer = "The road's limit beside the speed on the map. The ring around the speed still turns orange near it and red over it.") {
            switch("sign", "Speed Limit Sign", shell.speedLimitSign) { actions?.shell(ShellAction.SetSpeedLimitSign(it)) }
        }
        group("packs", "Offline Maps", "Another country's map is offered when the car enters it, and downloads over Wi-Fi.") {
            for (code in s.packs) {
                val name = regionName(code)
                val mb = MapPacks.PACK_MB[code] ?: 0
                val r = if (confirmPack == code) inline("pack:$code", name, "Remove", InlineButton.Kind.DANGER) { actions?.removePack(code); confirmPack = null; render() }
                else inline("pack:$code", name, "Remove…", description = "Remove the $name map") { confirmPack = code; render() }
                r.value = "${if (mb > 0) mb else "?"} MB"
            }
            s.downloading?.let { row("downloading", regionName(it.code), "Downloading ${Math.round(it.progress * 100)}%") }
            if (s.packs.isEmpty() && s.downloading == null) row("none", "No maps on the unit")
        }
    }

    // the Home Screen reorder: the row held by its handle, how far it went, and the rows' height
    private class Drag(val id: String, val from: Int, var dy: Float, val h: Float, val startY: Float)
    private var drag: Drag? = null

    private fun Page.homeScreen() {
        val chosen = HomeApps.shown(shell.homeApps, s.installed)
        val all = HomeApps.OWN + s.installed
        val others = all.filter { it.id !in shell.homeApps }
        val full = chosen.size >= HomeApps.MAX
        group("on", "On Home", "Up to ${HomeApps.MAX} apps, in this order beside the card. Drag to reorder.") {
            chosen.forEachIndexed { i, a -> add(reorderRow(a, i, chosen)) }
            if (chosen.isEmpty()) row("none", "No apps on home")
        }
        group("more", "More Apps", if (full) "Home is full: remove an app to add another." else "roadsterOS's own apps and the apps installed on the unit.") {
            for (a in others) {
                val r = keep.get("more/${a.id}") { c ->
                    SettingsRow(c, a.label).apply {
                        removeView(label)
                        addView(appName(c, a), 0, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addControl(InlineButton(c, "Add").apply { tag = "add" })
                        clipChildren = false
                    }
                }
                val add = r.findViewWithTag<InlineButton>("add")
                add.isEnabled = !full
                add.onClick = { actions?.shell(ShellAction.ToggleHomeApp(a.id)) }
                add(r)
            }
        }
    }

    private fun appName(c: Context, a: LaunchableApp): View = LinearLayout(c).apply {
        uiKey = "set-row-label"
        gravity = Gravity.CENTER_VERTICAL
        addView(AppName(c, a.id, a.label, OWN_TILES[a.id]))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun reorderRow(a: LaunchableApp, i: Int, chosen: List<LaunchableApp>): View {
        val r = keep.get("on/${a.id}") { c ->
            SettingsRow(c, a.label).apply {
                uiKey = "set-row.set-reorder"
                removeView(label)
                addView(appName(c, a), 0, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addControl(InlineButton(c, "Remove").apply { kind = InlineButton.Kind.REMOVE; tag = "remove" })
                val grip = FrameLayout(c).apply {
                    uiKey = "set-grip"; tag = "grip"
                    addView(icon(c, "list", 26f), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }
                addControl(grip)
                (grip.layoutParams as LinearLayout.LayoutParams).apply { width = dp(56f); height = dp(56f); rightMargin = -dp(12f) }
                clipChildren = false
            }
        }
        r.findViewWithTag<InlineButton>("remove").onClick = { actions?.shell(ShellAction.ToggleHomeApp(a.id)) }
        val grip = r.findViewWithTag<FrameLayout>("grip")
        grip.contentDescription = "Reorder ${a.label}, place ${i + 1} of ${chosen.size}"
        (grip.getChildAt(0) as ro.rdstr.ui.kit.IconView).color = if (drag?.id == a.id) UiTheme.settings.ink else CssColor.withAlpha(UiTheme.base.on, 0.42f)
        grip.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    drag = Drag(a.id, i, 0f, r.height.toFloat(), e.rawY)
                    lift(r, true)
                    shiftRows(chosen)
                }
                MotionEvent.ACTION_MOVE -> drag?.let { it.dy = e.rawY - it.startY; shiftRows(chosen) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag?.let { dr ->
                    val to = SettingsLogic.dropIndex(dr.from, dr.dy, dr.h, chosen.size)
                    drag = null
                    lift(r, false)
                    // ponytail: the rows land at once on drop; the browser's 220 ms settle of the moved row is left out
                    for (a2 in chosen) keep.peek("on/${a2.id}")?.apply { animate().cancel(); translationY = 0f; tag = null }
                    if (e.actionMasked == MotionEvent.ACTION_UP && to != dr.from) actions?.shell(ShellAction.MoveHomeApp(dr.id, to - dr.from))
                    render()
                }
            }
            true
        }
        return r
    }

    /** The dragged row lifts on `--panel-row` with its shadow over the others (`.set-reorder.is-dragged`). */
    private fun lift(row: View, on: Boolean) {
        row.translationZ = if (on) 1f else 0f
        row.outlineProvider = null
        row.background = if (!on) null else Plate(row, 12f).apply {
            box.shadows = listOf(Shadow(false, 0f, 6f, 16f, 0f, 0x2E000000))
            look(UiTheme.base.panelRow)
        }
        val grip = row.findViewWithTag<FrameLayout>("grip")
        (grip.getChildAt(0) as ro.rdstr.ui.kit.IconView).color = if (on) UiTheme.settings.ink else CssColor.withAlpha(UiTheme.base.on, 0.42f)
    }

    private fun shiftRows(chosen: List<LaunchableApp>) {
        val dr = drag ?: return
        val to = SettingsLogic.dropIndex(dr.from, dr.dy, dr.h, chosen.size)
        chosen.forEachIndexed { i, a ->
            val v = keep.peek("on/${a.id}") ?: return@forEachIndexed
            val y = SettingsLogic.dragShift(i, dr.from, to, dr.dy, dr.h)
            if (i == dr.from) { v.animate().cancel(); v.translationY = y }
            else if (v.tag != y) { v.tag = y; v.animate().translationY(y).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(Motion.seamless)) }
        }
    }

    private fun Page.about() {
        unitPicture?.let { pic ->
            view("about/hero") { c ->
                FrameLayout(c).apply {
                    addView(pic, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END))
                    // div.about-hero: right-aligned, margins -14px 4px -16px 0
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = -dp(14f); rightMargin = dp(4f); bottomMargin = -dp(16f)
                    }
                }
            }
        }
        val a = s.about
        group("unit") {
            row("car", "Car", shell.carName.ifEmpty { "Not named" })
            row("version", "roadsterOS", a.version, onPress = { updating = true; render() })
            row("cast", "Screen Sharing", CarEvent.PhoneCast.RECEIVER)
            row("android", "Android", a.android)
            row("platform", "Platform", a.model)
            row("screen", "Screen", a.screen)
        }
        group("memory", "Memory", "What roadsterOS itself uses, and what the unit has free for navigation and other apps.") {
            row("app", "roadsterOS", a.appMemory)
            row("free", "Free", "${a.freeMemory} of ${a.totalMemory}")
        }
        group("storage", "Storage") { row("free", "Free", "${a.freeStorage} of ${a.totalStorage}") }
        group("journal", "Troubleshooting", if (shell.recordEvents) "Recording: ${s.recorded} lines so far. Read the journal with adb."
            else "Writes car events, commands, screen actions and errors to the unit, one file a day; older days are compressed and the journal never passes 10 MB.") {
            switch("record", "Record Car Events", shell.recordEvents) { actions?.shell(ShellAction.SetRecording(it)) }
        }
        group("developer", footer = "Shows the Developer section after About: adb, touches and the frame rate on screen, the animation speed.") {
            switch("developer", "Developer Settings", shell.developer) { actions?.shell(ShellAction.SetDeveloper(it)) }
        }
        group("restart", footer = "Starts the whole unit again, as switching the car off and on does.") {
            row("restart", "Restart the Unit…", onPress = { actions?.askRestart(true) })
        }
        group("reset", footer = if (confirmReset) "Theme, brightness, clock and sound settings go back to their defaults. Phones, networks and maps stay." else null) {
            if (confirmReset) row("confirm", "Reset All Settings", danger = true, onPress = { actions?.shell(ShellAction.ResetSettings); confirmReset = false; render() })
            else row("ask", "Reset Settings…", onPress = { confirmReset = true; render() })
        }
    }

    private fun Page.softwareUpdate() {
        val u = s.update
        val version = s.about.version
        val wifi = s.wifiConnected != null
        out += keep.get("update/back") { c -> BackButton(c, "About").apply { onClick = { updating = false; render() } } }.also { b ->
            // .set-back in the detail: margin -12px 0 -12px -8px
            b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = -b.dp(12f); bottomMargin = -b.dp(12f); leftMargin = -b.dp(8f)
            }
        }
        out += keep.get("update/title") { c ->
            text(c, "set-sub-title", TextStyle(22f, 700)).apply {
                text = "Software Update"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f); bottomMargin = -dp(10f) }
            }
        }.also { (it as android.widget.TextView).setTextColor(UiTheme.settings.ink) }
        group("version") {
            row("version", "Version", "roadsterOS $version")
            row("installed", "Installed", SettingsLogic.updateDate(u.installedAt, s.timeZone))
        }
        val download = u.download
        val done = u.restartIn != null
        when {
            download != null || done -> group("download", footer = if (done) "Installed. The unit restarts in ${u.restartIn} s." else SettingsLogic.downloadFigures(download!!)) {
                val r = keep.get("download/progress") { ProgressRow(it) }
                r.show(if (done) "roadsterOS ${u.releaseOut.orEmpty()} installed" else "Downloading roadsterOS ${u.releaseOut.orEmpty()}",
                    "${download?.percent ?: 100}%", download?.fraction ?: 1f)
                add(r)
            }
            u.check == Update.Check.AVAILABLE && u.releaseOut != null -> group("available", footer = "Downloads over Wi-Fi and installs while the car is parked; the unit restarts once it is in.") {
                // the release APK's size, as the download bar writes it
                row("release", "roadsterOS ${u.releaseOut}", ro.rdstr.car.Updater.sizeText(u.releaseBytes))
                row("install", "Download and Install", onPress = { actions?.installUpdate() })
            }
            else -> group("check", footer = SettingsLogic.checkFooter(wifi, u, s.timeZone)) {
                if (u.check == Update.Check.LATEST) row("latest", "roadsterOS is up to date", version)
                if (wifi) row("check", if (u.check == Update.Check.CHECKING) "Checking…" else "Check for Updates",
                    onPress = if (u.check == Update.Check.CHECKING) null else ({ actions?.checkForUpdate() }))
                else row("wifi", "Connect to Wi-Fi", onPress = { actions?.shell(ShellAction.OpenNetworks) })
            }
        }
    }

    private fun Page.developer() {
        val address = s.adbAddress
        group("debugging", "Debugging", when {
            !shell.adbEnabled -> "adb cannot reach the unit until this is on again."
            address != null -> "From a laptop on the same Wi-Fi: adb connect $address"
            else -> "Connect the unit to Wi-Fi to reach it with adb."
        }) {
            switch("adb", "USB Debugging", shell.adbEnabled) { actions?.shell(ShellAction.SetAdb(it)) }
            if (shell.adbEnabled) row("address", "Address", address ?: "Not on Wi-Fi")
        }
        group("screen", "Screen", "On roadsterOS's own screens: a dot under each finger and the frames drawn each second. Fast runs every animation twice as fast, Off skips them.") {
            switch("touches", "Show Touches", shell.showTouches) { actions?.shell(ShellAction.SetShowTouches(it)) }
            switch("fps", "Show Frame Rate", shell.showFrameRate) { actions?.shell(ShellAction.SetShowFrameRate(it)) }
            seg("speed", "Animation speed", "Animations", listOf(AnimationSpeed.NORMAL to "Normal", AnimationSpeed.FAST to "Fast", AnimationSpeed.OFF to "Off"), shell.animationSpeed) {
                actions?.shell(ShellAction.SetAnimationSpeed(it))
            }
        }
        group("system", "System", if (opened) "On the unit this opens Android's own developer options over roadsterOS; the emulator has no Android behind it, so it only logs the request."
            else "Android's own developer options hold every other setting of the unit.") {
            row("android", "Android Developer Options", onPress = { actions?.openAndroidSettings(); opened = true; render() })
        }
        group("erase", footer = if (erasing) "Erases everything roadsterOS keeps: every setting, the journal, map packs, the phones paired with the unit and the saved Wi-Fi networks. The unit then restarts into setup." else null) {
            if (erasing) {
                row("erase", "Erase Everything", danger = true, onPress = { actions?.factoryReset() })
                row("cancel", "Cancel", onPress = { erasing = false; render() })
            } else row("ask", "Factory Reset…", onPress = { erasing = true; render() })
        }
    }

    /** Date & Time by hand: the weekday, then the day, the month and the year to pick one to step (`.set-date`). */
    private class DateParts(context: Context) : LinearLayout(context) {
        private val weekday = text(context, "set-date-weekday", TextStyle(18f))
        private val parts = SettingsLogic.DatePart.entries.map { p ->
            text(context, "set-date-part", TextStyle(18f)).apply {
                gravity = Gravity.CENTER; minHeight = dp(44f); setPadding(dp(10f), 0, dp(10f), 0); isClickable = true; tag = p
            }
        }

        init {
            uiKey = "set-date"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            dividerDrawable = Gap(dp(2f)); showDividers = SHOW_DIVIDER_MIDDLE
            addView(weekday, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
            parts.forEach { addView(it) }
            contentDescription = "Part of the date to change"
        }

        fun show(c: SettingsLogic.Clock, part: SettingsLogic.DatePart, onPick: (SettingsLogic.DatePart) -> Unit) {
            weekday.text = "${c.weekday},"
            weekday.setTextColor(UiTheme.settings.inkMuted)
            for (t in parts) {
                val p = t.tag as SettingsLogic.DatePart
                val v = when (p) { SettingsLogic.DatePart.DAY -> "${c.day}"; SettingsLogic.DatePart.MONTH -> c.monthName; SettingsLogic.DatePart.YEAR -> "${c.year}" }
                t.text = v
                t.contentDescription = "${p.id}, $v"
                t.setTextColor(UiTheme.settings.ink)
                t.background = if (p == part) Plate(t, 10f).apply { look(0x520A84FF) } else null
                t.setOnClickListener { onPick(p) }
            }
        }
    }

    /** Software Update's download row (`.set-update-progress`): the label and the share, the bar on a line of its own. */
    private class ProgressRow(context: Context) : LinearLayout(context) {
        private val label = text(context, "set-row-label", TextStyle(19f, 500))
        private val value = text(context, "set-row-value", TextStyle(18f))
        private val bar = ProgressBar(context)

        init {
            uiKey = "set-row.set-update-progress"
            orientation = VERTICAL
            setPadding(dp(20f), 0, dp(20f), dp(18f))
            minimumHeight = dp(68f)
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                dividerDrawable = Gap(dp(16f)); showDividers = SHOW_DIVIDER_MIDDLE
                addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); addView(value)
            }
            // a 68 px row of two lines and 18 px below: the 6 px left over goes half to each line (align-content: normal)
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1.5f); bottomMargin = dp(1.5f) })
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(8f)).apply { topMargin = dp(13.5f); bottomMargin = dp(1.5f) })
            contentDescription = null
        }

        fun show(text: String, percent: String, fraction: Float) {
            label.text = text; value.text = percent; bar.fraction = fraction
            label.setTextColor(UiTheme.settings.ink); value.setTextColor(UiTheme.settings.inkMuted)
        }
    }

    private companion object {
        /** Our apps' own tiles (`app-mark-*`), the emulator's pictures. */
        val OWN_TILES = mapOf(
            "MAPS" to R.drawable.rdstr_app_maps, "MUSIC" to R.drawable.rdstr_app_music, "PHONE" to R.drawable.rdstr_app_phone,
            "RADIO" to R.drawable.rdstr_app_radio, "SETTINGS" to R.drawable.rdstr_app_settings,
        )
    }
}
