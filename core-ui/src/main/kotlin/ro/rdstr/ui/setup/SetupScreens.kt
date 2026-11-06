package ro.rdstr.ui.setup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import ro.rdstr.car.Design
import ro.rdstr.car.Setup
import ro.rdstr.car.ThemeMode
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.R
import ro.rdstr.ui.UiParts
import ro.rdstr.ui.WifiBars
import ro.rdstr.ui.keyboard.KeyboardView
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiScale
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.uiKey
import java.util.Locale
import kotlin.math.roundToInt

/** The Wi-Fi screen's networks: in range, any order, with the one joined, the one joining and the saved ones. */
data class SetupWifi(
    val on: Boolean = true,
    val locationOn: Boolean = true,
    val networks: List<Setup.Network> = emptyList(),
    val connected: String? = null,
    val connecting: String? = null,
    val wrongPassword: String? = null,
    val saved: List<String> = emptyList(),
    /** The scan is still finding networks: a "Searching" row closes the list. */
    val searching: Boolean = false,
)

/** Where the car is: the GPS country ("" without a fix), the unit's time zone, the country's map on the unit, the numbers' country. */
data class SetupPlace(val country: String = "", val timeZone: String = "", val mapInstalled: Boolean = true, val phoneRegion: String = "RO")

/** One of the files the setup checks or installs, with its share done while it runs. */
data class SetupFile(val name: String, val size: String, val state: Setup.TaskState, val fraction: Float = 0f)

/** Everything the setup's screens show. */
data class SetupState(
    val step: Setup.Step = Setup.Step.CAR_NAME,
    val carName: String = "",
    val prepare: Setup.Prepare = Setup.Prepare(),
    val wifi: SetupWifi = SetupWifi(),
    /** The newer version out, "" for none. */
    val release: String = "",
    val update: UpdateInstall = UpdateInstall(),
    val place: SetupPlace = SetupPlace(),
    val design: Design = Design.SOLID,
    val theme: ThemeMode = ThemeMode.DARK,
    val files: List<SetupFile> = emptyList(),
)

/**
 * The setup's screens after the start screen (emulator/components/SetupScreens.tsx): the home screen's glass and type
 * under the boot screen's tube (scanlines), its amber and its pixel captions. One frame (`.sf`): the caption and the
 * progress bar, the cube, the title and its text, the buttons, and the screen's own body; our keyboard when a field
 * is typed into. The boot and start screens are BootView's.
 */
@Mirrors("SetupScreens.tsx")
@SuppressLint("ViewConstructor")
class SetupView(context: Context, private val actions: Actions) : FrameLayout(context), UiParts {
    /** What the screens ask for, as SetupScreens.tsx dispatches it. */
    interface Actions {
        /** The setup's next screen (Screen.tsx `next`: SetupNext with Wi-Fi and the newer version). */
        fun next()
        /** SetupBack. */
        fun back()
        /** SetCarName, before [next]. */
        fun setCarName(name: String)
        /** The system preparation's failed tasks run again. */
        fun retryPrepare()
        fun wifiScan()
        fun wifiConnect(ssid: String, password: String?)
        /** "Turn on Wi-Fi" / "Turn on Location": location on, then Wi-Fi. */
        fun turnOnWifi()
        /** The newer version: download and install. */
        fun install()
        /** SetPhoneRegion. */
        fun setPhoneRegion(region: String)
        /** SetDesign. */
        fun setDesign(design: Design)
        /** SetTheme. */
        fun setTheme(mode: ThemeMode)
    }

    private var s = SetupState()
    private var bound = false
    override val uiBackground: Int? get() = SfLook.base.canvas

