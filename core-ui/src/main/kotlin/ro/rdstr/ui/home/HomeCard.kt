package ro.rdstr.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarEvent
import ro.rdstr.car.FreqDisplay
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * HomeCard.tsx: the card on home, one page per sound and the map (core-car HomeCard), swiped sideways one at a
 * time, with dots below. The track follows the finger (a third of it past the ends) and settles on
 * `--spring-swap` (520 ms); a swipe longer than 18 % of the card or quicker than 0.45 px/ms turns one page, and
 * never taps what is under it. Distances are canvas px.
 *
 * [mapSlot] is the map page's `div.card-map`, where the one map goes while the Maps app is closed.
 */
@Mirrors("HomeCard.tsx")
@SuppressLint("ViewConstructor")
class HomeCardView(context: Context, private val actions: CardActions) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val bluetooth by lazy { BluetoothPage(context, actions) }
    private val noPhone by lazy { PhonePage(context, actions, off = false) }
    private val btOff by lazy { PhonePage(context, actions, off = true) }
    private val radio by lazy { RadioPage(context, actions) }
    private val map by lazy { MapPage(context, actions) }
    /** `div.card-map`: the map's place on the card. */
    val mapSlot: FrameLayout get() = map.slot

    private val track = Track(context)
    private val dots = Dots(context) { actions.showCard(it) }
    private var state = CardState()
    private var index = 0

    // the swipe
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var sideways: Boolean? = null
    private var dx = 0f

    init {
        uiKey = "card-pages"
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 16 * d)
        }
        addView(track, LayoutParams(-1, -1))
        addView(dots, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (10 * d).toInt() })
    }

    private fun page(p: CardPage): CardPageView = when (p) {
        CardPage.BLUETOOTH -> bluetooth
        CardPage.NO_PHONE -> noPhone
        CardPage.BT_OFF -> btOff
        CardPage.RADIO -> radio
        CardPage.NAVIGATION -> map
    }

    fun bind(s: CardState) {
        state = s
        val views = s.pages.map { page(it) }
        track.pages(views.map { it.view })
        for (v in views) v.bind(s)
        val i = s.pages.indexOf(s.shown).coerceAtLeast(0)
        // a hidden page is inert and out of the accessibility tree
        views.forEachIndexed { n, v -> v.view.importantForAccessibility = if (n == i) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        if (i != index) { index = i; track.go(i, isAttachedToWindow) }
        dots.visibility = if (s.pages.size > 1) VISIBLE else GONE
        dots.set(s.pages, s.shown)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = swipe(e)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean { swipe(e); return true }

    /** Watches every touch on the card; true once it is a sideways swipe, which then takes the touch from the page. */
    private fun swipe(e: MotionEvent): Boolean {
        if (state.pages.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; downAt = e.eventTime; sideways = null; dx = 0f }
            MotionEvent.ACTION_MOVE -> {
                val mx = (e.rawX - downX) / d
                val my = (e.rawY - downY) / d
                if (sideways == null && hypot(mx, my) > INTENT_PX) sideways = abs(mx) > abs(my)
                if (sideways == true) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dx = mx
                    val atEdge = (dx > 0 && index == 0) || (dx < 0 && index == state.pages.lastIndex)
                    track.dragTo(if (atEdge) dx * EDGE_GIVE else dx)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (sideways == true) {
                    val width = this.width / d
                    val speed = dx / maxOf(1L, e.eventTime - downAt)
                    val turn = if (abs(dx) > width * TURN_SHARE || abs(speed) > TURN_PX_PER_MS) -sign(dx).toInt() else 0
                    val next = (index + turn).coerceIn(0, state.pages.lastIndex)
                    sideways = null
                    track.release()
                    if (next != index) { index = next; track.go(next, true); actions.showCard(state.pages[next]) }
                    return true
                }
                sideways = null
            }
        }
        return false
    }

    /** `.card-track`: the pages side by side, translated to the shown one plus the finger's way. */
    private class Track(context: Context) : ViewGroup(context) {
        private val d = resources.displayMetrics.density
        private val at = Transition(0f)
        private var drag = 0f
        private var target = 0

        init { uiKey = "card-track" }

        fun pages(views: List<View>) {
            if (views.size == childCount && views.indices.all { getChildAt(it) === views[it] }) return
            removeAllViews()
            for (v in views) {
                (v.parent as? ViewGroup)?.removeView(v)
                addView(FrameLayout(context).apply { uiKey = "card-page"; addView(v, FrameLayout.LayoutParams(-1, -1)) })
            }
        }

        fun go(i: Int, animate: Boolean) {
            target = i
            val to = -i.toFloat()
            if (animate) at.to(to, Motion.now(), UiTheme.duration(SWAP_MS), Motion.springSwap) else at.snap(to)
            invalidate(); requestLayout()
        }

        /** The finger's offset in canvas px, added to the shown page's place. */
        fun dragTo(px: Float) { drag = px; requestLayout(); invalidate() }

        /** The finger lifts: the track springs from where it is to the shown page (go() may then change the page). */
        fun release() {
            if (width > 0) {
                val now = Motion.now()
                at.snap(at.value(now) + drag * d / width)
                at.to(-target.toFloat(), now, UiTheme.duration(SWAP_MS), Motion.springSwap)
            }
            drag = 0f
            requestLayout(); invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
            place()
        }

        private fun place() {
            val now = Motion.now()
            val x = at.value(now) * width + drag * d
            for (i in 0 until childCount) getChildAt(i).translationX = x
            if (at.running(now)) postOnAnimation { place() }
        }

    }

    /** `.card-dots`: one dot per page, the shown one 18 px wide (360 ms `--spring-swap`); a tap shows its page. */
    @SuppressLint("ViewConstructor")
    private class Dots(context: Context, private val onPick: (CardPage) -> Unit) : LinearLayout(context) {
        private val d = resources.displayMetrics.density
        private val box = CssBox().apply { radius = 10f }
        private var pages: List<CardPage> = emptyList()

        init {
            uiKey = "card-dots"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setWillNotDraw(false)
            setPadding((9 * d).toInt(), (5 * d).toInt(), (9 * d).toInt(), (5 * d).toInt())
            UiTheme.link(this) { theme() }
        }

        private fun theme() {
            val fill = if (UiTheme.light) 0xB3FFFFFF.toInt() else 0x52000000
            // Glassy, by day too: the glass material of the theme
            if (UiTheme.glassy) box.glassy(UiTheme.glass) else box.solid(fill)
            for (i in 0 until childCount) (getChildAt(i) as Dot).theme()
            invalidate()
        }

        fun set(pages: List<CardPage>, shown: CardPage) {
            if (pages != this.pages) {
                this.pages = pages
                removeAllViews()
                pages.forEachIndexed { i, p ->
                    addView(Dot(context).apply {
                        uiKey = "button"
                        contentDescription = p.name.lowercase().replaceFirst("_", " ")
                        setOnClickListener { onPick(p) }
                    }, LayoutParams(-2, -2).apply { if (i > 0) leftMargin = (7 * d).toInt() })
                }
            }
            pages.forEachIndexed { i, p -> (getChildAt(i) as Dot).on = p == shown }
        }

        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
    }

    private class Dot(context: Context) : KitView(context) {
        var on = false; set(v) { if (field != v) { field = v; change(true) } }
        private val wide = Transition(7f)
        private val color = ColorTransition(0)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { isClickable = true; onTheme() }

        fun theme() = change(false)

        override fun onTheme() = change(false)

        private fun fillOf(on: Boolean) = if (UiTheme.light) (if (on) UiTheme.base.ink else 0x40000000) else (if (on) -1 else 0x6BFFFFFF)

        private fun change(animate: Boolean) {
            val now = Motion.now()
            if (animate && isAttachedToWindow) {
                wide.to(if (on) 18f else 7f, now, UiTheme.duration(360), Motion.springSwap)
                color.to(fillOf(on), now, UiTheme.duration(200), Motion.easeOut)
            } else { wide.snap(if (on) 18f else 7f); color.snap(fillOf(on)) }
            requestLayout(); invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension((wide.value(Motion.now()) * d + 0.5f).toInt(), (7 * d + 0.5f).toInt())

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            paint.color = color.value(now)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, 4 * d, 4 * d, paint)
            if (wide.running(now) || color.running(now)) { requestLayout(); postInvalidateOnAnimation() }
        }
    }

    private companion object {
        const val TURN_SHARE = 0.18f
        const val TURN_PX_PER_MS = 0.45f
        const val INTENT_PX = 8f
        const val EDGE_GIVE = 0.3f
        const val SWAP_MS = 520L
    }
}

