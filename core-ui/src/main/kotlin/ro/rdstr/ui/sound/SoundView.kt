package ro.rdstr.ui.sound

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import ro.rdstr.car.ListeningSeat
import ro.rdstr.car.Sound
import ro.rdstr.car.SoundSettings
import ro.rdstr.car.SpeakerFilter
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.GlassButton
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitSegmented
import ro.rdstr.ui.kit.KitSwitch
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.SettingsGroup
import ro.rdstr.ui.kit.SettingsRow
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.kit.px
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

import kotlin.math.roundToInt

private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT

/** What the sound window asks of the car; the display app's runtime implements it. */
interface SoundActions {
    /** A changed setting (`store.setSound`): kept with the shell's, and only the processor's values that moved are written. */
    fun setSound(next: SoundSettings)

    /** The close button (`ShellAction.CloseSound`). */
    fun close()
}

/**
 * SoundApp.tsx `SoundApp`: every sound setting of the unit, one section at a time, over the app it was opened from
 * (`section.music-app.settings-app.sound-app`, the Settings layout: the sections on the left at 340 px, the chosen one
 * on the right, 48 px apart, inside `76px 64px 24px 128px` on `--sheet`). The section shown is this view's own
 * state, kept while the view lives, as the mounted component keeps it; the equalizer's page of bands starts at the
 * first each time the equalizer is shown.
 *
 * Opening rises from the bottom (420 ms, `--sheet-in`); closing shrinks onto [closeTo] (the home card's rectangle,
 * canvas px) in 280 ms while the content fades in 100 ms, as `.music-app` does.
 */
@Mirrors("SoundApp.tsx", "CarTop.tsx")
class SoundView(context: Context) : FrameLayout(context) {
    var actions: SoundActions? = null
    /** Where the window closes onto, in canvas px; null: `inset(20% 2% 2% 72%)`, the default of `--to-*`. */
    var closeTo: RectF? = null

    var section = SoundSection.EQ
        private set
    /** The detail's scroll thumb; the parity scenes turn it off, as the headless browser draws no scrollbar. */
    internal var scrollbar = true
    /** The window's `--sheet` ground; the parity scenes turn it off to show one element alone. */
    internal var ground = true
        set(v) { field = v; background = if (v) ColorDrawable(UiTheme.settings.sheet) else null }
    private var s = SoundSettings()
    private var open = false
    private var page: Page? = null

    private val closeButton = GlassButton(context, Icons.CARET_DOWN).apply { uiKey = "glass-button.music-close" }
    private val nav = Nav()
    private val detail = Detail()
    private val column = ro.rdstr.ui.kit.CssColumn(context, 28f).apply {
        clipChildren = false
        setPadding(dpi(4f), 0, dpi(4f), dpi(24f))
    }
    private val pageTitle = KitText(context, TextStyle(26f, 700, -0.01f)).apply {
        uiKey = "set-page-title"
        val pill = BoxDrawable(this, CssBox().apply { radius = 999f; border = 1f })
        background = pill
        // padding 8 22 inside the 1 px border
        setPadding(dpi(23f), px(9f).toInt(), dpi(23f), kotlin.math.ceil(px(9f)).toInt())
        UiTheme.link(this) {
            pill.box.borderColor = UiTheme.settings.hairline
            pill.colors(UiTheme.base.glassStrong, UiTheme.base.glassStrong)
            setTextColor(UiTheme.settings.ink)
        }
    }

    init {
        uiKey = "music-app.settings-app.sound-app"
        visibility = INVISIBLE
        clipChildren = false
        addView(closeButton)
        addView(nav)
        addView(detail)
        detail.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        closeButton.setOnClickListener { actions?.close() }
        UiTheme.link(this) { background = if (ground) ColorDrawable(UiTheme.settings.sheet) else null }
        background = ColorDrawable(UiTheme.settings.sheet)
        show(SoundSection.EQ, fade = false)
    }

    /** The shell's `sound` (open) and `dsp` (the settings shown). */
    fun bind(open: Boolean, settings: SoundSettings) {
        s = settings
        nav.update()
        page?.update()
        if (open != this.open) { this.open = open; transition(open) }
    }