    private val column = SfColumn(context).apply { padTop = 32f; padSide = 56f }
    private val bar = SfBar(context)
    private val head = LinearLayout(context).apply { uiKey = "sf-head"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val title = context.sfText("sf-title", TextStyle(44f, 700, -0.02f, 1.1f))
    private val text = context.sfText("sf-text", TextStyle(20f, 400, lineHeight = 1.4f), lines = 3)
    private val buttons = LinearLayout(context).apply { uiKey = "sf-actions"; orientation = LinearLayout.HORIZONTAL }
    private val scan = Scanlines(context)
    private val keyboard = KeyboardView(context).apply { uiKey = "keyboard"; amber = true }

    private val glowPaint = Paint()
    private var glowFor = -1

    init {
        uiKey = "sf"
        contentDescription = "Setup"
        setWillNotDraw(false)
        isClickable = true
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(bar, SfColumn.Params())
        head.addView(CubeMark(context, 104f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val heading = LinearLayout(context).apply { uiKey = "sf-heading"; orientation = LinearLayout.VERTICAL }
        heading.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // p { max-width: 34em }: a block as wide as that, or the column
        text.minWidth = ipx(680f); text.maxWidth = ipx(680f)
        heading.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ipx(8f) })
        head.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ipx(28f) })
        head.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ipx(28f) })
        column.addView(head, SfColumn.Params(top = 28f))
        addView(scan, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        keyboard.setTheme(UiTheme.light, UiTheme.glassy)
        keyboard.setFont(if (UiTheme.geist) "Geist" else "Inter")
        title.setTextColor(SfLook.base.ink)
        text.setTextColor(SfLook.base.inkMuted)
        if (bound) render()
        invalidate()
    }

    // --- the screens' own state, as the components keep it
    private var name = ""
    private var typing = false
    private var joining: String? = null
    private var password = ""
    private var reveal = false

    fun bind(state: SetupState) {
        val stepChanged = !bound || state.step != s.step
        s = state
        bound = true
        if (stepChanged) {
            // the field starts empty each time the step shows; the name is kept only on Continue
            name = ""
            typing = false
            join(null)
        }
        // joined: back to the list
        if (joining != null && state.wifi.connected == joining) join(null)
        render()
    }

    private fun join(ssid: String?) {
        if (ssid != joining) { password = ""; typing = false; reveal = false }
        joining = ssid
    }

    private fun render() {
        scan(s.step == Setup.Step.WIFI && joining == null && s.wifi.on && s.wifi.locationOn && isAttachedToWindow)
        page()
    }

    private fun page() = when (s.step) {
        Setup.Step.CAR_NAME -> carName()
        Setup.Step.PREPARE -> prepare()
        Setup.Step.WIFI -> if (joining != null) password(joining!!) else wifi()
        Setup.Step.UPDATE -> update()
        Setup.Step.LOCATION -> location()
        Setup.Step.LOOK -> look()
        Setup.Step.FILES -> files()
        else -> welcome()
    }

    // --- the frame

    private fun frame(step: Setup.Step, title: String, text: String, next: () -> Unit, nextLabel: String = "Continue", canNext: Boolean = true,
                      back: Boolean = true, extra: List<Pair<String, () -> Unit>> = emptyList()) {
        val at = STEPS.indexOf(step)
        bar.caption.text = "Setup ${at + 1} of ${STEPS.size}"
        bar.progress.at = at
        this.title.text = title
        this.text.text = text
        val labels = extra.map { it.first } + (if (back && at > 0) listOf("Back") else emptyList()) + nextLabel
        while (buttons.childCount > labels.size) buttons.removeViewAt(buttons.childCount - 1)
        while (buttons.childCount < labels.size) buttons.gapBefore(SfButton(context), 12f)
        labels.forEachIndexed { i, label ->
            val b = buttons.getChildAt(i) as SfButton
            b.text = label
            val primary = i == labels.size - 1
            b.primary = primary
            b.isEnabled = !primary || canNext
            b.setOnClickListener {
                when {
                    primary -> next()
                    i < extra.size -> extra[i].second()
                    else -> actions.back()
                }
            }
        }
        if (!keyboardWanted) keyboard.open = false
        keyboardWanted = false
    }

    // --- the screen bodies: built once per screen, filled on every bind

    private var bodyKey = ""
    private val body = ArrayList<View>()

    private fun body(key: String, build: () -> Unit) {
        if (key == bodyKey) return
        body.forEach(column::removeView)
        body.clear()
        bodyKey = key
        build()
    }

    private fun add(view: View, top: Float, stretch: Boolean = true, left: Float = 0f) {
        column.addView(view, SfColumn.Params(top, left, stretch))
        body += view
    }

    private var keyboardWanted = false

    private fun keyboard(open: Boolean, symbols: Boolean, label: String, enabled: Boolean, onKey: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit) {
        keyboard.symbols = symbols
        keyboard.submitLabel = label
        keyboard.submitEnabled = enabled
        keyboard.onKey = onKey
        keyboard.onBackspace = onBackspace
        keyboard.onSubmit = onSubmit
        keyboard.open = open
        keyboardWanted = true
    }

    private lateinit var field: SfField
    private lateinit var hint: KitText

    private fun carName() {
        body("car") {
            field = SfField(context).apply { setOnClickListener { typing = true; render() } }
            add(field, 24f)
            hint = context.sfText("sf-hint", TextStyle(18f))
            add(hint, 12f, left = 4f)
        }
        val kept = Setup.carName(name)
        keyboard(typing, symbols = false, label = "Done", enabled = true,
            onKey = { c -> type(c) }, onBackspace = { name = name.dropLast(1); render() }, onSubmit = { typing = false; render() })
        frame(Setup.Step.CAR_NAME, "Name your car", "Your car's name. Phones and other devices see it when they look for the car or share their screen with it.",
            next = { if (kept != null) { actions.setCarName(kept); actions.next() } }, canNext = kept != null)
        field.value.text = name
        field.typing = typing
        field.contentDescription = if (name.isEmpty()) "Car name" else "Car name: $name"
        val short = name.isNotEmpty() && kept == null
        val letters = name.count { !it.isWhitespace() }
        hint.text = "Letters and numbers, ${Setup.CAR_NAME_MIN} to ${Setup.CAR_NAME_MAX} characters" + if (short) ", ${Setup.CAR_NAME_MIN - letters} more" else ""
        hint.setTextColor(if (short) SfLook.sf.amberCaption else SfLook.base.inkSubtle)
    }

    /** Only letters, digits and spaces reach the field; a space never starts the name or doubles one. */
    private fun type(c: Char) {
        if (!Setup.carNameChar(c) || (c == ' ' && (name.isEmpty() || name.endsWith(' ')))) return
        name = (name + c).take(Setup.CAR_NAME_MAX)
        render()
    }

    private lateinit var meter: Meter
    private lateinit var list: SfColumn

    private fun prepare() {
        body("prepare") {
            meter = Meter(context)
            add(meter, 26f)
            list = list()
            TASKS.forEach { list.addView(SfRow(context).apply { end(PixelText(context, "sf-state")) }) }
            add(list, 22f)
        }
        val p = s.prepare
        // a failed task: the one button runs the failed tasks again; Continue once all are done
        frame(Setup.Step.PREPARE, "Preparing the system", "roadsterOS takes over the unit. This runs once.",
            next = if (p.ok) actions::next else actions::retryPrepare, canNext = p.finished,
            nextLabel = if (p.finished && !p.ok) "Try again" else "Continue")
        meter.set(p.progress)
        TASKS.forEachIndexed { i, (task, label, detail) ->
            val row = list.getChildAt(i) as SfRow
            val state = p.tasks.getValue(task)
            row.state = state
            row.name.text = label
            row.detail.text = detail
            (row.getChildAt(2) as PixelText).apply { text = STATE_LABEL.getValue(state); color = stateColor(state) }
        }
    }

    private fun list() = SfColumn(context).apply { uiKey = "sf-list"; gap = 10f }

    private var scanning = false
    private val rescan = object : Runnable {
        override fun run() { actions.wifiScan(); postDelayed(this, SCAN_MS) }
    }

    /** While the list shows: a scan now and every 10 s. */
    private fun scan(on: Boolean) {
        if (on == scanning) return
        scanning = on
        removeCallbacks(rescan)
        if (on) rescan.run()
    }

    private lateinit var empty: LinearLayout
    private lateinit var emptyText: KitText
    private lateinit var emptyButton: SfButton
    private lateinit var networks: SfColumn
    private var shownNetworks = emptyList<Setup.Network>()

    private fun wifi() {
        val w = s.wifi
        val ready = w.on && w.locationOn
        if (!ready) body("wifi-off") {
            empty = LinearLayout(context).apply { uiKey = "sf-empty"; orientation = LinearLayout.VERTICAL }
            emptyText = context.sfText("p", TextStyle(22f))
            empty.addView(emptyText)
            emptyButton = SfButton(context, primary = true).apply { setOnClickListener { actions.turnOnWifi() } }
            empty.gapBefore(emptyButton, 16f)
            add(empty, 32f)
        } else body("wifi-list") {
            networks = list().apply { padBottom = 24f }
            add(MaxHeightScroll(context, 470f).apply { uiKey = "sf-list.sf-networks"; addView(networks) }, 22f)
            shownNetworks = emptyList()
        }
        val connected = w.connected != null
        frame(Setup.Step.WIFI, "Connect to Wi-Fi", "For updates and maps. You can skip it and connect later from the top bar.",
            next = actions::next, nextLabel = if (connected) "Continue" else "Skip")
        if (!ready) {
            emptyText.text = if (!w.on) "Wi-Fi is off." else "Finding networks needs Location on."
            emptyText.setTextColor(SfLook.base.inkMuted)
            emptyButton.text = if (!w.on) "Turn on Wi-Fi" else "Turn on Location"
            return
        }
        // the joined network first, then the saved ones, then the strongest, as on the unit
        val seen = Setup.networkOrder(w.networks, w.connected, w.saved.toSet())
        if (seen != shownNetworks || networks.childCount != seen.size + if (w.searching) 1 else 0) {
            shownNetworks = seen
            networks.removeAllViews()
            for (n in seen) networks.addView(FrameLayout(context).apply { uiKey = "li"; addView(networkRow(n), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) })
            if (w.searching) networks.addView(SfRow(context).apply { only(PixelText(context, "sf-state").apply { text = "Searching" }) })
        }
        seen.forEachIndexed { i, n ->
            val row = (networks.getChildAt(i) as ViewGroup).getChildAt(0) as SfRow
            val on = w.connected == n.ssid
            row.connected = on
            row.isEnabled = w.connecting == null
            row.name.text = n.ssid
            row.detail.text = when { on -> "Connected"; w.connecting == n.ssid -> "Connecting…"; n.secure -> "Secured"; else -> "Open" }
            row.setOnClickListener { if (n.secure && n.ssid !in w.saved) { join(n.ssid); render() } else actions.wifiConnect(n.ssid, null) }
            val signal = row.getChildAt(2) as ViewGroup
            for (k in 0 until signal.childCount) when (val v = signal.getChildAt(k)) {
                is IconView -> v.color = SfLook.base.inkMuted
                is WifiBars -> v.color = SfLook.base.inkMuted
            }
        }
        if (w.searching) (networks.getChildAt(seen.size) as SfRow).let { r ->
            r.state = Setup.TaskState.WAITING
            (r.getChildAt(2) as PixelText).color = stateColor(Setup.TaskState.WAITING)
        }
    }

    private fun networkRow(n: Setup.Network) = SfRow(context, pressable = true).apply {
        val signal = LinearLayout(context).apply { uiKey = "sf-signal"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (n.secure) signal.addView(IconView(context, Icons.LOCK).apply { uiKey = "icon.i-lock"; contentDescription = "Secured" })
        signal.gapBefore(WifiBars(context).apply { level = n.signal.coerceIn(0, 3) }, 10f)
        end(signal)
    }

    private lateinit var wrap: FrameLayout
    private lateinit var secret: SfField
    private lateinit var eye: EyeButton

    private fun password(ssid: String) {
        body("wifi-join") {
            wrap = FrameLayout(context).apply { uiKey = "sf-field-wrap" }
            secret = SfField(context, right = 96f).apply { setOnClickListener { typing = true; render() }; contentDescription = "Password" }
            wrap.addView(secret, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ipx(24f) })
            eye = EyeButton(context).apply { setOnClickListener { reveal = !reveal; render() } }
            // centred on the field: right 10 px, the field's 24 + 76 / 2 - 32
            wrap.addView(eye, LayoutParams(ipx(64f), ipx(64f), Gravity.END).apply { rightMargin = ipx(10f); topMargin = ipx(30f) })
            add(wrap, 0f)
        }
        val w = s.wifi
        val send = { if (password.length >= 8) actions.wifiConnect(ssid, password) }
        keyboard(typing, symbols = true, label = "Join", enabled = password.length >= 8,
            onKey = { c -> password += c; render() }, onBackspace = { password = password.dropLast(1); render() },
            onSubmit = { typing = false; render(); send() })
        frame(Setup.Step.WIFI, ssid, if (w.wrongPassword == ssid) "Incorrect password. Try again." else "Enter the password, at least 8 characters.",
            next = send, nextLabel = if (w.connecting == ssid) "Connecting…" else "Join", canNext = password.length >= 8 && w.connecting != ssid,
            back = false, extra = listOf("Cancel" to { join(null); render() }))
        secret.value.text = if (reveal) password else "•".repeat(password.length)
        secret.typing = typing
        eye.reveal = reveal
    }

    private lateinit var download: LinearLayout
    private lateinit var downloadMeter: Meter
    private lateinit var figures: KitText

    private fun update() {
        body("update") {
            download = LinearLayout(context).apply { uiKey = "sf-download"; orientation = LinearLayout.VERTICAL }
            downloadMeter = Meter(context)
            download.addView(downloadMeter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ipx(26f) })
            figures = context.sfText("sf-figures", TextStyle(22f))
            download.addView(figures, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ipx(14f) })
            add(download, 8f)
        }
        val u = s.update
        val d = u.download
        frame(Setup.Step.UPDATE, "roadsterOS ${s.release} is out", "Install it now, or later from Settings > About.",
            next = { if (d == null) actions.install() }, nextLabel = if (d == null) "Install now" else if (u.done) "Installed" else "Installing…",
            canNext = d == null, back = d == null, extra = if (d == null) listOf("Later" to actions::next) else emptyList())
        download.visibility = if (d == null) GONE else VISIBLE
        if (d == null) return
        downloadMeter.set(d.fraction, d.percent)
        figures.setTextColor(SfLook.base.inkMuted)
        figures.text = if (!u.done) UpdateInstall.figures(d) else SpannableStringBuilder("Installed. Restarting in ").apply {
            val from = length
            append("${u.restartIn} s")
            setSpan(ForegroundColorSpan(SfLook.sf.amber), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(Weight(700), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private lateinit var places: SfColumn

    private fun location() {
        body("location") {
            places = list()
            repeat(3) { places.addView(SfRow(context).apply { state = Setup.TaskState.DONE; end(PixelText(context, "sf-state").apply { text = "Found" }) }) }
            add(places, 22f)
        }
        val p = s.place
        val here = p.country.ifEmpty { "RO" }
        val rows = listOf(
            "Map" to if (p.mapInstalled) "${region(here)}, on the unit" else "${region(here)}, downloads on Wi-Fi",
            "Time zone" to p.timeZone.substringAfterLast('/').replace('_', ' '),
            "Phone numbers" to region(p.phoneRegion),
        )
        frame(Setup.Step.LOCATION, "Where you drive", "Found from the GPS. Each one can be changed later in Settings.", next = {
            if (p.country.isNotEmpty() && p.country != p.phoneRegion) actions.setPhoneRegion(p.country)
            actions.next()
        })
        rows.forEachIndexed { i, (name, value) ->
            val row = places.getChildAt(i) as SfRow
            row.state = Setup.TaskState.DONE
            row.name.text = name
            row.detail.text = value
            (row.getChildAt(2) as PixelText).color = stateColor(Setup.TaskState.DONE)
        }
    }

    private val looks = ArrayList<SfLookCard>()
    private val segments = ArrayList<SfButton>()

    private fun look() {
        body("look") {
            looks.clear(); segments.clear()
            val grid = LinearLayout(context).apply { uiKey = "sf-looks"; orientation = LinearLayout.HORIZONTAL }
            for ((id, label, detail) in DESIGNS) {
                val card = SfLookCard(context, if (id == Design.SOLID) R.drawable.rdstr_setup_look_solid else R.drawable.rdstr_setup_look_glassy)
                card.name.text = label
                card.detail.text = detail
                card.setOnClickListener { actions.setDesign(id) }
                looks += card
                grid.gapBefore(card, 20f, ipx(460f))
            }
            add(grid, 22f)
            val seg = BoxLinear(context).apply {
                uiKey = "sf-segments"
                orientation = LinearLayout.HORIZONTAL
                box.radius = 36f; box.border = 1f
                pad(6f, 6f)
            }
            for ((mode, label) in THEMES) {
                val b = SfButton(context, segment = true).apply { text = label; setOnClickListener { actions.setTheme(mode) } }
                segments += b
                seg.gapBefore(b, 6f)
            }
            add(seg, 18f, stretch = false)
        }
        frame(Setup.Step.LOOK, "Choose the look", "Auto follows the headlights: dark at night, light by day.", next = actions::next)
        DESIGNS.forEachIndexed { i, (id) -> looks[i].on = s.design == id }
        THEMES.forEachIndexed { i, (mode) -> segments[i].primary = s.theme == mode }
        (segments.firstOrNull()?.parent as? BoxLinear)?.box?.let { it.fill = SfLook.base.glass; it.borderColor = SfLook.base.hairline }
    }

    private lateinit var fileRows: SfColumn

    private fun files() {
        body("files") {
            fileRows = list()
            add(fileRows, 22f)
        }
        frame(Setup.Step.FILES, "Installing files", "Everything the car needs offline. Other countries' maps come when you drive there.",
            next = actions::next, canNext = s.files.isNotEmpty() && s.files.all { it.state == Setup.TaskState.DONE })
        while (fileRows.childCount > s.files.size) fileRows.removeViewAt(fileRows.childCount - 1)
        while (fileRows.childCount < s.files.size) fileRows.addView(SfRow(context))
        s.files.forEachIndexed { i, f ->
            val row = fileRows.getChildAt(i) as SfRow
            row.state = f.state
            row.name.text = f.name
            row.detail.text = f.size
            val running = f.state == Setup.TaskState.RUNNING
            val end = row.getChildAt(2).takeIf { row.childCount > 2 }
            if (running) {
                val dots = end as? DotBar ?: DotBar(context).also { row.end(it) }
                dots.fraction = f.fraction
            } else {
                val label = end as? PixelText ?: PixelText(context, "sf-state").also { row.end(it) }
                label.text = if (f.state == Setup.TaskState.DONE) "Done" else "Waiting"
                label.color = stateColor(f.state)
            }
        }
    }

    private fun welcome() {
        body("welcome") {}
        frame(Setup.Step.WELCOME, if (s.carName.isNotEmpty()) "${s.carName} is ready" else "The car is ready",
            "Everything is set. Settings holds it all if you want to change something.", next = actions::next, nextLabel = "Start driving")
    }

    private fun region(code: String) = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bound && s.step == Setup.Step.WIFI && joining == null) scan(s.wifi.on && s.wifi.locationOn)
    }

    override fun onDetachedFromWindow() {
        scan(false)
        super.onDetachedFromWindow()
    }

    // --- the frame's own drawing: the canvas with the amber glow at the top left

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(SfLook.base.canvas)
        if (glowFor != width) {
            glowFor = width
            // radial-gradient(900px 520px at 12% 0%, glow, transparent 70%): an ellipse, the colour kept as it fades
            val cx = 0.12f * width
            val rx = 900f * d
            glowPaint.shader = RadialGradient(cx, 0f, rx, intArrayOf(SfLook.GLOW, SfLook.GLOW and 0xFFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(1f, 520f / 900f, cx, 0f) })
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
    }

    /** `.sf-bar`: the caption at the left and the progress at the right of a 50 px box with a 2.5 px `--amber-line` edge, 26 px in. */
    private class SfBar(context: Context) : ViewGroup(context) {
        val caption = PixelText(context, "sf-caption").apply { color = AMBER.amberCaption }
        val progress = SfProgress(context)
        private val box = CssBox().apply { border = 2.5f; borderColor = AMBER.amberLine }

        init {
            uiKey = "sf-bar"
            setWillNotDraw(false)
            addView(caption)
            addView(progress)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureChildren(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ipx(50f))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val edge = UiScale.borderPx(2.5f, d)
            val inset = edge + lu(26f * d)
            val fraction = (layoutParams as? SfColumn.Params)?.fractionX ?: 0f
            // align-items: center; justify-content: space-between, on the unrounded positions
            fun place(v: View, x: Float, fx: Boolean) {
                val y = lu((height - lu(v.measuredHeight.toFloat())) / 2f)
                val left = (fraction + x).roundToInt()
                val top = y.roundToInt()
                if (fx && v is DotGrid) { v.fractionX = fraction + x - left; v.fractionY = y - top }
                v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
            }
            place(caption, inset, false)
            place(progress, width - inset - progressWidth(), true)
        }

        /** The progress bar's unrounded width. */
        private fun progressWidth(): Float = 8 * (6 * lu(4f * d) + 5 * lu(1.6f * d)) + 7 * lu(14f * d)

        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())

        private companion object { val AMBER = ro.rdstr.ui.tokens.Sf.DARK }
    }

    /** `.sf-meter`: a dot bar and its percent, 20 px apart, centred. */
    private class Meter(context: Context) : LinearLayout(context) {
        val dots = DotBar(context)
        val percent = PixelText(context, "sf-caption").apply { color = ro.rdstr.ui.tokens.Sf.DARK.amberCaption }

        init {
            uiKey = "sf-meter"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dots)
            gapBefore(percent, 20f)
        }

        fun set(fraction: Float, percent: Int = (fraction * 100).roundToInt()) {
            dots.fraction = fraction
            this.percent.text = "$percent%"
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            (layoutParams as? SfColumn.Params)?.let { dots.fractionX = it.fractionX; dots.fractionY = it.fractionY }
        }
    }

    /** `.sf-scanlines` (`.sf::after`): one unit pixel dark in every three, over the whole frame. */
    private class Scanlines(context: Context) : View(context) {
        private val paint = Paint().apply {
            val tile = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, SfLook.SCANLINE) }
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        override fun onDraw(canvas: Canvas) = canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /** `.sf-reveal`: the password's eye, 64 px round, `--ink-muted`, `--glass-pressed` while pressed. */
    private class EyeButton(context: Context) : FrameLayout(context) {
        private val icon = IconView(context, Icons.EYE, 30f).apply { uiKey = "icon.i-eye" }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var reveal = false
            set(v) {
                field = v
                icon.setIcon(if (v) Icons.EYE_SLASH else Icons.EYE)
                icon.uiKey = if (v) "icon.i-eye-slash" else "icon.i-eye"
                contentDescription = if (v) "Hide the password" else "Show the password"
                theme()
            }

        init {
            uiKey = "sf-reveal"
            isClickable = true
            setWillNotDraw(false)
            addView(icon, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            UiTheme.link(this) { theme() }
            theme()
        }

        private fun theme() { icon.color = SfLook.base.inkMuted; invalidate() }

        override fun dispatchSetPressed(pressed: Boolean) { super.dispatchSetPressed(pressed); invalidate() }

        override fun onDraw(canvas: Canvas) {
            if (!isPressed) return
            paint.color = SfLook.base.glassPressed
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        }
    }

    /** A ScrollView no taller than [max] canvas px (`max-height`, `overflow-y: auto`). */
    private class MaxHeightScroll(context: Context, private val max: Float) : ScrollView(context) {
        init { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(ipx(max), MeasureSpec.AT_MOST))
    }

    /** `<b>`'s weight on a variable font. */
    private class Weight(private val weight: Int) : MetricAffectingSpan() {
        override fun updateMeasureState(p: TextPaint) { p.fontVariationSettings = "'wght' $weight" }
        override fun updateDrawState(p: TextPaint) { p.fontVariationSettings = "'wght' $weight" }
    }

    private companion object {
        /** The screens the progress bar counts, in order. */
        val STEPS = listOf(Setup.Step.CAR_NAME, Setup.Step.PREPARE, Setup.Step.WIFI, Setup.Step.UPDATE, Setup.Step.LOCATION, Setup.Step.LOOK, Setup.Step.FILES, Setup.Step.WELCOME)
        val TASKS = listOf(
            Triple(Setup.Task.KEYS, "System keys", "The firmware trusts roadsterOS with the system's own permissions"),
            Triple(Setup.Task.PACKAGES, "Packages", "The apps the car does not need are switched off"),
            Triple(Setup.Task.PERMISSIONS, "Permissions", "Location, Bluetooth, the phone and the clock"),
            Triple(Setup.Task.HOME, "Home screen", "roadsterOS opens whenever the car starts"),
        )
        val STATE_LABEL = mapOf(
            Setup.TaskState.WAITING to "Waiting", Setup.TaskState.RUNNING to "Working", Setup.TaskState.DONE to "Done",
            Setup.TaskState.FAILED to "Failed", Setup.TaskState.SKIPPED to "Skipped",
        )
        val DESIGNS = listOf(
            Triple(Design.SOLID, "Solid", "Flat panels, the clearest at a glance"),
            Triple(Design.GLASSY, "Glassy", "Frosted glass over the background"),
        )
        val THEMES = listOf(ThemeMode.AUTO to "Auto", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
        /** While the Wi-Fi list shows, a scan this often. */
        const val SCAN_MS = 10_000L
    }
}

/**
 * `.sf-look`: one of the two designs, its picture of the home screen (`--sheet-raised` while it loads, 12 px round),
 * its name and a line about it; 460 px wide, 14 px in (18 below), 22 px round, a 2 px edge, amber when chosen.
 */
@SuppressLint("ViewConstructor")
class SfLookCard(context: Context, picture: Int) : BoxLinear(context) {
    val name = context.sfText("sf-row-name", TextStyle(22f, 600))
    val detail = context.sfText("sf-row-detail", TextStyle(17f))
    private val image = LookPicture(context, picture)
    var on = false; set(v) { field = v; theme() }

    init {
        uiKey = "sf-look"
        orientation = VERTICAL
        isClickable = true
        box.radius = 22f
        box.border = 2f
        pad(14f, 14f, 18f)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ipx(8f + 6f) })
        gapBefore(detail, 6f)
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        val b = SfLook.base
        box.fill = b.glass
        box.borderColor = if (on) SfLook.sf.amber else b.hairline
        name.setTextColor(b.ink)
        detail.setTextColor(b.inkMuted)
        image.theme()
        invalidate()
    }

    /** `img.sf-look-picture`: the picture stretched over a 1280:750 box, 12 px round. */
    private class LookPicture(context: Context, private val res: Int) : View(context), UiParts {
        private val box = CssBox().apply { radius = 12f }
        override val uiBackground: Int? get() = box.fill

        init { uiKey = "sf-look-picture" }

        fun theme() { box.fill = SfLook.base.sheetRaised; invalidate() }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // half the picture's 1024 px is still more than the 428 px it shows at
            if (box.image == null) box.image = android.graphics.BitmapFactory.decodeResource(resources, res, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
        }

        override fun onDetachedFromWindow() { box.image?.recycle(); box.image = null; super.onDetachedFromWindow() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * 750f / 1280f).roundToInt())
        }

        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
    }
}