/**
 * The Bluetooth page without a phone (MusicApp.tsx `NoPhone` at the card's sizes) or with Bluetooth off
 * (HomeCard.tsx `BluetoothOff`): an icon in a disc, a title and a line; then the saved phones, each connecting on a
 * tap, and Other devices; with none saved or Bluetooth off, one blue button.
 */
@SuppressLint("ViewConstructor")
class PhonePage(context: Context, private val actions: CardActions, private val off: Boolean) : Column(context, 0f), CardPageView {
    override val view: View get() = this
    private val d = resources.displayMetrics.density
    private val surface = NpSurface()
    private val discBox = CssBox().apply { radius = 999f }
    private val disc = object : FrameLayout(context) {
        init { setWillNotDraw(false); uiKey = "music-nophone-icon" }
        override fun onDraw(canvas: Canvas) = discBox.draw(canvas, this, width.toFloat(), height.toFloat())
    }
    private val glyph = IconView(context, if (off) Icons.BLUETOOTH else Icons.DEVICE_MOBILE, 34f).apply { uiKey = "icon" }
    private val title = KitText(context, TextStyle(24f, 600, -0.01f)).apply { uiKey = "h2"; gravity = Gravity.CENTER; text = if (off) "Bluetooth is off" else "No phone connected" }
    private val line = KitText(context, TextStyle(15f)).apply { uiKey = "p"; gravity = Gravity.CENTER }
    private val list = Column(context, 10f).apply { uiKey = "music-nophone-list" }
    private val more = PillButton(context, "music-nophone-more", null, TextStyle(16f, 600), 48f, 28f, blue = false, plain = true).apply { label.text = "Other devices"; setOnClickListener { actions.openDevices() } }
    private val pair = PillButton(context, "music-nophone-pair", Icons.BLUETOOTH, TextStyle(17f, 600), 56f, 26f, blue = true).apply {
        label.text = if (off) "Turn on Bluetooth" else "Pair a phone"
        setOnClickListener { if (off) actions.turnOnBluetooth() else actions.openDevices() }
    }
    private val body = Column(context, 0f).apply { uiKey = "music-nophone"; gravity = Gravity.CENTER_HORIZONTAL }
    private var saved: List<String> = emptyList()
    private var busy: Pair<String?, String?> = null to null