    /** A section tapped in the list (`setSection`): its page replaces the shown one and fades in over 200 ms. */
    fun show(section: SoundSection, fade: Boolean = true) {
        this.section = section
        nav.update()
        column.removeAllViews()
        column.addView(pageTitle, ro.rdstr.ui.kit.CssColumn.Params(WRAP, WRAP, cssTop = 4f, cssBottom = -8f))
        pageTitle.text = section.label
        val p = when (section) {
            SoundSection.EQ -> EqPage()
            SoundSection.BALANCE -> BalancePage()
            SoundSection.FIELD -> FieldPage()
            SoundSection.LOUDNESS -> LoudnessPage()
            SoundSection.BASS -> BassPage()
            SoundSection.FILTERS -> FiltersPage()
        }
        page = p
        p.update()
        detail.fit = section == SoundSection.EQ
        detail.scrollTo(0, 0)
        if (fade) {
            column.alpha = 0f
            column.animate().alpha(1f).setDuration(UiTheme.duration(200)).setInterpolator(Motion.interpolator(Motion.seamless)).start()
        }
    }

    private fun set(next: SoundSettings) = actions?.setSound(next)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        nav.measure(exactly(340f), exactly(650f))
        detail.measure(exactly(700f), exactly(650f))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        place(closeButton, 32f, 76f)
        place(nav, 128f, 76f)
        place(detail, 516f, 76f)
    }

    private fun exactly(css: Float) = MeasureSpec.makeMeasureSpec(dpi(css), MeasureSpec.EXACTLY)

    private fun place(v: View, x: Float, y: Float) {
        val l = dpi(x); val t = dpi(y)
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
    }

    // the open and close transitions (music.css .music-app)

    private var running: ValueAnimator? = null
    private var clipK = 0f
    private var clipRadius = 0f
    private val clip = Path()
    private val clipRect = RectF()

    private fun transition(open: Boolean) {
        running?.cancel()
        val speed = UiTheme.speed
        if (speed <= 0f || !isAttachedToWindow || width == 0) {
            visibility = if (open) VISIBLE else INVISIBLE
            alpha = 1f; translationY = 0f; clipK = 0f; kids(1f)
            return
        }
        visibility = VISIBLE
        val fromAlpha = alpha
        val fromClip = clipK
        val sheetIn = Motion.sheetIn
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UiTheme.duration(if (open) 420 else 280)
            interpolator = null
            addUpdateListener { a ->
                val ms = a.currentPlayTime / speed
                fun part(len: Float) = (ms / len).coerceIn(0f, 1f)
                alpha = fromAlpha + ((if (open) 1f else 0f) - fromAlpha) * FADE.at(part(280f))
                clipK = fromClip + ((if (open) 0f else 1f) - fromClip) * sheetIn.at(part(280f))
                kids(if (open) Motion.easeOut.at(part(100f)) else 1f - Motion.easeOut.at(part(100f)))
                translationY = if (open) (1f - sheetIn.at(part(420f))) * height else 0f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (running === animation && !open) visibility = INVISIBLE
                }
            })
            start()
        }
    }

    private fun kids(a: Float) { closeButton.alpha = a; nav.alpha = a; detail.alpha = a }

    override fun draw(canvas: Canvas) {
        if (clipK <= 0f) return super.draw(canvas)
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        val to = closeTo
        val l = (to?.left?.times(d) ?: (0.72f * w)) * clipK
        val t = (to?.top?.times(d) ?: (0.2f * h)) * clipK
        val r = w - (to?.right?.let { w - it * d } ?: (0.02f * w)) * clipK
        val b = h - (to?.bottom?.let { h - it * d } ?: (0.02f * h)) * clipK
        clipRadius = UiTheme.base.radius * d * clipK
        clipRect.set(l, t, r, b)
        clip.rewind(); clip.addRoundRect(clipRect, clipRadius, clipRadius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip); super.draw(canvas); canvas.restore()
    }

    // the left list (.set-nav)

    private inner class Nav : LinearLayout(context) {
        private val title = KitText(context, TextStyle(34f, 700, -0.02f)).apply { uiKey = "set-title"; text = "Sound" }
        private val list = LinearLayout(context).apply {
            uiKey = "ul"
            orientation = VERTICAL
            clipChildren = false
            dividerDrawable = gap(this, 4f)
            showDividers = SHOW_DIVIDER_MIDDLE
        }
        private val items = SoundSection.entries.map { NavItem(it) }

        init {
            uiKey = "set-nav"
            orientation = VERTICAL
            dividerDrawable = gap(this, 16f)
            showDividers = SHOW_DIVIDER_MIDDLE
            addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(4f); leftMargin = dpi(8f) })
            addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
            for (item in items) list.addView(FrameLayout(context).apply {
                uiKey = "li"
                clipChildren = false
                addView(item, FrameLayout.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            UiTheme.link(this) { title.setTextColor(UiTheme.settings.ink) }
        }

        fun update() = items.forEach { it.update() }
    }

    /**
     * `.set-nav-item`: 60 px, the tile (36 px, radius 9, the section's tint, a 22 px white icon), the label (19 px,
     * 600) and the summary (16 px, `--ink-muted`, at most 130 px); the current one on `--set-current` (its summary
     * white, its tile ringed with `rgb(var(--on) / .35)`; on Glassy with the sheen and the glass shadow), pressed
     * `rgb(var(--on) / .08)`, 200 ms on `--seamless`.
     */
    private inner class NavItem(private val which: SoundSection) : LinearLayout(context) {
        private val tileBox = CssBox().apply { radius = 9f; fill = which.tint }
        private val tile = object : FrameLayout(context) {
            override fun onDraw(canvas: Canvas) = tileBox.draw(canvas, this, width.toFloat(), height.toFloat())
        }.apply {
            uiKey = "set-tile"
            setWillNotDraw(false)
            addView(IconView(context, ICONS.getValue(which), 22f).apply { uiKey = "icon.${CLASSES.getValue(which)}"; color = -1 }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
        private val label = KitText(context, TextStyle(19f, 600)).apply { uiKey = "set-nav-label"; text = which.label; maxLines = 1 }
        private val value = KitText(context, TextStyle(16f)).apply { uiKey = "set-nav-value"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dpi(130f) }
        private val box = CssBox().apply { radius = 14f }
        private val fill = ColorTransition(0)
        private var current: Boolean? = null

        init {
            uiKey = "set-nav-item"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(60f)
            setPadding(dpi(10f), 0, dpi(16f), 0)
            dividerDrawable = gap(this, 14f)
            showDividers = SHOW_DIVIDER_MIDDLE
            clipChildren = false
            setWillNotDraw(false)
            isClickable = true
            addView(tile, LinearLayout.LayoutParams(dpi(36f), dpi(36f)))
            addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
            setOnClickListener { if (section != which) show(which) }
            UiTheme.link(this) { current = null; update() }
        }

        private fun rest() = if (section == which) UiTheme.settings.setCurrent else if (isPressed) CssColor.withAlpha(UiTheme.base.on, 0.08f) else 0

        fun update() {
            val on = section == which
            val st = UiTheme.settings
            value.text = SoundModel.summary(which, s)
            label.setTextColor(st.ink)
            value.setTextColor(if (on) -1 else st.inkMuted)
            tileBox.shadows = if (on) listOf(Shadow(false, 0f, 0f, 0f, 1f, CssColor.withAlpha(UiTheme.base.on, 0.35f))) else emptyList()
            if (on && UiTheme.glassy) { box.gradient = UiTheme.glass.glassySheen; box.shadows = UiTheme.glass.glassyShadow } else { box.gradient = null; box.shadows = emptyList() }
            if (current == null) fill.snap(rest()) else if (current != on) fill.to(rest(), Motion.now(), UiTheme.duration(200), Motion.seamless)
            current = on
            tile.invalidate()
            invalidate()
        }

        override fun dispatchSetPressed(pressed: Boolean) {
            super.dispatchSetPressed(pressed)
            fill.to(rest(), Motion.now(), UiTheme.duration(200), Motion.seamless)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            box.fill = fill.value(now)
            if (CssColor.alpha(box.fill) > 0 || box.gradient != null) box.draw(canvas, this, width.toFloat(), height.toFloat())
            if (fill.running(now)) postInvalidateOnAnimation()
        }
    }

    // the right side (.set-detail): scrolls, except the fitted equalizer page

    private inner class Detail : ScrollView(context) {
        var fit = false
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init {
            uiKey = "set-detail"
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isFillViewport = false
            clipChildren = false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent) = !fit && super.onInterceptTouchEvent(ev)
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent) = !fit && super.onTouchEvent(ev)

        /** `::-webkit-scrollbar`: a 4 px thumb, radius 2, `rgb(var(--on) / .24)`, while the page is taller than the side. */
        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val content = getChildAt(0)?.height ?: return
            if (fit || !scrollbar || content <= height) return
            val len = height.toFloat() * height / content
            val top = scrollY + (height - len) * scrollY / (content - height)
            thumb.color = CssColor.withAlpha(UiTheme.base.on, 0.24f)
            rect.set(width - px(4f), top, width.toFloat(), top + len)
            canvas.drawRoundRect(rect, px(2f), px(2f), thumb)
        }
    }

    // the sections

    private interface Page { fun update() }

    private fun add(v: View, width: Int = MATCH, top: Float = 0f): View {
        column.addView(v, ro.rdstr.ui.kit.CssColumn.Params(width, WRAP, cssTop = top))
        return v
    }

    private fun footer(text: String) = KitText(context, TextStyle(15f, 400, lineHeight = 1.45f)).apply {
        uiKey = "set-footer"
        this.text = text
        UiTheme.link(this) { setTextColor(UiTheme.settings.inkMuted); maxWidth = (paint.measureText("0") * 70).toInt() }
    }

    private fun addFooter(text: String) = footer(text).also {
        column.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dpi(20f); rightMargin = dpi(20f) })
    }

    /** A Settings group (`Group`) on the page. */
    private fun group(title: String? = null, footer: String? = null) = SettingsGroup(context).also { it.title = title; it.footer = footer; add(it) }

    private fun switchRow(group: SettingsGroup, label: String, onChange: (Boolean) -> Unit): KitSwitch {
        val sw = KitSwitch(context)
        UiTheme.link(sw) { sw.uiKey = if (UiTheme.glassy) "lg-switch.set-switch-glass" else "switch" }
        sw.onChange = onChange
        group.rows.addView(SettingsRow(context, label).apply { addControl(sw) })
        return sw
    }

    private fun stepRow(group: SettingsGroup, label: String, values: List<Int>, format: (Int) -> String, onChange: (Int) -> Unit): Pair<SettingsRow, Stepper> {
        val st = Stepper(context, values, format).apply { this.onChange = onChange }
        val row = SettingsRow(context, label).apply { clipChildren = false; addControl(st) }
        group.rows.addView(row)
        return row to st
    }

    private fun resetRow(group: SettingsGroup, label: String, press: () -> Unit) =
        group.rows.addView(SettingsRow(context, label).apply { onPress = press })

    private fun segmented(stretch: Boolean) = KitSegmented(context).apply {
        this.stretch = stretch
        UiTheme.link(this) { uiKey = if (UiTheme.glassy) "lg-segmented.set-seg-glass" else "set-seg" }
    }

    /** `.snd-eq` and `.snd-stage`: the settings group's ground (radius 18), glass on Glassy. */
    private fun ground(v: View, box: CssBox) = UiTheme.link(v) {
        if (UiTheme.glassy) box.glassy(UiTheme.glass) else box.solid(UiTheme.settings.setGroup)
        v.invalidate()
    }

    /** SoundApp.tsx `Equalizer`: the presets across the page, the curve of all 30 bands, the page of 10 shown, its bands. */
    private inner class EqPage : Page {
        private var shown = 0
        private val presets = segmented(stretch = true).apply {
            labels = SoundModel.PRESETS.map { it.second }
            onChange = { i -> set(Sound.withPreset(s, SoundModel.PRESETS[i].first)) }
        }
        private val pages = segmented(stretch = false).apply {
            labels = SoundModel.pages()
            onChange = { i -> shown = i; update() }
        }
        private val curve = EqCurve(context)
        private val bands = List(SoundModel.BANDS_PER_PAGE) { k ->
            BandSlider(context).apply { track.onChange = { v -> set(Sound.withBand(s, shown * SoundModel.BANDS_PER_PAGE + k, v)) } }
        }

        init {
            add(FrameLayout(context).apply {
                uiKey = "snd-presets"
                addView(presets, FrameLayout.LayoutParams(MATCH, WRAP))
            })
            val box = CssBox().apply { radius = 18f }
            val eq = object : LinearLayout(context) {
                override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
            }.apply {
                uiKey = "snd-eq"
                orientation = LinearLayout.VERTICAL
                setWillNotDraw(false)
                clipChildren = false
                setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(14f))
                addView(curve, LinearLayout.LayoutParams(MATCH, dpi(88f)))
                addView(FrameLayout(context).apply {
                    uiKey = "snd-eq-pages"
                    addView(pages, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(14f) })
                addView(LinearLayout(context).apply {
                    uiKey = "snd-bands"
                    clipChildren = false
                    for (b in bands) addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(10f) })
            }
            ground(eq, box)
            add(eq)
            addFooter("dB per band, from −${SoundModel.BAND_DB} to +${SoundModel.BAND_DB}. Moving a band makes the preset Custom; Flat sets every band back to 0.")
        }

        override fun update() {
            presets.selected = SoundModel.PRESETS.indexOfFirst { it.first == s.preset }
            pages.selected = shown
            curve.set(s.bands, shown)
            val first = shown * SoundModel.BANDS_PER_PAGE
            bands.forEachIndexed { k, b -> b.set(Sound.BANDS[first + k], s.bands[first + k]) }
        }
    }

    /** `.snd-stage`: the cabin (289 x 503 in a 288 px column) beside its readout, 40 px apart, inside `16px 32px`. */
    private fun stage(cabin: Cabin, readout: View): LinearLayout {
        val box = CssBox().apply { radius = 18f }
        val st = object : LinearLayout(context) {
            override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
        }.apply {
            uiKey = "snd-stage"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setWillNotDraw(false)
            clipChildren = false
            setPadding(dpi(32f), dpi(16f), dpi(32f), dpi(16f))
            addView(cabin, LinearLayout.LayoutParams(dpi(289f), dpi(503f)).apply { rightMargin = dpi(39f) })
            addView(readout, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        ground(st, box)
        add(st)
        return st
    }

    /** `.snd-readout`: label-value pairs (15 px `--ink-muted` over 34 px 600), then its actions, 22 px apart. */
    private fun readout(vararg parts: View) = LinearLayout(context).apply {
        uiKey = "snd-readout"
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        dividerDrawable = gap(this, 22f)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        for (p in parts) addView(p, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private inner class Reading(label: String) {
        val value = KitText(context, TextStyle(34f, 600, -0.02f)).apply { uiKey = "strong" }
        val view = LinearLayout(context).apply {
            uiKey = "p"
            orientation = LinearLayout.VERTICAL
            dividerDrawable = gap(this, 4f)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            val span = KitText(context, TextStyle(15f)).apply { uiKey = "span"; text = label }
            addView(span, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(value, LinearLayout.LayoutParams(MATCH, WRAP))
            UiTheme.link(this) { span.setTextColor(UiTheme.settings.inkMuted); value.setTextColor(UiTheme.settings.ink) }
        }
    }

    /** SoundApp.tsx `Balance`: drag the dot over the cabin, or tap Center. */
    private inner class BalancePage : Page {
        private val cabin = Cabin(pad = true)
        private val lr = Reading("Left and right")
        private val fr = Reading("Front and rear")
        private val center = inlineButton(context, "Center").apply { setOnClickListener { set(s.copy(balance = 0, fader = 0)) } }

        init {
            stage(cabin, readout(lr.view, fr.view, center))
            cabin.onPlace = { b, f -> if (b != s.balance || f != s.fader) set(s.copy(balance = b, fader = f)) }
        }

        override fun update() {
            cabin.set(s, BooleanArray(4), SoundModel.speakerLevels(s.balance, s.fader))
            lr.value.text = SoundModel.balanceText(s.balance)
            fr.value.text = SoundModel.faderText(s.fader)
            center.isEnabled = !(s.balance == 0 && s.fader == 0)
        }
    }

    /** SoundApp.tsx `Field`: a seat, a row or the middle of the car; Custom shows a delay per speaker. */
    private inner class FieldPage : Page {
        private val cabin = Cabin(pad = false)
        private val focused = Reading("Focused on")
        private val focus = SoundModel.FOCUS.map { f -> focusButton(f.label).apply { setOnClickListener { set(s.copy(seat = f.seat)) } } }
        private val delays = SettingsGroup(context).apply {
            title = "Delay per Speaker"
            footer = "Each speaker plays later by this much, so the sound of all four reaches you together; 0.1 ms is about 3.4 cm."
        }
        private val delayRows = SoundModel.SPEAKERS.mapIndexed { i, name ->
            stepRow(delays, name, SoundModel.DELAYS, SoundModel::ms) { v -> set(s.copy(delays = s.delays.mapIndexed { j, d -> if (j == i) v else d })) }
        }
        private val note = footer("Center plays every speaker at once. Front and Rear delay the nearer speakers so the sound meets between the two seats of that row.")

        init {
            val actions = WrapRow(context, 8f).apply { uiKey = "snd-readout-actions"; focus.forEach { addView(it) } }
            val hint = KitText(context, TextStyle(15f)).apply {
                uiKey = "snd-hint"; text = "Tap a seat to focus the sound there."
                UiTheme.link(this) { setTextColor(UiTheme.settings.inkMuted) }
            }
            stage(cabin, readout(focused.view, actions, hint))
            cabin.onSeat = { seat -> set(s.copy(seat = seat)) }
            add(delays)
            column.addView(note, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dpi(20f); rightMargin = dpi(20f) })
        }

        override fun update() {
            cabin.set(s, BooleanArray(4) { SoundModel.lit(s, SoundModel.SEATS[it].first) }, floatArrayOf(1f, 1f, 1f, 1f))
            focused.value.text = SoundModel.summary(SoundSection.FIELD, s)
            SoundModel.FOCUS.forEachIndexed { i, f -> focus[i].tag = (f.seat == s.seat); focus[i].refreshDrawableState(); themeFocus(focus[i]) }
            val custom = s.seat == ListeningSeat.CUSTOM
            delays.visibility = if (custom) VISIBLE else GONE
            note.visibility = if (custom) GONE else VISIBLE
            delayRows.forEachIndexed { i, (row, st) -> row.value = SoundModel.cm(s.delays[i]); st.set(s.delays[i]) }
        }
    }

    /** sound.css `.snd-focus`: a radio button, `--set-blue` text; checked `--ink` on `rgb(var(--on) / .14)`; pressed `rgb(var(--on) / .08)`. */
    private fun focusButton(label: String) = KitText(context, TextStyle(17f, 600)).apply {
        uiKey = "snd-focus"
        text = label
        gravity = Gravity.CENTER
        minHeight = dpi(48f)
        setPadding(dpi(18f), 0, dpi(18f), 0)
        background = BoxDrawable(this, CssBox().apply { radius = 12f })
        isClickable = true
        UiTheme.link(this) { themeFocus(this) }
    }

    private fun themeFocus(b: KitText) {
        val on = b.tag == true
        val base = UiTheme.base.on
        (b.background as BoxDrawable).colors(if (on) CssColor.withAlpha(base, 0.14f) else 0, CssColor.withAlpha(base, 0.08f))
        b.setTextColor(if (on) UiTheme.settings.ink else UiTheme.settings.setBlue)
    }

    /** SoundApp.tsx `Loudness`. */
    private inner class LoudnessPage : Page {
        private val main = group(footer = "Lifts the lows and the highs at low volume, where the ear hears them less.")
        private val sw = switchRow(main, "Loudness") { set(s.copy(loudness = it)) }
        private val low = group("Low")
        private val lowHz = stepRow(low, "Frequency", SoundModel.LOW_HZ, SoundModel::hz) { set(s.copy(loudLowHz = it)) }.second
        private val lowGain = stepRow(low, "Gain", SoundModel.GAINS, SoundModel::db) { set(s.copy(loudLowGain = it)) }.second
        private val high = group("High")
        private val highHz = stepRow(high, "Frequency", SoundModel.HIGH_HZ, SoundModel::hz) { set(s.copy(loudHighHz = it)) }.second
        private val highGain = stepRow(high, "Gain", SoundModel.GAINS, SoundModel::db) { set(s.copy(loudHighGain = it)) }.second

        init { group(footer = "600 Hz and 2 kHz, each lifted 4 dB.").also { resetRow(it, "Reset Loudness") { set(Sound.resetLoudness(s)) } } }

        override fun update() {
            sw.checked = s.loudness
            low.visibility = if (s.loudness) VISIBLE else GONE
            high.visibility = low.visibility
            lowHz.set(s.loudLowHz); lowGain.set(s.loudLowGain); highHz.set(s.loudHighHz); highGain.set(s.loudHighGain)
        }
    }

    /** SoundApp.tsx `Bass`. */
    private inner class BassPage : Page {
        private val main = group(footer = "Adds the low notes door speakers cannot play, as harmonics the ear hears as bass.")
        private val sw = switchRow(main, "Smart Bass") { set(s.copy(bass = it)) }
        private val gain = stepRow(main, "Gain", SoundModel.GAINS, SoundModel::db) { set(s.copy(bassGain = it)) }
        private val sub = group("Subwoofer Output", "For a subwoofer on the unit's sub out: its level, and the frequency above which it stays quiet.")
        private val level = stepRow(sub, "Level", SoundModel.SUB_LEVELS, SoundModel::db) { set(s.copy(subLevel = it)) }.second
        private val cut = stepRow(sub, "Cut Above", SoundModel.SUB_HZ, SoundModel::hz) { set(s.copy(subHz = it)) }.second

        init { group(footer = "On, 6 dB, subwoofer 10 dB up to 240 Hz.").also { resetRow(it, "Reset Smart Bass") { set(Sound.resetBass(s)) } } }

        override fun update() {
            sw.checked = s.bass
            gain.first.visibility = if (s.bass) VISIBLE else GONE
            sub.visibility = gain.first.visibility
            gain.second.set(s.bassGain); level.set(s.subLevel); cut.set(s.subHz)
        }
    }

    /** SoundApp.tsx `Filters` and `FilterPair`: a high-pass and a low-pass per pair of speakers, with the response they give. */
    private inner class FiltersPage : Page {
        private val front = FilterPair("Front Speakers", "A high-pass keeps the lows a door speaker cannot play out of it, so it plays louder without distorting.") { f -> s.copy(front = f) }
        private val rear = FilterPair("Rear Speakers", "A low-pass on the rear keeps the highs in front, where the stereo image is.") { f -> s.copy(rear = f) }

        override fun update() { front.update(s.front); rear.update(s.rear) }
    }

    private inner class FilterPair(title: String, footer: String, private val with: (SpeakerFilter) -> SoundSettings) {
        private val g = group(title, footer)
        private val curve = ResponseCurve(context)
        private var f = SpeakerFilter()
        private val hp: KitSwitch
        private val hpHz: Pair<SettingsRow, Stepper>
        private val lp: KitSwitch
        private val lpHz: Pair<SettingsRow, Stepper>

        init {
            g.rows.addView(LinearLayout(context).apply {
                uiKey = "snd-response"
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(20f), dpi(18f), dpi(20f), dpi(12f))
                setWillNotDraw(false)
                addView(curve, LinearLayout.LayoutParams(MATCH, dpi(72f)))
                addView(LinearLayout(context).apply {
                    uiKey = "snd-scale"
                    SoundModel.SCALE.forEachIndexed { i, t ->
                        if (i > 0) addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
                        addView(KitText(context, TextStyle(14f)).apply {
                            uiKey = "span"; text = t
                            UiTheme.link(this) { setTextColor(UiTheme.settings.inkMuted) }
                        })
                    }
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(10f) })
            })
            hp = switchRow(g, "High-Pass") { set(with(f.copy(highPass = it))) }
            hpHz = stepRow(g, "Cut Below", Sound.HPF_HZ, SoundModel::hz) { set(with(f.copy(highPassHz = it))) }
            lp = switchRow(g, "Low-Pass") { set(with(f.copy(lowPass = it))) }
            lpHz = stepRow(g, "Cut Above", Sound.LPF_HZ, SoundModel::hz) { set(with(f.copy(lowPassHz = it))) }
        }

        fun update(f: SpeakerFilter) {
            this.f = f
            curve.set(f)
            hp.checked = f.highPass; lp.checked = f.lowPass
            hpHz.first.visibility = if (f.highPass) VISIBLE else GONE
            lpHz.first.visibility = if (f.lowPass) VISIBLE else GONE
            hpHz.second.set(f.highPassHz); lpHz.second.set(f.lowPassHz)
        }
    }

    /**
     * `.snd-cabin` (289 x 503): the car from above, and over it either the balance pad (`.snd-pad`, 30 % in from
     * each door, from 41 % to 71 % of the length) or the four seats to tap (`.snd-seat-hit`, the seats' boxes).
     */
    private inner class Cabin(pad: Boolean) : FrameLayout(context) {
        var onPlace: ((Int, Int) -> Unit)? = null
        var onSeat: ((ListeningSeat) -> Unit)? = null
        private val car = CarTopView(context)
        private val padView = if (pad) Pad() else null
        private val seats = if (pad) emptyList() else SoundModel.SEATS.map { (seat, label) ->
            View(context).apply {
                uiKey = "snd-seat-hit"
                contentDescription = label
                isClickable = true
                setOnClickListener { onSeat?.invoke(seat) }
            }
        }

        init {
            uiKey = "snd-cabin"
            clipChildren = false
            addView(car)
            padView?.let { addView(it) }
            seats.forEach { addView(it) }
        }

        fun set(s: SoundSettings, lit: BooleanArray, levels: FloatArray) {
            car.set(lit, levels)
            padView?.set(s.balance, s.fader)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            car.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
            padView?.measure(MeasureSpec.makeMeasureSpec((measuredWidth * 0.4f).roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((measuredHeight * 0.3f).roundToInt(), MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val w = width.toFloat(); val h = height.toFloat()
            car.layout(0, 0, width, height)
            padView?.let { p -> box(p, 0.3f * w, 0.41f * h, 0.7f * w, 0.71f * h) }
            seats.forEachIndexed { i, v ->
                val r = SoundModel.CAR_SEATS[i]
                box(v, r[0] / SoundModel.CAR_W * w, r[1] / SoundModel.CAR_H * h, (r[0] + r[2]) / SoundModel.CAR_W * w, (r[1] + r[3]) / SoundModel.CAR_H * h)
            }
        }

        private fun box(v: View, l: Float, t: Float, r: Float, b: Float) {
            val L = l.roundToInt(); val T = t.roundToInt(); val R = r.roundToInt(); val B = b.roundToInt()
            v.measure(MeasureSpec.makeMeasureSpec(R - L, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(B - T, MeasureSpec.EXACTLY))
            v.layout(L, T, R, B)
        }

        /** `.snd-pad`: both axes through the dot (`--set-blue` at 55 %, 2 px) and the dot (36 px, blue, 3 px white border, a drop). */
        private inner class Pad : FrameLayout(context) {
            private var balance = 0
            private var fader = 0
            private var dragging = false
            private val axisX = View(context).apply { uiKey = "snd-axis" }
            private val axisY = View(context).apply { uiKey = "snd-axis" }
            private val dotBox = CssBox().apply { radius = 999f; border = 3f; borderColor = -1; shadows = listOf(Shadow(false, 0f, 2f, 8f, 0f, 0x73000000)) }
            private val dot = object : View(context) {
                override fun onDraw(canvas: Canvas) = dotBox.draw(canvas, this, width.toFloat(), height.toFloat())
            }.apply { uiKey = "snd-dot" }

            init {
                uiKey = "snd-pad"
                clipChildren = false
                addView(axisX); addView(axisY); addView(dot)
                UiTheme.link(this) {
                    val axis = CssColor.fade(UiTheme.settings.setBlue, 0.55f)
                    axisX.background = ColorDrawable(axis); axisY.background = ColorDrawable(axis)
                    dotBox.fill = UiTheme.settings.setBlue; dot.invalidate()
                }
            }

            fun set(balance: Int, fader: Int) {
                if (balance == this.balance && fader == this.fader) return
                val beforeX = dot.left + dot.translationX; val beforeY = dot.top + dot.translationY
                this.balance = balance; this.fader = fader
                place()
                if (!dragging && isAttachedToWindow) {
                    // left/top 120 ms on --seamless; none while dragging
                    dot.translationX = beforeX - dot.left; dot.translationY = beforeY - dot.top
                    dot.animate().translationX(0f).translationY(0f).setDuration(UiTheme.duration(120)).setInterpolator(Motion.interpolator(Motion.seamless)).start()
                }
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = place()

            private fun place() {
                val w = width.toFloat(); val h = height.toFloat()
                if (w == 0f) return
                val x = SoundModel.dotX(balance) * w
                val y = SoundModel.dotY(fader) * h
                lay(axisX, -0.3f * w, y - px(1f), 1.6f * w, px(2f))
                lay(axisY, x - px(1f), -1.3f * h, px(2f), 3.2f * h)
                lay(dot, x - px(18f), y - px(18f), px(36f), px(36f))
            }

            /** Placed at ([l], [t]) with its own size rounded once, so a 2 px line stays 2 px wherever it falls. */
            private fun lay(v: View, l: Float, t: Float, w: Float, h: Float) {
                val L = l.roundToInt(); val T = t.roundToInt(); val R = L + w.roundToInt(); val B = T + h.roundToInt()
                v.measure(MeasureSpec.makeMeasureSpec(R - L, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(B - T, MeasureSpec.EXACTLY))
                v.layout(L, T, R, B)
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; at(e) }
                    MotionEvent.ACTION_MOVE -> if (dragging) at(e)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
                return true
            }

            private fun at(e: MotionEvent) {
                val (b, f) = SoundModel.balanceAt(e.x / width, e.y / height)
                onPlace?.invoke(b, f)
            }
        }
    }

    private companion object {
        /** music.css `.music-app` closing opacity: `cubic-bezier(0.4, 0, 1, 1)`. */
        val FADE = CssBezier(0.4f, 0f, 1f, 1f)
        val ICONS = mapOf(
            SoundSection.EQ to Icons.SND_FADERS, SoundSection.BALANCE to Icons.SND_BALANCE, SoundSection.FIELD to Icons.SND_FIELD,
            SoundSection.LOUDNESS to Icons.SND_LOUDNESS, SoundSection.BASS to Icons.SND_BASS, SoundSection.FILTERS to Icons.SND_FILTER,
        )
        val CLASSES = mapOf(
            SoundSection.EQ to "i-snd-faders", SoundSection.BALANCE to "i-snd-balance", SoundSection.FIELD to "i-snd-field",
            SoundSection.LOUDNESS to "i-snd-loudness", SoundSection.BASS to "i-snd-bass", SoundSection.FILTERS to "i-snd-filter",
        )
    }
}
