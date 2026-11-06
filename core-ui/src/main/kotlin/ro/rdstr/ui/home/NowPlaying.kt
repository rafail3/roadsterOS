package ro.rdstr.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarEvent
import ro.rdstr.car.FreqDisplay
import ro.rdstr.car.HomeCard
import ro.rdstr.car.PlaybackClock
import ro.rdstr.car.Rds
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssBlur
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.CssFilter
import ro.rdstr.ui.kit.CssGradient
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

private val PRESSES = arrayOf(HomeCard.Press.PREV, HomeCard.Press.PLAY, HomeCard.Press.NEXT)

/**
 * NowPlaying.tsx `Bluetooth`: the phone's track on the card. The cover, blurred, tints the whole page; the cover
 * itself, the title and the artist (a tap opens the music app), the position line and times, the transport.
 * The tint is baked once per cover (blur 60 px, saturate 1.4, 0.55; by day 1.7 and 0.7), as the unit has no live blur.
 */
@Mirrors("NowPlaying.tsx")
@SuppressLint("ViewConstructor")
class BluetoothPage(context: Context, private val actions: CardActions) : Column(context, 12f), CardPageView {
    override val view: View get() = this
    private val surface = NpSurface()
    private val tint = CoverTint()
    private val cover = CoverView(context)
    private val title = Marquee(context, TextStyle(24f, 600, -0.01f)).apply { uiKey = "np-title.marquee" }
    private val artist = Marquee(context, TextStyle(16f)).apply { uiKey = "np-artist.marquee" }
    private val open = Column(context, 16f).apply {
        uiKey = "np-open"
        isClickable = true
        setOnClickListener { actions.openApp(App.MUSIC) }
        addView(cover, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(Column(context, 2f).apply {
            uiKey = "np-meta"
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(artist, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private val progress = ProgressLine(context)
    private val elapsed = context.line(TextStyle(14f), "span:0", false)
    private val remaining = context.line(TextStyle(14f), "span:1", false)
    private val times = FrameLayout(context).apply {
        uiKey = "np-times"
        addView(elapsed, FrameLayout.LayoutParams(-2, -2, Gravity.START))
        addView(remaining, FrameLayout.LayoutParams(-2, -2, Gravity.END))
    }
    private val transport = TransportRow(context, TransportRow.CARD, 4f) { actions.pressSound(CardPage.BLUETOOTH, PRESSES[it]) }
    private var state = CardState()
    private val tick = object : Runnable {
        override fun run() { position(); if (state.car.playing) postDelayed(this, TICK_MS) }
    }

    init {
        uiKey = "np.np-bt"
        setWillNotDraw(false)
        val d = resources.displayMetrics.density
        // .np padding 24, and room for the dots under the controls
        setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (38 * d).toInt())
        addView(open, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(progress, LinearLayout.LayoutParams(-1, -2))
        addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (-4 * d).toInt() })
        addView(transport, LinearLayout.LayoutParams(-1, -2))
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        val b = UiTheme.base
        surface.theme()
        title.color = b.ink
        artist.color = b.inkMuted
        elapsed.setTextColor(b.inkSubtle); remaining.setTextColor(b.inkSubtle)
        val tx = ro.rdstr.ui.tokens.NpTx.of(UiTheme.light, UiTheme.glassy)
        transport.colors(tx.txColor, tx.txBloom)
        tint.light = UiTheme.light
        invalidate()
    }

    override fun bind(s: CardState) {
        val old = state
        state = s
        title.text = s.track.title
        artist.text = s.track.artist
        cover.set(s.cover, s.car.playing)
        if (s.cover !== old.cover) { tint.set(s.cover, this); invalidate() }
        transport.playing = s.car.source == AudioSource.BT && s.car.playing
        position()
        removeCallbacks(tick)
        if (s.car.playing && isAttachedToWindow) postDelayed(tick, TICK_MS)
    }

    private fun position() {
        val s = state
        val at = PlaybackClock.elapsedMs(s.positionMs, s.positionAt, s.car.playing, s.track.durationMs, System.currentTimeMillis())
        progress.set(if (s.track.durationMs > 0) at.toFloat() / s.track.durationMs else 0f)
        elapsed.text = mediaTime(at)
        remaining.text = "-" + mediaTime(s.track.durationMs - at)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (state.car.playing) postDelayed(tick, TICK_MS) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        surface.draw(canvas, this)
        canvas.save(); surface.clip(canvas, this)
        tint.draw(canvas, width, height)
        canvas.restore()
    }

    private companion object { const val TICK_MS = 250L }
}

/** `.np-tint`: the cover blurred over the page (inset −20 %) under a dark wash (by day a light one), baked per cover. */
private class CoverTint {
    var light = false; set(v) { if (field != v) { field = v; source?.let { bake(it) } } }
    private var source: Bitmap? = null
    private var baked: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wash = Paint()
    private var washH = -1
    private var washLight = false
    private val dst = RectF()
    private var density = 1f

    fun set(cover: Bitmap?, view: View) {
        density = view.resources.displayMetrics.density
        source = cover
        if (cover == null) { baked = null; return }
        bake(cover)
    }

    /** A small copy of the cover, blurred and saturated: what `filter: blur(60px) saturate(…)` leaves of it at the card's size. */
    private fun bake(cover: Bitmap) {
        val n = BAKE_PX
        val small = Bitmap.createScaledBitmap(cover, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        // the page is ~376 x 670 px drawn at 140 %: the cover spans ~938 css px, so 60 px of blur is n * 60 / 938 here
        CssBlur.gaussian(px, n, n, CssBlur.sigma(60f) * n / SPAN)
        CssFilter.apply(CssFilter.saturateBrightness(if (light) 1.7f else 1.4f, 1f), px)
        val out = small.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, n, 0, 0, n, n)
        if (small !== cover && small !== out) small.recycle()
        baked = out
    }

    fun draw(canvas: Canvas, w: Int, h: Int) {
        baked?.let { b ->
            // object-fit: cover over a box 140 % of the page, centred
            val bw = w * 1.4f; val bh = h * 1.4f
            val s = maxOf(bw, bh)
            dst.set((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f)
            paint.alpha = ((if (light) 0.7f else 0.55f) * 255).toInt()
            canvas.drawBitmap(b, null, dst, paint)
        }
        if (washH != h || washLight != light) {
            washH = h; washLight = light
            wash.shader = if (light) LinearGradient(0f, 0f, 0f, h.toFloat(), 0x0FF2F4F7, 0x85F2F4F7.toInt(), Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, 0f, h.toFloat(), 0x3306080B, 0x9E06080B.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), wash)
    }

    private companion object {
        const val BAKE_PX = 64
        const val SPAN = 938f
    }
}

/**
 * `.np-cover`: the cover filling the column's width and the height left, 12 px round with a soft drop; at 0.94
 * while paused, it grows to 1 on play (780 ms `--spring-cover`) and back (500 ms). No cover: `--glass-strong` with
 * the music notes in `--ink-subtle` (glass on Glassy).
 */
@SuppressLint("ViewConstructor")
class CoverView(context: Context) : KitView(context) {
    private val box = CssBox().apply { radius = 12f; shadows = listOf(Shadow(false, 0f, 6f, 18f, 0f, 0x47000000)) }
    private val empty = CssBox().apply { radius = 12f }
    private val notes = context.getDrawable(Icons.MUSIC_NOTES)!!.mutate()
    private val scale = Transition(0.94f)
    private var bitmap: Bitmap? = null
    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = android.graphics.Path()

    init { uiKey = "np-cover"; onTheme() }

    override fun onTheme() {
        if (UiTheme.glassy) empty.glassy(UiTheme.glass) else empty.solid(UiTheme.base.glassStrong)
        notes.setTint(UiTheme.base.inkSubtle)
    }

    fun set(cover: Bitmap?, playing: Boolean) {
        if (cover !== bitmap) { bitmap = cover; invalidate() }
        val to = if (playing) 1f else 0.94f
        if (to != scale.target) {
            if (isAttachedToWindow) scale.to(to, Motion.now(), UiTheme.duration(if (playing) 780 else 500), if (playing) Motion.easing(Base.DARK.springCover) else SETTLE)
            else scale.snap(to)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val s = scale.value(now)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.save()
        canvas.scale(s, s, w / 2f, h / 2f)
        box.draw(canvas, this, w, h)
        val b = bitmap
        if (b == null) {
            empty.draw(canvas, this, w, h)
            IconView.place(notes, 0f, 0f, w, h, 64 * d)
            notes.draw(canvas)
        } else {
            // object-fit: cover, clipped to the rounded box
            val r = 12 * d
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW)
            canvas.clipPath(clip)
            val k = maxOf(w / b.width, h / b.height)
            val sw = w / k; val sh = h / k
            src.set(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt())
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(b, src, dst, paint)
        }
        canvas.restore()
        if (scale.running(now)) postInvalidateOnAnimation()
    }

    private companion object { val SETTLE = CssBezier(0.2f, 0.9f, 0.25f, 1f) }
}

/**
 * NowPlaying.tsx `Radio`: the station on the card, tinted by its genre (700 ms `--seamless` between genres): band
 * and state tags, the frequency large, the station's name and genre (a tap opens the radio app), the favourite star,
 * the transport.
 */
@Mirrors("NowPlaying.tsx")
@SuppressLint("ViewConstructor")
class RadioPage(context: Context, private val actions: CardActions) : Column(context, 12f), CardPageView {
    override val view: View get() = this
    private val surface = NpSurface()
    private val genre = ColorTransition(Genre.color(0))
    private val wash = Paint()
    private var washKey = 0L
    private val band = Tag(context, true)
    private val state = Tag(context, false)
    private val num = context.line(TextStyle(96f, 600, -0.04f, 1f), "freq-num", false)
    private val unit = context.line(TextStyle(24f, 500), "freq-band", false)
    private val name = context.line(TextStyle(24f, 600, -0.01f), "np-title.np-radio-name")
    private val genreChip = KitText(context, TextStyle(17f, 600)).apply { uiKey = "radio-genre"; gravity = Gravity.CENTER; isSingleLine = true }
    private val genreBox = CssBox().apply { radius = 17f }
    private val genreFrame = object : FrameLayout(context) {
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) = genreBox.draw(canvas, this, width.toFloat(), height.toFloat())
    }
    private val star = StarButton(context) { actions.toggleFavorite(khz) }
    private val transport = TransportRow(context, TransportRow.CARD, 4f) { actions.pressSound(CardPage.RADIO, PRESSES[it]) }
    private var khz = 0
    private var pty = -1

    init {
        uiKey = "np.np-radio"
        setWillNotDraw(false)
        val d = resources.displayMetrics.density
        setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (38 * d).toInt())
        val tags = Row(context, 8f).apply {
            uiKey = "radio-tags"
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minimumHeight = (28 * d).toInt()
            addView(band, LinearLayout.LayoutParams(-2, (28 * d).toInt()))
            addView(state, LinearLayout.LayoutParams(-2, (28 * d).toInt()))
        }
        val freq = BaselineRow(context, 12f).apply { uiKey = "freq"; addView(num); addView(unit) }
        genreFrame.uiKey = "radio-genre"
        genreChip.uiKey = null
        genreFrame.addView(genreChip, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        genreFrame.setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        val open = Column(context, 8f).apply {
            uiKey = "np-open"
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { actions.openApp(App.RADIO) }
            addView(tags, LinearLayout.LayoutParams(-1, -2))
            addView(freq, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
            addView(name, LinearLayout.LayoutParams(-1, -2))
            addView(genreFrame, LinearLayout.LayoutParams(-2, (34 * d).toInt()).apply { gravity = Gravity.START })
        }
        addView(open, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(star, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(transport, LinearLayout.LayoutParams(-1, -2))
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        val b = UiTheme.base
        // the genre's gradient is the page's whole background (no glass fill, no sheen), under its edge and lines
        surface.theme(0)
        surface.box.gradient = null
        surface.box.under = drawWash
        num.setTextColor(b.ink); unit.setTextColor(b.inkMuted); name.setTextColor(b.ink)
        genreChip.setTextColor(b.ink)
        val tx = ro.rdstr.ui.tokens.NpTx.of(UiTheme.light, UiTheme.glassy)
        transport.colors(tx.txColor, tx.txBloom)
        band.theme(); state.theme(); star.onThemeChanged()
        washKey = 0L
        chip()
        invalidate()
    }

    private fun chip() {
        val g = genre.value(Motion.now())
        val b = UiTheme.base
        val fill = if (UiTheme.light) 0xA8FFFFFF.toInt() else Genre.mix(g, 0.34f, b.glassStrong)
        if (UiTheme.glassy) genreBox.glassy(UiTheme.glass, fill) else genreBox.solid(fill)
        genreFrame.invalidate()
    }

    override fun bind(s: CardState) {
        val r = s.radio
        khz = r.khz
        band.text = r.band.name
        val tag = when { s.car.source != AudioSource.RADIO -> "Off"; !s.car.radioOn -> "Paused"; r.stereo -> "Stereo"; else -> null }
        state.visibility = if (tag == null) GONE else VISIBLE
        if (tag != null) state.text = tag
        num.text = FreqDisplay.format(r.band, r.khz)
        unit.text = if (r.band == CarEvent.Radio.Band.FM) "MHz" else "kHz"
        name.text = r.rds.ifEmpty { "Radio" }
        val g = Rds.ptyName(r.pty)
        genreFrame.visibility = if (g.isEmpty()) GONE else VISIBLE
        genreChip.text = g
        star.on = s.favorite
        transport.playing = s.car.source == AudioSource.RADIO && s.car.radioOn
        if (r.pty != pty) {
            val to = Genre.color(r.pty)
            if (pty < 0 || !isAttachedToWindow) genre.snap(to) else genre.to(to, Motion.now(), UiTheme.duration(700), Motion.seamless)
            pty = r.pty
            chip(); invalidate()
        }
    }

    private val drawWash: (Canvas) -> Unit = { c -> c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wash) }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        // radio.css .np.np-radio: the genre over the glass, redrawn while the genre fades
        val g = genre.value(now)
        val key = (g.toLong() shl 32) or (height.toLong() shl 1) or (if (UiTheme.light) 1L else 0L)
        if (key != washKey) {
            washKey = key
            val b = UiTheme.base
            val h = height.toFloat()
            wash.shader = if (UiTheme.light) {
                LinearGradient(0f, 0f, 0f, h, intArrayOf(Genre.mix(g, 0.58f, -1), Genre.mix(g, 0.22f, -1), Genre.mix(g, 0.08f, b.glassStrong)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
            } else LinearGradient(0f, 0f, 0f, h, intArrayOf(Genre.mix(g, 0.26f, b.glass), b.glass), floatArrayOf(0f, 0.72f), Shader.TileMode.CLAMP)
        }
        surface.draw(canvas, this)
        if (genre.running(now)) { chip(); postInvalidateOnAnimation() }
    }

    /** `.radio-tag`: 28 px, 14 px round, 14/600; the band in `--sheet` on `--ink`, the state in `--ink-muted` on `--glass-strong`. */
    private class Tag(context: Context, private val isBand: Boolean) : FrameLayout(context) {
        private val label = KitText(context, TextStyle(14f, 600)).apply { gravity = Gravity.CENTER; isSingleLine = true }
        private val box = CssBox().apply { radius = 14f }
        var text: CharSequence
            get() = label.text
            set(v) { label.text = v }

        init {
            uiKey = if (isBand) "radio-tag" else "radio-tag"
            setWillNotDraw(false)
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            addView(label, LayoutParams(-2, -1, Gravity.CENTER))
        }

        fun theme() {
            val b = UiTheme.base
            val (ink, fill) = when {
                isBand -> b.sheet to b.ink
                UiTheme.light -> b.ink to 0x99FFFFFF.toInt()
                else -> b.inkMuted to b.glassStrong
            }
            label.setTextColor(ink)
            if (UiTheme.glassy) {
                box.glassy(UiTheme.glass, fill)
                // the band is a tinted key (sheen); the state keeps its solid colour under the frost and shadow
                if (!isBand) box.gradient = null
            } else box.solid(fill)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
    }
}

/** Children on one baseline (`display: flex; align-items: baseline`), [gap] canvas px apart. */
internal class BaselineRow(context: Context, gap: Float) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        isBaselineAligned = true
        dividerDrawable = object : android.graphics.drawable.ColorDrawable(0) {
            val s = (gap * resources.displayMetrics.density + 0.5f).toInt()
            override fun getIntrinsicWidth() = s
        }
        showDividers = SHOW_DIVIDER_MIDDLE
    }
}

/**
 * RadioApp.tsx `Star` on the card (`.np-radio .radio-star`): 52 px round on `--glass-strong`, the star 24 px in
 * `--ink-muted`, `#ffd60a` when a favourite (by day `#c98f00`); pressed 0.88 (120 ms), back on `--spring-glyph`.
 */
@SuppressLint("ViewConstructor")
class StarButton(context: Context, private val onToggle: () -> Unit) : KitView(context) {
    var on = false; set(v) { if (field != v) { field = v; glyph = icon(); onTheme(); invalidate() } }
    private var glyph = icon()
    private val box = CssBox().apply { radius = 999f }
    private val scale = Transition(1f)
    private var down = false

    init { uiKey = "radio-star"; isClickable = true; setOnClickListener { onToggle() }; onTheme() }

    private fun icon() = context.getDrawable(if (on) Icons.STAR_FILL else Icons.STAR)!!.mutate()

    fun onThemeChanged() = onTheme()

    override fun onTheme() {
        val b = UiTheme.base
        glyph.setTint(if (!on) b.inkMuted else if (UiTheme.light) 0xFFC98F00.toInt() else 0xFFFFD60A.toInt())
        // Glassy: the card's star keeps the card rule's fill, under the frost and the glass shadow
        if (UiTheme.glassy) { box.glassy(UiTheme.glass, if (down) UiTheme.glass.glassyFillStrong else b.glassStrong); box.gradient = null } else box.solid(b.glassStrong)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (52 * d + 0.5f).toInt()
        setMeasuredDimension(s, s)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (pressed == down) return
        down = pressed
        scale.to(if (pressed) 0.88f else 1f, Motion.now(), UiTheme.duration(if (pressed) 120 else 300), Motion.springGlyph)
        onTheme(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val s = scale.value(now)
        val w = width.toFloat()
        canvas.save(); canvas.scale(s, s, w / 2, w / 2)
        box.draw(canvas, this, w, w)
        IconView.place(glyph, 0f, 0f, w, w, 24 * d)
        glyph.draw(canvas)
        canvas.restore()
        if (scale.running(now)) postInvalidateOnAnimation()
    }
}