    init {
        uiKey = "np.np-phone"
        setWillNotDraw(false)
        gravity = Gravity.CENTER_VERTICAL
        setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (38 * d).toInt())
        disc.addView(glyph, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        body.addView(disc, LinearLayout.LayoutParams((76 * d).toInt(), (76 * d).toInt()))
        body.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * d + 10 * d).toInt() })
        body.addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * d).toInt() })
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d + 16 * d).toInt() })
        body.addView(more, LinearLayout.LayoutParams(-2, (48 * d).toInt()).apply { topMargin = (8 * d + 6 * d).toInt() })
        body.addView(pair, LinearLayout.LayoutParams(-2, (56 * d).toInt()).apply { topMargin = (8 * d + 16 * d).toInt() })
        addView(body, LinearLayout.LayoutParams(-1, -2))
        if (off) line.text = "Turn it on to play music from a phone."
        UiTheme.link(this) { theme() }
        theme()
        bind(CardState())
    }

    private fun theme() {
        val b = UiTheme.base
        surface.theme()
        val fill = if (UiTheme.light) 0x0F000000 else b.glassStrong
        if (UiTheme.glassy) { discBox.glassy(UiTheme.glass, fill); discBox.gradient = null; if (UiTheme.light) discBox.shadows = emptyList() } else discBox.solid(fill)
        glyph.color = b.ink
        title.setTextColor(b.ink)
        line.setTextColor(b.inkMuted)
        for (i in 0 until list.childCount) (list.getChildAt(i) as PillButton).theme()
        more.theme(); pair.theme()
        invalidate()
    }

    override fun bind(s: CardState) {
        if (off) return
        val names = s.saved
        if (names != saved || busy != (s.connecting to s.failed)) {
            saved = names; busy = s.connecting to s.failed
            list.removeAllViews()
            for (name in names) list.addView(PillButton(context, "music-nophone-phone", Icons.BLUETOOTH, TextStyle(17f, 600), 58f, 20f, blue = false).apply {
                label.text = when (name) { s.connecting -> "Connecting…"; s.failed -> "$name · not available"; else -> name }
                isEnabled = s.connecting == null
                alpha = if (isEnabled) 1f else 0.55f
                setOnClickListener { actions.connect(name) }
            }, LinearLayout.LayoutParams(-1, (58 * d).toInt()))
        }
        line.text = if (names.isNotEmpty()) "Connect a phone to play its music." else "Pair a phone to play its music."
        list.visibility = if (names.isNotEmpty()) VISIBLE else GONE
        more.visibility = if (names.isNotEmpty()) VISIBLE else GONE
        pair.visibility = if (names.isEmpty()) VISIBLE else GONE
    }

    override fun onDraw(canvas: Canvas) = surface.draw(canvas, this)
}

