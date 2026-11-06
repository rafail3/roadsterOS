package ro.rdstr.ui.media

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarEvent.Radio.Band
import ro.rdstr.car.FreqDisplay
import ro.rdstr.car.HomeCard
import ro.rdstr.car.Rds
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.GlassButton
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.RadioActionsTransport
import ro.rdstr.ui.tokens.RadioActionsTx
import ro.rdstr.ui.tokens.RadioActionsTxPlay
import ro.rdstr.ui.uiKey
import kotlin.math.roundToInt

/** A station the tuner knows: its RDS name (empty without RDS) and programme type. */
data class Station(val band: Band, val khz: Int, val ps: String, val pty: Int)

/** What the radio app shows: the store's `radio`, `stations`, `favorites`, `radioScan` and the car's source state. */
data class RadioState(
    val band: Band = Band.FM,
    val khz: Int = 87_500,
    /** The RDS name of the tuned station, empty until it arrives (and while a seek or scan passes). */
    val rds: String = "",
    val pty: Int = 0,
    val stereo: Boolean = false,
    /** The stations the last scan found, both bands; also where a frequency's name and genre are read. */
    val stations: List<Station> = emptyList(),
    val favorites: List<Int> = emptyList(),
    /** A scan running, at this frequency now. */
    val scan: Int? = null,
    /** `car.radioOn`: the radio receives its station (false while paused). */
    val radioOn: Boolean = true,
    /** `car.source === "RADIO"`. */
    val own: Boolean = false,
    val muted: Boolean = false,
)

/** The store calls the radio app makes besides commands and shell actions. */
interface RadioActions : MediaActions {
    /** store `pressSound`. */
    fun pressSound(page: CardPage, press: HomeCard.Press)
    /** store `toggleFavorite`. */
    fun toggleFavorite(khz: Int)
}

/**
 * RadioApp.tsx `RadioApp` (`section.music-app.radio-app`): the station large over its genre's colour (eased over
 * 700 ms), the frequency as a rolling counter gliding to each new tuning, the transport and a star, the tuning dial,
 * and the list of stations (favourites first) with the bands and the scan. Dark by day too.
 */
@Mirrors("RadioApp.tsx")
@SuppressLint("ViewConstructor")
class RadioAppView(context: Context, private val actions: RadioActions) : MediaAppFrame(context) {
    private var state = RadioState()
    private val genre = ColorTransition(RadioMath.genreColor(0))