/**
 * The card's round buttons: a saved phone (58 px, `--glass-strong`, the Bluetooth glyph in `#0a84ff`), Other
 * devices (48 px, plain) and the blue one (56 px, `#0a84ff`, white). Pressed they shrink (0.97, the blue 0.96) and
 * darken, then spring back on `--spring-glyph`.
 */
@SuppressLint("ViewConstructor")
class PillButton(
    context: Context, key: String, icon: Int?, style: TextStyle, height: Float, side: Float,
    private val blue: Boolean, private val plain: Boolean = false,
) : Row(context, if (blue) 12f else 14f) {
    val label = KitText(context, style).apply { uiKey = "span"; isSingleLine = true }
    private val glyph = icon?.let { IconView(context, it, if (blue) 24f else 22f).apply { uiKey = "icon" } }
    private val box = CssBox().apply { radius = 999f }
    private val scale = Transition(1f)
    private val fill = ColorTransition(0)
    private var down = false

    init {
        uiKey = key
        setWillNotDraw(false)
        isClickable = true
        val d = resources.displayMetrics.density
        setPadding((side * d).toInt(), 0, (side * d).toInt(), 0)
        gravity = if (blue || plain) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        glyph?.let { addView(it) }
        addView(label)
        theme()
    }

    private fun rest(): Int = when { blue -> 0xFF0A84FF.toInt(); plain -> 0; UiTheme.light -> 0x0F000000; else -> UiTheme.base.glassStrong }
    private fun pressed(): Int = when { blue -> 0xFF0070E0.toInt(); plain -> ro.rdstr.ui.kit.CssColor.withAlpha(UiTheme.base.on, 0.12f); else -> ro.rdstr.ui.kit.CssColor.withAlpha(UiTheme.base.on, 0.22f) }

    fun theme() {
        val b = UiTheme.base
        label.setTextColor(if (blue) -1 else b.ink)
        glyph?.color = if (blue) -1 else 0xFF0A84FF.toInt()
        fill.snap(if (down) pressed() else rest())
        box.fill = fill.target
        val lit = if (!blue && !plain && !UiTheme.light) listOf(Shadow(true, 0f, 1f, 0f, 0f, 0x14FFFFFF)) else emptyList()
        if (UiTheme.glassy && !plain) {
            box.glassy(UiTheme.glass, fill.target)
            // the phone rows keep their fill and the card's lit line (light: none); the blue key is tinted glass
            if (!blue) { box.gradient = null; box.shadows = if (UiTheme.light) emptyList() else lit }
        } else { box.solid(fill.target); box.shadows = lit }
        invalidate()
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        if (pressed == down || !isEnabled) return
        down = pressed
        val now = Motion.now()
        scale.to(if (pressed) (if (blue) 0.96f else if (plain) 1f else 0.97f) else 1f, now, UiTheme.duration(if (pressed) 120 else 420), Motion.springGlyph)
        fill.to(if (pressed) pressed() else rest(), now, UiTheme.duration(UiTheme.base.fast), Easing.EASE)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        box.fill = fill.value(now)
        val s = scale.value(now)
        canvas.save(); canvas.scale(s, s, width / 2f, height / 2f)
        box.draw(canvas, this, width.toFloat(), height.toFloat())
        canvas.restore()
        if (scale.running(now) || fill.running(now)) postInvalidateOnAnimation()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val s = scale.value(Motion.now())
        canvas.save(); canvas.scale(s, s, width / 2f, height / 2f)
        super.dispatchDraw(canvas)
        canvas.restore()
    }
}

/**
 * HomeCard.tsx `MapPage`: the map edge to edge ([slot], where the one map is put), a tap opens Maps, and under it a
 * strip of the sound that plays.
 */
@SuppressLint("ViewConstructor")
class MapPage(context: Context, actions: CardActions) : FrameLayout(context), CardPageView {
    override val view: View get() = this
    private val surface = NpSurface()
    val slot = FrameLayout(context).apply { uiKey = "card-map" }
    private val opener = View(context).apply {
        uiKey = "card-map-open"
        contentDescription = "Open maps"
        isClickable = true
        setOnClickListener { actions.openApp(App.MAPS) }
    }
    private val strip = SoundStrip(context, actions)