    private val close = GlassButton(context, Icons.CARET_DOWN).apply {
        uiKey = "glass-button.music-close"; contentDescription = "Close radio"
        setOnClickListener { actions.shellAction(ShellAction.Home) }
    }
    private val sound = GlassButton(context, Icons.SND_FADERS).apply {
        uiKey = "glass-button.music-sound"; contentDescription = "Sound"
        setOnClickListener { actions.shellAction(ShellAction.OpenSound) }
    }
    private val main = LinearLayout(context).apply { uiKey = "radio-main"; orientation = LinearLayout.VERTICAL; clipChildren = false; gap(dp(24f)) }
    private val hero = LinearLayout(context).apply { uiKey = "radio-hero"; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false; gap(dp(8f)) }
    private val tags = LinearLayout(context).apply { uiKey = "radio-tags"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; minimumHeight = dp(28f); gap(dp(8f)); clipChildren = false }
    private val bandTag = Tag(context, band = true)
    private val stateTag = Tag(context, band = false)
    private val freqRow = FreqRow(context)
    private val station = LinearLayout(context).apply { uiKey = "radio-station"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; minimumHeight = dp(52f); gap(dp(16f)); clipChildren = false }
    private val name = cssText(context, 40f, 600, -0.01f, 48f, key = "radio-name").apply { ellipsize = TextUtils.TruncateAt.END }
    private val genrePill = Surface(context, "radio-genre").apply {
        gravity = Gravity.CENTER; radius = 17f; glassRule = Surface.GlassRule.TINTED
        setPadding(dp(16f), 0, dp(16f), 0)
    }
    private val genreText = KitText(context, TextStyle(17f, 600)).apply { includeFontPadding = false; setTextColor(0xFFFFFFFF.toInt()) }
    private val radioActions = LinearLayout(context).apply { uiKey = "radio-actions"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; gap(dp(28f)) }
    private val transport = TransportRow(context, TxSizes(RadioActionsTransport.DARK.txGap, RadioActionsTx.DARK.txSize, RadioActionsTx.DARK.txGlyph, RadioActionsTxPlay.DARK.txSize, RadioActionsTxPlay.DARK.txGlyph))
    private val heroStar = Star(context, row = false)
    private val dial = DialView(context) { preview(it) }
    private val list = Surface(context, "radio-list").apply {
        orientation = LinearLayout.VERTICAL; radius = 28f; border = 1f; glassRule = Surface.GlassRule.BASE
        fill = 0x47000000; borderColor = 0x1AFFFFFF
        roundClip(28f)
    }
    private val head = LinearLayout(context).apply { uiKey = "radio-list-head"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    private val bands = Surface(context, "radio-bands").apply { orientation = LinearLayout.HORIZONTAL; radius = 25f; fill = 0x1AFFFFFF; glassRule = Surface.GlassRule.BASE }
    private val bandKeys = listOf(Band.FM, Band.AM).map { b -> BandKey(context, b) }
    private val scan = ScanKey(context)
    private val scroll = ScrollView(context).apply { uiKey = "radio-list-scroll"; isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; clipToPadding = false }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var preview: Int? = null
    private var shownKhz = 0.0
    private val glide = object : Runnable {
        override fun run() {
            val next = FreqDisplay.glide(shownKhz, state.khz)
            shownKhz = next
            counter()
            if (next != state.khz.toDouble()) postOnAnimation(this)
        }
    }

    init {
        uiKey = "music-app.radio-app"
        contentDescription = "Radio"
        clipChildren = false
        addView(close); addView(sound)
        tags.addView(bandTag); tags.addView(stateTag)
        hero.addView(tags, LinearLayout.LayoutParams(-2, -2))
        hero.addView(freqRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        genrePill.addView(genreText)
        station.addView(name, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
        station.addView(genrePill, LinearLayout.LayoutParams(-2, dp(34f)))
        hero.addView(station, LinearLayout.LayoutParams(-2, -2))
        radioActions.addView(View(context), LinearLayout.LayoutParams(dp(56f), 0))
        radioActions.addView(transport)
        radioActions.addView(heroStar)
        hero.addView(radioActions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12f) })
        main.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))
        main.addView(dial, LinearLayout.LayoutParams(-1, dp(104f)))
        addView(main)
        for (k in bandKeys) bands.addView(k)
        bands.setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        head.addView(bands)
        head.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        head.addView(scan, LinearLayout.LayoutParams(-2, dp(44f)).apply { leftMargin = dp(12f) })
        head.setPadding(dp(16f), dp(16f), dp(16f), dp(8f))
        list.addView(head, LinearLayout.LayoutParams(-1, -2).apply { val b = UiScaleBorder.px(list); leftMargin = b; rightMargin = b; topMargin = b })
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        scroll.setPadding(dp(8f), 0, dp(8f), dp(12f))
        list.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { val b = UiScaleBorder.px(list); leftMargin = b; rightMargin = b; bottomMargin = b })
        addView(list)
        transport.prev.onPress = { actions.pressSound(CardPage.RADIO, HomeCard.Press.PREV) }
        transport.play.onPress = { actions.pressSound(CardPage.RADIO, HomeCard.Press.PLAY) }
        transport.next.onPress = { actions.pressSound(CardPage.RADIO, HomeCard.Press.NEXT) }
        heroStar.setOnClickListener { actions.toggleFavorite(state.khz) }
        scan.setOnClickListener { actions.carCommand(CarCommand.Radio.Scan) }
        for (k in bandKeys) k.setOnClickListener { if (k.band != state.band) actions.carCommand(CarCommand.Radio.Band(k.band)) }
        dial.onTune = { khz -> actions.carCommand(CarCommand.Radio.Tune(khz)) }
        dial.onSeek = { up -> actions.carCommand(CarCommand.Radio.Seek(up)) }
        for (s in listOf(bandTag, stateTag, genrePill, heroStar, list, bands, scan)) s.frost = frost
        dial.frost = frost
        for (b in transport.buttons) { b.on = Night.on; b.glass = { Night.glass } }
    }

    /** Shows [s]; [animate] false jumps to it (a still picture). */
    fun bind(s: RadioState, animate: Boolean = true) {
        val before = state
        state = s
        val run = animate && UiTheme.speed > 0f
        val color = RadioMath.genreColor(s.pty)
        if (run) genre.to(color, Motion.now(), UiTheme.duration(700), SEAMLESS) else genre.snap(color)
        if (run) postDelayed({ groundChanged() }, UiTheme.duration(700)) else groundChanged()
        val scanning = s.scan != null
        bandTag.text = s.band.name
        val tag = RadioMath.tag(scanning, s.radioOn, s.stereo)
        stateTag.visibility = if (tag != null) VISIBLE else GONE
        if (tag != null) stateTag.text = tag
        // the frequency glides to a new tuning in the same band; a band change jumps
        if (!run || before.band != s.band) { removeCallbacks(glide); shownKhz = s.khz.toDouble() }
        else if (before.khz != s.khz) { removeCallbacks(glide); postOnAnimation(glide) }
        freqRow.unit.text = RadioMath.unit(s.band)
        counter()
        if (s.rds != before.rds || name.text.isEmpty()) {
            name.text = s.rds
            if (run && s.rds.isNotEmpty()) { name.alpha = 0f; name.animate().alpha(1f).setDuration(UiTheme.duration(320)).setInterpolator(interp(SEAMLESS)).start() }
        }
        name.visibility = if (s.rds.isNotEmpty()) VISIBLE else GONE
        val g = Rds.ptyName(s.pty)
        if (g != genreText.text.toString()) {
            genreText.text = g
            if (run && g.isNotEmpty()) {
                genrePill.alpha = 0f; genrePill.scaleX = 0.8f; genrePill.scaleY = 0.8f
                genrePill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(UiTheme.duration(420)).setInterpolator(interp(Motion.springGlyph)).start()
            }
        }
        genrePill.visibility = if (g.isNotEmpty()) VISIBLE else GONE
        transport.play.playing = s.own && s.radioOn
        val favorite = s.khz in s.favorites
        heroStar.set(favorite, if (favorite) "Remove from favorites" else "Add to favorites", run)
        dial.bind(s.band, s.khz, s.stations.filter { it.band == s.band }, animate = run && before.band == s.band)
        for (k in bandKeys) k.chosen(k.band == s.band, run)
        scan.bind(s.band, s.scan)
        rebuildRows(before, run)
        invalidate()
    }

    private fun preview(khz: Int?) { preview = khz; counter() }

    private fun counter() {
        val target = preview ?: state.khz
        val shown = preview?.toDouble() ?: shownKhz
        freqRow.counter.bind(FreqDisplay.counter(state.band, shown, target))
    }

    private fun stationAt(band: Band, khz: Int) = state.stations.firstOrNull { it.band == band && it.khz == khz }

    private fun rebuildRows(before: RadioState, animate: Boolean) {
        val s = state
        val found = s.stations.filter { it.band == s.band }.map { it.khz }
        val mine = RadioMath.mine(s.band, s.favorites)
        val others = RadioMath.others(found, s.favorites)
        val scanning = s.scan != null
        val same = before.band == s.band && before.stations == s.stations && before.favorites == s.favorites && (before.scan != null) == scanning && rows.childCount > 0
        if (same) {
            for (r in allRows()) r.active(r.khz == s.khz && !scanning, s.muted)
            return
        }
        rows.removeAllViews()
        if (mine.isNotEmpty()) {
            rows.addView(section("Favorites"), sectionParams())
            rows.addView(rowList(mine, animate), LinearLayout.LayoutParams(-1, -2))
        }
        if (others.isNotEmpty() || scanning) {
            rows.addView(section(if (mine.isNotEmpty()) "Other stations" else "Stations"), sectionParams())
            rows.addView(rowList(others, animate), LinearLayout.LayoutParams(-1, -2))
        }
        if (found.isEmpty() && mine.isEmpty() && !scanning) {
            rows.addView(cssText(context, 16f, key = "radio-empty").apply {
                text = "No stations found. Scan to look again."; setTextColor(0x99FFFFFF.toInt()); isSingleLine = false; maxLines = 3
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16f), dp(24f), dp(16f), dp(24f)) })
        }
        for (r in allRows()) r.active(r.khz == s.khz && !scanning, s.muted)
    }

    private fun allRows(): List<StationRow> = (0 until rows.childCount).map(rows::getChildAt).filterIsInstance<LinearLayout>()
        .flatMap { l -> (0 until l.childCount).map(l::getChildAt).filterIsInstance<StationRow>() }

    private fun section(title: String) = cssText(context, 14f, 600, key = "radio-section").apply { text = title; setTextColor(0x8CFFFFFF.toInt()) }
    private fun sectionParams() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12f), dp(14f), dp(12f), dp(6f)) }

    private fun rowList(khz: List<Int>, animate: Boolean) = LinearLayout(context).apply {
        uiKey = "radio-rows"; orientation = LinearLayout.VERTICAL; gap(dp(2f)); clipChildren = false
        for (f in khz) {
            val st = stationAt(state.band, f)
            addView(StationRow(context, state.band, f, st?.ps.orEmpty(), st?.pty ?: 0, f in state.favorites).also { row ->
                row.setOnClickListener { actions.carCommand(CarCommand.Radio.Tune(f)) }
                row.star.setOnClickListener { actions.toggleFavorite(f) }
                row.star.frost = frost; row.frost = frost
                if (animate) {
                    row.alpha = 0f; row.translationY = dpf(8f)
                    row.animate().alpha(1f).translationY(0f).setDuration(UiTheme.duration(260)).setInterpolator(interp(Motion.easeOut)).start()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private val groundPaint = Paint()
    private var shaderColor = 0
    private var shaderH = 0

    override fun drawGround(canvas: Canvas) {
        val now = Motion.now()
        val g = genre.value(now)
        if (g != shaderColor || height != shaderH) {
            shaderColor = g; shaderH = height
            // linear-gradient(180deg, color-mix(genre 44%, #0b0c10) 0%, color-mix(genre 14%, #08090c) 58%, #060709 100%)
            val top = CssColor.lerp(0xFF0B0C10.toInt(), g, 0.44f)
            val mid = CssColor.lerp(0xFF08090C.toInt(), g, 0.14f)
            groundPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(top, mid, 0xFF060709.toInt()), floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)
            // the genre's other users: the pill and the needle's head
            genrePill.fill = CssColor.lerp(0x14FFFFFF, g, 0.6f)
            dial.genre = g
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), groundPaint)
        if (genre.running(now)) postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val d = resources.displayMetrics.density
        val cw = w / d; val ch = h / d
        for (b in listOf(close, sound)) measureAt(b, 56f, 56f)
        // grid: 1fr | 48 | 420, padding 84 32 44 112, both stretched to the row
        val rowH = ch - 84f - 44f
        measureAt(main, cw - 112f - 32f - 48f - 420f, rowH)
        measureAt(list, 420f, rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = resources.displayMetrics.density
        val cw = width / d
        place(close, 32f, 76f)
        place(sound, 32f, 144f)
        place(main, 112f, 84f)
        place(list, cw - 32f - 420f, 84f)
    }

    /** `.radio-tag`: a 28 px pill; the band's is white with black text (tinted glass), the other white at .12. */
    private class Tag(context: Context, band: Boolean) : Surface(context, "radio-tag") {
        private val label = KitText(context, TextStyle(14f, 600)).apply { includeFontPadding = false }
        var text: CharSequence = ""; set(v) { field = v; label.text = v }
        init {
            gravity = Gravity.CENTER; radius = 14f
            setPadding(dp(12f), 0, dp(12f), 0)
            minimumHeight = dp(28f)
            if (band) { fill = 0xFFFFFFFF.toInt(); glassRule = GlassRule.TINTED; label.setTextColor(0xFF000000.toInt()) }
            else { fill = 0x1FFFFFFF; glassRule = GlassRule.BASE; label.setTextColor(0xD9FFFFFF.toInt()) }
            addView(label)
            layoutParams = LinearLayout.LayoutParams(-2, dp(28f))
        }
    }
}

/** `.radio-freq`: the counter and its unit on one baseline, 14 px apart. */
internal class FreqRow(context: Context) : LinearLayout(context) {
    val counter = FreqCounterView(context)
    val unit = cssText(context, 32f, 500, key = "radio-freq-unit").apply { setTextColor(0x99FFFFFF.toInt()) }

    init {
        uiKey = "radio-freq"
        orientation = HORIZONTAL
        isBaselineAligned = true
        clipChildren = false
        addView(counter, LayoutParams(-2, -2))
        addView(unit, LayoutParams(-2, -2).apply { leftMargin = dp(14f) })
    }
}

/**
 * RadioApp.tsx `FreqCounter` (`span.counter`): core-car `FreqDisplay.counter` drawn as a mechanical counter, each
 * digit a 0-9-0 strip under a one-digit window (`clip-path: inset(0 -0.05em)`), turned by its offset; the exact
 * digits as plain text once the count settles off the FM tenths grid. 152 px, 600, -0.045em, line-height 1.
 */
internal class FreqCounterView(context: Context) : LinearLayout(context) {
    private val style = TextStyle(152f, 600, -0.045f, 1f)
    private val exact = KitText(context, style).apply { uiKey = "radio-freq-num"; includeFontPadding = false; isSingleLine = true; setTextColor(Night.ink) }
    private var columns = 0
    private var decimalAt = -1

    init {
        orientation = HORIZONTAL
        isBaselineAligned = true
        clipChildren = false
        addView(exact)
        UiTheme.link(this) { exact.setTextColor(Night.ink); for (g in groups()) g.ink() }
    }

    private fun groups() = (0 until childCount).map(::getChildAt).filterIsInstance<Group>()

    fun bind(c: FreqDisplay.Counter) {
        contentDescription = c.label
        if (c.exact != null) {
            uiKey = null
            exact.visibility = VISIBLE; exact.text = c.exact
            for (g in groups()) g.visibility = GONE
            return
        }
        uiKey = "counter.radio-freq-num"
        exact.visibility = GONE
        if (c.columns.size != columns || c.decimalAt != decimalAt) {
            columns = c.columns.size; decimalAt = c.decimalAt
            for (g in groups()) removeView(g)
            for (col in c.columns) addView(Group(context, col.place == c.decimalAt - 1 && c.decimalAt > 0))
        }
        for ((i, g) in groups().withIndex()) { g.visibility = VISIBLE; g.turn(c.columns[i].offset) }
    }

    override fun getBaseline(): Int = if (exact.visibility == VISIBLE) exact.baseline else groups().firstOrNull()?.let { it.top + it.baseline } ?: -1

    /** `.counter-group`: [point] puts the decimal point before the digit. */
    private inner class Group(context: Context, point: Boolean) : LinearLayout(context) {
        private val dot = KitText(context, style).apply { uiKey = "counter-point"; text = "."; includeFontPadding = false; isSingleLine = true }
        private val digit = Digit(context)

        init {
            uiKey = "counter-group"
            orientation = HORIZONTAL
            clipChildren = false
            if (point) addView(dot)
            addView(digit)
            ink()
        }

        fun ink() { dot.setTextColor(Night.ink); digit.ink() }
        fun turn(offset: Double) = digit.turn(offset)
        override fun getBaseline() = digit.baseline
    }

    /** `.counter-digit`: one digit's window, as wide as a hidden "0", and its strip. */
    private inner class Digit(context: Context) : FrameLayout(context) {
        private val ghost = KitText(context, style).apply { text = "0"; includeFontPadding = false; isSingleLine = true; visibility = INVISIBLE }
        private val strip = LinearLayout(context).apply { uiKey = "counter-strip"; orientation = VERTICAL }
        private val digits = (0..10).map { n -> KitText(context, style).apply { uiKey = "span"; text = "${n % 10}"; includeFontPadding = false; isSingleLine = true; gravity = Gravity.CENTER_HORIZONTAL } }
        private var offset = 0.0
        private val window = Rect()

        init {
            uiKey = "counter-digit"
            clipChildren = false
            addView(ghost, LayoutParams(-2, -2))
            for (t in digits) strip.addView(t, LinearLayout.LayoutParams(-1, -2))
            addView(strip, LayoutParams(-1, -2))
        }

        fun ink() { for (t in digits) t.setTextColor(Night.ink) }

        fun turn(o: Double) { offset = o; place() }

        private fun place() { strip.translationY = (-offset / 11.0 * strip.height).toFloat() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            ghost.measure(0, 0)
            val w = MeasureSpec.makeMeasureSpec(ghost.measuredWidth, MeasureSpec.EXACTLY)
            strip.measure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(ghost.measuredWidth, ghost.measuredHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            ghost.layout(0, 0, ghost.measuredWidth, ghost.measuredHeight)
            strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
            place()
            // clip-path: inset(0 -0.05em): the window, a little wider than the digit
            val wider = (0.05f * style.size * resources.displayMetrics.density).roundToInt()
            window.set(-wider, 0, width + wider, height)
            clipBounds = window
        }

        override fun getBaseline() = ghost.baseline
    }
}

/**
 * RadioApp.tsx `Dial` (`div.tuner`): the band's scale under a fixed needle. A finger drags it (finger left tunes up),
 * the counter previews the frequency under the needle and the tuner is tuned on release; it snaps to the band's step
 * and slides 200 ms (`--ease-out`) to each new tuning. A dot for each station found; arrows step, page keys seek.
 * The needle is centred on the frequency (the emulator's fixed form).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class DialView(context: Context, private val onPreview: (Int?) -> Unit) : Surface(context, "tuner") {
    var onTune: ((Int) -> Unit)? = null
    var onSeek: ((Boolean) -> Unit)? = null
    var genre = 0; set(v) { field = v; needle.invalidate() }
    private var dial = RadioMath.Dial(Band.FM)
    private var khz = 0
    private val strip = Strip(context)
    private val needle = Needle(context)
    private val shift = Transition(0f)
    private var dragFrom = 0
    private var dragX = 0f
    private var dragAt: Int? = null
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var maskW = 0
    private val slide = object : Runnable {
        override fun run() { strip.translationX = -dpf(shift.value(Motion.now())); if (shift.running(Motion.now())) postOnAnimation(this) }
    }

    init {
        radius = 20f; border = 1f; fill = 0x38000000; borderColor = 0x14FFFFFF
        glassRule = GlassRule.BASE
        clipChildren = true
        isClickable = true
        isFocusable = true
        contentDescription = "Tuning"
        setLayerType(LAYER_TYPE_HARDWARE, null)
        roundClip(20f)
        addView(FrameLayout(context).apply {
            clipChildren = false
            addView(strip)
            addView(needle)
        }, LayoutParams(-1, -1).apply { val b = UiScaleBorder.px(this@DialView); setMargins(b, b, b, b) })
    }

    fun bind(band: Band, khz: Int, found: List<Station>, animate: Boolean) {
        val bandChanged = dial.band != band || strip.childCount == 0
        dial = RadioMath.Dial(band)
        this.khz = khz
        strip.build(dial, found)
        contentDescription = "Tuning, ${FreqDisplay.format(band, khz)} ${RadioMath.unit(band)}"
        if (dragAt != null) return
        val to = dial.at(khz)
        if (animate && !bandChanged) shift.to(to, Motion.now(), UiTheme.duration(200), Motion.easeOut) else shift.snap(to)
        removeCallbacks(slide); slide.run()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); dragFrom = khz; dragX = e.x; dragAt = khz }
            MotionEvent.ACTION_MOVE -> dragAt?.let {
                val at = dial.dragged(dragFrom, (e.x - dragX) / resources.displayMetrics.density)
                dragAt = at
                shift.snap(dial.at(at)); slide.run()
                onPreview(at)
            }
            MotionEvent.ACTION_UP -> { val at = dragAt; dragAt = null; if (at != null && at != khz) onTune?.invoke(at); onPreview(null) }
            MotionEvent.ACTION_CANCEL -> { dragAt = null; shift.snap(dial.at(khz)); slide.run(); onPreview(null) }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val by = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_UP -> 1
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> -1
            else -> 0
        }
        if (by != 0) { onTune?.invoke(dial.stepped(khz, by)); return true }
        if (keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP || keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN) { onSeek?.invoke(keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val inner = strip.parent as View
        strip.layout(inner.width / 2, 0, inner.width / 2 + strip.measuredWidth, inner.height)
        // left: 50%; margin-left: -2px; top: 12px; bottom: 0; width: 4px
        val nl = inner.width / 2 - dp(2f)
        needle.layout(nl, dp(12f), nl + dp(4f), inner.height)
        slide.run()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        strip.measure(MeasureSpec.makeMeasureSpec(dp(dial.stripWidth), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.AT_MOST))
        needle.measure(0, 0)
    }

    /** `mask-image: linear-gradient(90deg, transparent, #000 14%, #000 86%, transparent)` over the whole dial. */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (maskW != width) {
            maskW = width
            mask.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0, -0x1000000, -0x1000000, 0), floatArrayOf(0f, 0.14f, 0.86f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
    }

    /** `.dial-strip`: the ticks (major 2 px x 30, minor 1 px x 16, from the bottom), the labels and the station dots. */
    private class Strip(context: Context) : FrameLayout(context) {
        private val paint = Paint()
        private var dial: RadioMath.Dial? = null
        private var stations: List<Station> = emptyList()

        init { uiKey = "dial-strip"; setWillNotDraw(false); clipChildren = false }

        fun build(d: RadioMath.Dial, found: List<Station>) {
            if (dial?.band == d.band && stations == found) return
            dial = d; stations = found
            removeAllViews()
            for (f in d.labels()) addView(KitText(context, TextStyle(15f, 500)).apply {
                uiKey = "dial-label"; text = d.label(f); includeFontPadding = false; isSingleLine = true; setTextColor(0x99FFFFFF.toInt())
                tag = d.at(f)
            })
            for (s in found) addView(Fill(context, "dial-station").apply { color = RadioMath.genreColor(s.pty); radius = 999f; tag = d.at(s.khz) })
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val dot = MeasureSpec.makeMeasureSpec((8 * resources.displayMetrics.density + 0.5f).toInt(), MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).let { if (it is Fill) it.measure(dot, dot) else it.measure(0, 0) }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val d = resources.displayMetrics.density
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (c.tag as Float) * d
                if (c is Fill) {
                    // top: 16px; margin-left: -4px (centred on the frequency, the emulator's fixed form)
                    val left = (x - 4 * d).roundToInt(); val top = (16 * d).roundToInt()
                    c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
                } else {
                    // top: 40px; translateX(-50%)
                    val left = x.roundToInt(); val top = (40 * d).roundToInt()
                    c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
                    c.translationX = -c.measuredWidth / 2f
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val d = dial ?: return
            val k = resources.displayMetrics.density
            val h = height.toFloat()
            // minor ticks first (the later gradient lies under the first)
            paint.color = 0x3DFFFFFF
            var x = 0f
            while (x * k < width) { canvas.drawRect(x * k, h - 16 * k, (x + 1) * k, h, paint); x += d.minor }
            paint.color = 0x99FFFFFF.toInt()
            x = d.firstMajor * d.px % d.major
            while (x * k < width) { canvas.drawRect(x * k, h - 30 * k, (x + 2) * k, h, paint); x += d.major }
        }
    }

    /** `.dial-needle`: a 4 px white bar under a 12 px head in the genre's colour ringed in white. */
    private inner class Needle(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { uiKey = "dial-needle" }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(4f), 0)
        override fun onDraw(canvas: Canvas) {
            val k = resources.displayMetrics.density
            paint.color = -1
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 2 * k, 2 * k, paint)
            // ::before: top -2px, 12 px, centred, 2 px white border
            val cx = width / 2f; val cy = -2 * k + 6 * k
            canvas.drawCircle(cx, cy, 6 * k, paint)
            paint.color = genre
            canvas.drawCircle(cx, cy, 4 * k, paint)
        }
    }
}

/** RadioApp.tsx `Star` (`button.radio-star`): yellow when starred, popping 1.3x as it turns on; 56 px, 48 in a row. */
@SuppressLint("ViewConstructor")
internal class Star(context: Context, private val row: Boolean) : Surface(context, "radio-star") {
    private var on = false
    private var glyph: IconView = icon(context, "i-star", if (row) 22f else 26f)
    private val scaleT = Transition(1f)

    init {
        gravity = Gravity.CENTER
        radius = 999f
        isClickable = true
        fill = if (row) 0 else 0x1AFFFFFF
        glassRule = if (row) GlassRule.RIM else GlassRule.BASE
        glassPress = true
        addView(glyph)
        ink()
    }

    private fun ink() { glyph.color = if (on) 0xFFFFD60A.toInt() else 0xB8FFFFFF.toInt() }

    fun set(on: Boolean, label: String, animate: Boolean) {
        contentDescription = label
        if (on == this.on) return
        this.on = on
        removeView(glyph)
        glyph = icon(context, if (on) "i-star-fill" else "i-star", if (row) 22f else 26f)
        addView(glyph)
        ink()
        if (on && animate) ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UiTheme.duration(460)
            interpolator = interp(Motion.springGlyph)
            // star-pop: 40 % { scale 1.3 }
            addUpdateListener { val t = it.animatedValue as Float; val s = if (t < 0.4f) 1f + 0.3f * t / 0.4f else 1.3f - 0.3f * (t - 0.4f) / 0.6f; glyph.scaleX = s; glyph.scaleY = s }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.makeMeasureSpec(dp(if (row) 48f else 56f), MeasureSpec.EXACTLY)
        super.onMeasure(s, s)
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        val now = Motion.now()
        scaleT.to(if (pressed) 0.88f else 1f, now, UiTheme.duration(if (pressed) 120 else 300), Motion.springGlyph)
        animate().scaleX(if (pressed) 0.88f else 1f).scaleY(if (pressed) 0.88f else 1f).setDuration(UiTheme.duration(if (pressed) 120 else 300)).setInterpolator(interp(Motion.springGlyph)).start()
    }
}

/** `.radio-bands button`: a 44 px segment, white with black text when chosen (its colour under the sheen on Glassy). */
@SuppressLint("ViewConstructor")
internal class BandKey(context: Context, val band: Band) : Surface(context, "button") {
    private val label = KitText(context, TextStyle(16f, 600)).apply { includeFontPadding = false; text = band.name }
    private val ink = ColorTransition(0xB3FFFFFF.toInt())

    init {
        gravity = Gravity.CENTER; radius = 22f; isClickable = true
        minimumWidth = dp(68f)
        setPadding(dp(16f), 0, dp(16f), 0)
        addView(label)
        layoutParams = LinearLayout.LayoutParams(-2, dp(44f))
    }

    fun chosen(on: Boolean, animate: Boolean) {
        contentDescription = band.name
        isSelected = on
        glassRule = if (on) GlassRule.CHOSEN else GlassRule.NONE
        val now = Motion.now()
        val target = if (on) 0xFF000000.toInt() else 0xB3FFFFFF.toInt()
        if (animate) ink.to(target, now, UiTheme.duration(UiTheme.base.base), Motion.easeOut) else ink.snap(target)
        fill = if (on) 0xFFFFFFFF.toInt() else 0
        label.setTextColor(target)
    }
}

/** `.radio-scan`: the magnifying glass and "Scan", or the frequency passing (the glass pulsing) while a scan runs. */
internal class ScanKey(context: Context) : Surface(context, "radio-scan") {
    private val glyph = icon(context, "i-magnifying-glass", 20f).apply { color = 0xFFFFFFFF.toInt() }
    private val label = KitText(context, TextStyle(16f, 600)).apply { includeFontPadding = false; setTextColor(0xFFFFFFFF.toInt()) }
    private var pulse: ObjectAnimator? = null

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        radius = 22f; fill = 0x1FFFFFFF; glassRule = GlassRule.BASE; glassPress = true
        isClickable = true
        minimumWidth = dp(116f)
        setPadding(dp(14f), 0, dp(18f), 0)
        addView(glyph)
        addView(label, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
    }

    fun bind(band: Band, scan: Int?) {
        isEnabled = scan == null
        label.text = if (scan != null) FreqDisplay.format(band, scan) else "Scan"
        if (scan != null && pulse == null && UiTheme.speed > 0f) {
            pulse = ObjectAnimator.ofFloat(glyph, ALPHA, 1f, 0.35f).apply {
                duration = UiTheme.duration(700); repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                interpolator = interp(Easing { t -> ro.rdstr.ui.kit.CssBezier(0.42f, 0f, 0.58f, 1f).at(t) })
                start()
            }
        } else if (scan == null) { pulse?.cancel(); pulse = null; glyph.alpha = 1f }
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        if (!isEnabled) return
        animate().scaleX(if (pressed) 0.94f else 1f).scaleY(if (pressed) 0.94f else 1f).setDuration(UiTheme.duration(if (pressed) 120 else 300)).setInterpolator(interp(Motion.springGlyph)).start()
    }
}

/**
 * RadioApp.tsx `StationRow` (`li.radio-row`): a tap tunes it; its genre's dot, or three moving bars while it plays
 * (still while muted); the name (or frequency) and the frequency with the genre; a star.
 */
@SuppressLint("ViewConstructor")
internal class StationRow(context: Context, band: Band, val khz: Int, ps: String, pty: Int, favorite: Boolean) : Surface(context, "radio-row") {
    private val tune = Surface(context, "radio-row-tune").apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; radius = 18f; pressedFill = 0x14FFFFFF
        setPadding(dp(16f), 0, dp(8f), 0)
        isDuplicateParentStateEnabled = false
    }
    private val genre = RadioMath.genreColor(pty)
    private val dot = Fill(context, "radio-row-dot").apply { color = genre; radius = 999f }
    private val live = LinearLayout(context).apply { uiKey = "radio-live"; orientation = HORIZONTAL; gravity = Gravity.BOTTOM; gap(dp(2f)) }
    private val bars = (0..2).map { Fill(context, "i").apply { color = genre; radius = 1f } }
    private val anims = ArrayList<ValueAnimator>()
    val star = Star(context, row = true)
    private val name = RadioMath.rowName(band, khz, ps)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; radius = 18f
        for (b in bars) live.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
        tune.addView(dot, LinearLayout.LayoutParams(dp(10f), dp(10f)))
        tune.addView(live, LinearLayout.LayoutParams(dp(14f), dp(14f)))
        val text = LinearLayout(context).apply { uiKey = "radio-row-text"; orientation = VERTICAL }
        text.addView(cssText(context, 19f, 600, lineHeight = 24f, key = "radio-row-name").apply { this.text = name; setTextColor(-1); ellipsize = TextUtils.TruncateAt.END })
        text.addView(cssText(context, 15f, lineHeight = 20f, key = "radio-row-sub").apply { this.text = RadioMath.rowSub(band, khz, ps, pty); setTextColor(0x99FFFFFF.toInt()); ellipsize = TextUtils.TruncateAt.END })
        tune.addView(text, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14f) })
        addView(tune, LinearLayout.LayoutParams(0, dp(64f), 1f))
        addView(star, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6f) })
        star.set(favorite, if (favorite) "Remove $name from favorites" else "Add $name to favorites", animate = false)
        tune.isClickable = true
        tune.setOnClickListener { performClick() }
    }

    fun active(on: Boolean, muted: Boolean) {
        fill = if (on) 0x24FFFFFF else 0
        glassRule = if (on) GlassRule.CHOSEN else GlassRule.NONE
        dot.visibility = if (on) GONE else VISIBLE
        live.visibility = if (on) VISIBLE else GONE
        tune.contentDescription = if (on) "$name, playing" else name
        if (on && anims.isEmpty() && UiTheme.speed > 0f) {
            // live-bar: scaleY .25 to 1 and back, 900 / 700 / 1100 ms, started -0 / -300 / -600 ms in
            for ((i, b) in bars.withIndex()) {
                val ms = longArrayOf(900, 700, 1100)[i]
                anims += ValueAnimator.ofFloat(0.25f, 1f).apply {
                    duration = UiTheme.duration(ms); repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    interpolator = interp(ro.rdstr.ui.kit.CssBezier(0.42f, 0f, 0.58f, 1f))
                    addUpdateListener { b.pivotY = b.height.toFloat(); b.scaleY = it.animatedValue as Float }
                    start()
                    currentPlayTime = UiTheme.duration(longArrayOf(0, 300, 600)[i])
                }
            }
        }
        if (!on) { anims.forEach { it.cancel() }; anims.clear(); bars.forEach { it.scaleY = 1f } }
        anims.forEach { if (muted) it.pause() else it.resume() }
    }

    override fun onDetachedFromWindow() { anims.forEach { it.cancel() }; anims.clear(); super.onDetachedFromWindow() }
}