    init {
        uiKey = "np.np-map"
        setWillNotDraw(false)
        addView(slot, LayoutParams(-1, -1))
        addView(opener, LayoutParams(-1, -1))
        val d = resources.displayMetrics.density
        addView(strip, LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = (10 * d).toInt(); rightMargin = leftMargin; bottomMargin = (32 * d).toInt() })
        UiTheme.link(this) { surface.theme(UiTheme.base.canvas); if (UiTheme.glassy) surface.box.gradient = null; invalidate() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 16 * d)
        }
    }

    override fun bind(s: CardState) {
        strip.visibility = if (s.strip == AudioSource.NONE) GONE else VISIBLE
        if (s.strip != AudioSource.NONE) strip.bind(s)
    }

    override fun onDraw(canvas: Canvas) = surface.draw(canvas, this)
}

/** HomeCard.tsx `SoundStrip`: under the map, the cover or the radio's mark, the names, the transport. */
@SuppressLint("ViewConstructor")
class SoundStrip(context: Context, private val actions: CardActions) : Row(context, 12f) {
    private val d = resources.displayMetrics.density
    private val box = CssBox().apply { radius = 22f; border = 1f }
    private val coverBox = CssBox().apply { radius = 12f }
    private var bitmap: android.graphics.Bitmap? = null
    private val glyph = IconView(context, Icons.MUSIC_NOTES, 26f)
    private val cover = object : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip = android.graphics.Path()
        init { setWillNotDraw(false); uiKey = "card-strip-cover" }
        override fun onDraw(canvas: Canvas) {
            coverBox.draw(canvas, this, width.toFloat(), height.toFloat())
            val b = bitmap ?: return
            clip.reset(); clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12 * d, 12 * d, android.graphics.Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            canvas.drawBitmap(b, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            canvas.restore()
        }
    }
    private val title = Marquee(context, TextStyle(17f, 600, -0.01f)).apply { uiKey = "np-title.marquee" }
    private val artist = Marquee(context, TextStyle(14f)).apply { uiKey = "np-artist.marquee" }
    private var kind = AudioSource.BT
    private val transport = TransportRow(context, TransportRow.STRIP, 0f) {
        actions.pressSound(if (kind == AudioSource.RADIO) CardPage.RADIO else CardPage.BLUETOOTH, arrayOf(ro.rdstr.car.HomeCard.Press.PREV, ro.rdstr.car.HomeCard.Press.PLAY, ro.rdstr.car.HomeCard.Press.NEXT)[it])
    }

    init {
        uiKey = "card-strip"
        setWillNotDraw(false)
        setPadding((10 * d).toInt(), (9 * d).toInt(), (8 * d).toInt(), (9 * d).toInt())
        cover.addView(glyph, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        addView(cover, LayoutParams((52 * d).toInt(), (52 * d).toInt()))
        addView(Column(context, 2f).apply {
            uiKey = "card-strip-meta"
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(artist, LinearLayout.LayoutParams(-1, -2))
        }, LayoutParams(0, -2, 1f))
        addView(transport, LayoutParams(-2, -2))
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        val b = UiTheme.base
        val light = UiTheme.light
        // Glassy, by day too: the glass material of the theme, the strip's own edge kept
        if (UiTheme.glassy) box.glassy(UiTheme.glass)
        else {
            box.solid(if (light) 0xF0FFFFFF.toInt() else 0xE014161A.toInt())
            box.shadows = listOf(if (light) Shadow(false, 0f, 6f, 18f, 0f, 0x24000000) else Shadow(false, 0f, 8f, 24f, 0f, 0x59000000))
        }
        box.borderColor = if (light) 0x14000000 else 0x1FFFFFFF
        coverBox.solid(if (light) 0x0F000000 else 0x14FFFFFF)
        glyph.color = if (light) b.ink else -1
        title.color = if (light) b.ink else -1
        artist.color = if (light) b.inkMuted else 0x9EFFFFFF.toInt()
        transport.colors(if (light) b.ink else -1, if (light) 0x14000000 else 0x29FFFFFF)
        invalidate()
    }

    fun bind(s: CardState) {
        kind = s.strip
        if (s.strip == AudioSource.RADIO) {
            bitmap = null
            glyph.setIcon(Icons.RADIO); glyph.visibility = VISIBLE
            title.text = s.radio.rds.ifEmpty { "Radio" }
            artist.text = FreqDisplay.format(s.radio.band, s.radio.khz) + if (s.radio.band == CarEvent.Radio.Band.FM) " MHz" else " kHz"
            transport.playing = s.car.radioOn
        } else {
            bitmap = s.cover
            glyph.setIcon(Icons.MUSIC_NOTES); glyph.visibility = if (s.cover == null) VISIBLE else GONE
            title.text = s.track.title
            artist.text = s.track.artist
            transport.playing = s.car.playing
        }
        cover.invalidate()
    }

    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
}
