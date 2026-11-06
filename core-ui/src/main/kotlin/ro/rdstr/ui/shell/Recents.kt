package ro.rdstr.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import ro.rdstr.car.App
import ro.rdstr.car.CarEvent
import ro.rdstr.car.FreqDisplay
import ro.rdstr.car.Rds
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.home.Column
import ro.rdstr.ui.home.Genre
import ro.rdstr.ui.home.Row
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBlur
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssFilter
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.uiKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** What the open-apps screen shows of each app. */
data class RecentsState(
    val open: Boolean = false,
    /** Our apps open, most recent first (Shell `running`, without HOME). */
    val apps: List<App> = emptyList(),
    val track: CarEvent.Track = CarEvent.Track(""),
    val cover: Bitmap? = null,
    /** The car's speed while driving, else null ("Offline map"). */
    val kmh: Int? = null,
    /** The phone connected for calls, or null. */
    val phone: String? = null,
    val radio: CarEvent.Radio = CarEvent.Radio(87500, CarEvent.Radio.Band.FM),
)

interface RecentsActions {
    fun openApp(app: App)
    fun closeApp(app: App)
    fun closeAll()
    fun close()
}

/**
 * Recents.tsx: our apps open right now as cards over the screen they were opened from, blurred (one small snapshot
 * of the screen blurred when the list opens, as the unit has no live blur). A tap returns to an app, a push up (120
 * px, or a flick) closes it, the cross closes it too, Close all closes every one; a tap beside the cards closes the
 * list. Five or six cards are wider than the screen: the row scrolls sideways, and a sideways drag on a card scrolls
 * it. Distances in canvas px.
 */
@Mirrors("Recents.tsx")
@SuppressLint("ViewConstructor")
class RecentsView(context: Context, private val actions: RecentsActions) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val list = Row(context, 32f).apply { uiKey = "recents-list"; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
    private val scroller = object : HorizontalScrollView(context) {
        init { isHorizontalScrollBarEnabled = false; isFillViewport = true; clipChildren = false; clipToPadding = false; overScrollMode = OVER_SCROLL_NEVER }
        // the cards' own drags decide; the row scrolls only when a card says so
        override fun onInterceptTouchEvent(ev: MotionEvent) = false
    }
    private val clear = ClearButton(context) { actions.closeAll() }
    private val empty = KitText(context, TextStyle(20f)).apply { uiKey = "recents-empty"; text = "No open apps"; gravity = Gravity.CENTER }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
    private val tint = Paint()
    private var snapshot: Bitmap? = null
    private val snapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var state = RecentsState()
    private val cards = HashMap<App, Card>()

    init {
        uiKey = "recents"
        setWillNotDraw(false)
        clipChildren = false
        alpha = 0f
        visibility = GONE
        contentDescription = "Open apps"
        val pad = (32 * d).toInt()
        list.setPadding(pad, 0, pad, 0)
        scroller.addView(list, LayoutParams(-2, -2))
        column.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        column.addView(clear, LinearLayout.LayoutParams(-2, (56 * d).toInt()).apply { topMargin = (48 * d).toInt() })
        column.addView(empty, LinearLayout.LayoutParams(-2, -2))
        addView(column, LayoutParams(-1, -2, Gravity.CENTER))
        isClickable = true
        setOnClickListener { actions.close() }
        list.isClickable = true
        list.setOnClickListener { actions.close() }
        UiTheme.link(this) { theme() }
    }

    private fun theme() {
        val b = UiTheme.base
        tint.color = ro.rdstr.ui.kit.CssColor.fade(b.sheet, 0.55f)
        empty.setTextColor(b.inkMuted)
        clear.theme()
        for (c in cards.values) c.theme()
        invalidate()
    }

    fun bind(s: RecentsState) {
        val opening = s.open && !state.open
        state = s
        if (opening) { capture(); visibility = VISIBLE }
        animate().cancel()
        animate().alpha(if (s.open) 1f else 0f).setDuration(UiTheme.duration(UiTheme.base.base)).setInterpolator(Motion.interpolator(Motion.easeOut))
            .withEndAction { if (!state.open) { visibility = GONE; snapshot = null } }.start()
        val apps = s.apps.filter { it != App.HOME }
        if (list.childCount != apps.size || apps.indices.any { (list.getChildAt(it) as Card).app != apps[it] }) {
            list.removeAllViews()
            for (a in apps) list.addView(cards.getOrPut(a) { Card(context, a) }.also { it.reset() })
            cards.keys.retainAll(apps.toSet())
        }
        for (c in cards.values) c.bind(s)
        scroller.visibility = if (apps.isEmpty()) GONE else VISIBLE
        clear.visibility = if (apps.isEmpty()) GONE else VISIBLE
        empty.visibility = if (apps.isEmpty()) VISIBLE else GONE
        if (opening) for ((i, c) in (0 until list.childCount).map { list.getChildAt(it) as Card }.withIndex()) c.enter(i)
    }

    /** The screen under the list, a quarter of its size, blurred 28 px and saturated 1.2. */
    private fun capture() {
        val root = parent as? View ?: return
        if (root.width == 0) return
        val w = (root.width + 3) / 4; val h = (root.height + 3) / 4
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(0.25f, 0.25f)
        val was = visibility
        visibility = INVISIBLE
        root.draw(c)
        visibility = was
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        CssBlur.gaussian(px, w, h, CssBlur.sigma(28f) * d / 4)
        CssFilter.apply(CssFilter.saturateBrightness(1.2f, 1f), px)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        snapshot = bmp
    }

    override fun onDraw(canvas: Canvas) {
        snapshot?.let { dst.set(0f, 0f, width.toFloat(), height.toFloat()); canvas.drawBitmap(it, null, dst, snapPaint) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tint)
    }

    /** `li.recents-card`: its head (icon, name, close) and its body, the preview; pushed up it flies off and closes. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class Card(context: Context, val app: App) : Column(context, 8f) {
        private val icon = IconView(context, META.getValue(app).second, 22f).apply { uiKey = "icon" }
        private val name = KitText(context, TextStyle(16f, 500)).apply { uiKey = "span"; text = META.getValue(app).first }
        private val close = CloseKey(context) { leave() }
        private val body = Body(context, app)
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var sideways: Boolean? = null
        private var left = 0
        private var leaving = false

        init {
            uiKey = "recents-card"
            clipChildren = false
            close.contentDescription = "Close ${META.getValue(app).first}"
            val head = Row(context, 8f).apply {
                uiKey = "recents-card-head"
                setPadding((8 * d).toInt(), 0, 0, 0)
                addView(icon); addView(name, LinearLayout.LayoutParams(0, -2, 1f)); addView(close, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
            }
            addView(head, LinearLayout.LayoutParams((280 * d).toInt(), (56 * d).toInt()))
            addView(body, LinearLayout.LayoutParams((280 * d).toInt(), (360 * d).toInt()))
            body.contentDescription = "Open ${META.getValue(app).first}"
            body.setOnTouchListener { _, e -> drag(e); true }
            theme()
        }

        fun theme() { val b = UiTheme.base; icon.color = b.ink; name.setTextColor(b.ink); close.theme(); body.theme() }

        fun bind(s: RecentsState) = body.bind(s)

        fun reset() { leaving = false; animate().cancel(); translationY = 0f; alpha = 1f }

        /** `recents-in`: up 24 px and in over 260 ms. */
        fun enter(i: Int) {
            if (UiTheme.speed <= 0f) return
            translationY = 24 * d; alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(UiTheme.duration(260)).setInterpolator(Motion.interpolator(Motion.easeOut)).start()
        }

        private fun leave() {
            if (leaving) return
            leaving = true
            animate().cancel()
            animate().translationY(-1.2f * height).alpha(0f).setDuration(UiTheme.duration(LEAVE_MS)).setInterpolator(Motion.interpolator(Motion.easeOut)).start()
            // a timer, not the animation's end: the app closes after the fly-up whatever the animation does
            postDelayed({ actions.closeApp(app) }, UiTheme.duration(LEAVE_MS))
        }

        private fun drag(e: MotionEvent) {
            if (leaving) return
            val tx = (e.rawX - downX) / d
            val ty = (e.rawY - downY) / d
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; downAt = e.eventTime; sideways = null; left = scroller.scrollX; animate().cancel(); body.isPressed = true }
                MotionEvent.ACTION_MOVE -> {
                    if (sideways == null && hypot(tx, ty) >= TAP_SLOP) { sideways = abs(tx) > abs(ty); body.isPressed = false }
                    if (sideways == true) { scroller.scrollTo(left - (tx * d).toInt(), 0); return }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // only upward travel moves the card; downward is resisted
                    val dy = if (ty < 0) ty else ty / 6
                    translationY = dy * d
                    alpha = max(0.3f, 1 + dy / 400)
                }
                MotionEvent.ACTION_UP -> {
                    body.isPressed = false
                    if (sideways == true) return
                    val speed = -ty / max(1L, e.eventTime - downAt)
                    if (ty < -CLOSE_DISTANCE || (ty < -TAP_SLOP * 4 && speed > CLOSE_SPEED)) return leave()
                    back()
                    if (hypot(tx, ty) < TAP_SLOP) actions.openApp(app)
                }
                MotionEvent.ACTION_CANCEL -> { body.isPressed = false; back() }
            }
        }

        private fun back() = animate().translationY(0f).alpha(1f).setDuration(UiTheme.duration(UiTheme.base.base)).setInterpolator(Motion.interpolator(Motion.easeOut)).start()
    }

    /** `button.recents-card-body`: 360 px, 16 px round, `--sheet-raised` with a `--hairline-strong` edge (glass on Glassy). */
    @SuppressLint("ViewConstructor")
    private class Body(context: Context, private val app: App) : FrameLayout(context) {
        private val d = resources.displayMetrics.density
        private val box = CssBox().apply { radius = 16f; border = 1f }
        private val picture = Picture(context, app)
        private val title = KitText(context, TextStyle(16f, 600)).apply { uiKey = "recents-preview-title"; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        private val sub = KitText(context, TextStyle(16f)).apply { uiKey = "recents-preview-sub"; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        private val meta = Column(context, 4f).apply { uiKey = "recents-preview-meta" }
        private val clip = android.graphics.Path()

        init {
            uiKey = "recents-card-body"
            setWillNotDraw(false)
            isClickable = true
            val p = (16 * d).toInt()
            meta.setPadding(p, p, p, p)
            meta.addView(title); meta.addView(sub)
            if (app != App.PHONE_SCREEN) {
                val preview = Column(context, 0f).apply {
                    uiKey = "recents-preview." + when (app) { App.MUSIC -> "recents-preview-music"; App.RADIO -> "recents-preview-radio"; else -> "recents-preview-maps" }
                    addView(picture, LinearLayout.LayoutParams(-1, (278 * d).toInt()))
                    addView(meta, LinearLayout.LayoutParams(-1, -2))
                }
                addView(preview, LayoutParams(-1, -1))
            }
        }

        fun theme() {
            val b = UiTheme.base
            if (UiTheme.glassy) box.glassy(UiTheme.glass) else box.solid(b.sheetRaised)
            box.borderColor = b.hairlineStrong
            title.setTextColor(b.ink); sub.setTextColor(b.inkMuted)
            picture.theme()
            invalidate()
        }

        fun bind(s: RecentsState) {
            picture.bind(s)
            when (app) {
                App.MUSIC -> { title.text = s.track.title; sub.text = s.track.artist }
                App.MAPS -> { title.text = "Maps"; sub.text = s.kmh?.let { "$it km/h" } ?: "Offline map" }
                App.PHONE -> { title.text = "Phone"; sub.text = s.phone ?: "No phone connected" }
                App.SETTINGS -> { title.text = "Settings"; sub.visibility = GONE }
                App.RADIO -> {
                    title.text = s.radio.rds.ifEmpty { s.radio.band.name }
                    sub.text = Rds.ptyName(s.radio.pty).ifEmpty { if (s.radio.band == CarEvent.Radio.Band.FM) "MHz" else "kHz" }
                }
                else -> Unit
            }
        }

        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())

        override fun dispatchDraw(canvas: Canvas) {
            // overflow: hidden inside the 16 px corners (inside the border)
            clip.reset(); clip.addRoundRect(d, d, width - d, height - d, 15 * d, 15 * d, android.graphics.Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip); super.dispatchDraw(canvas); canvas.restore()
        }
    }

    /** The square picture of a card: the cover, the map's arrow, the handset, the gear, the frequency on its genre. */
    @SuppressLint("ViewConstructor")
    private class Picture(context: Context, private val app: App) : View(context) {
        private val d = resources.displayMetrics.density
        private val glyph = when (app) {
            App.MUSIC -> Icons.MUSIC_NOTES; App.MAPS -> Icons.NAVIGATION_ARROW_FILL; App.PHONE -> Icons.PHONE_FILL
            App.SETTINGS -> Icons.GEAR_SIX; else -> null
        }?.let { context.getDrawable(it)!!.mutate() }
        private val ground = Paint()
        private val glass = CssBox()
        private val image = Paint(Paint.FILTER_BITMAP_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cover: Bitmap? = null
        private var freq = ""
        private var genre = Genre.color(0)
        private val src = android.graphics.Rect()
        private val dst = RectF()

        init {
            uiKey = when (app) {
                App.MUSIC -> "recents-preview-empty"; App.RADIO -> "recents-preview-freq"
                App.PHONE -> "recents-preview-map.recents-preview-phone"; App.SETTINGS -> "recents-preview-map.recents-preview-settings"
                else -> "recents-preview-map"
            }
        }

        fun theme() {
            val b = UiTheme.base
            if (UiTheme.glassy) glass.glassy(UiTheme.glass) else glass.solid(b.glass)
            glyph?.setTint(when (app) { App.MUSIC -> b.inkMuted; App.MAPS -> 0xFF0A84FF.toInt(); App.PHONE -> b.callGreen; else -> 0xFF8E8E93.toInt() })
            ro.rdstr.ui.kit.Type.apply(text, TextStyle(64f, 600, -0.04f), context)
            text.color = -1
            text.textAlign = Paint.Align.CENTER
            ground.shader = null
            invalidate()
        }

        fun bind(s: RecentsState) {
            cover = if (app == App.MUSIC) s.cover else null
            uiKey = if (app == App.MUSIC && cover != null) "img" else uiKey
            freq = FreqDisplay.format(s.radio.band, s.radio.khz)
            if (Genre.color(s.radio.pty) != genre) { genre = Genre.color(s.radio.pty); ground.shader = null }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (ground.shader == null) ground.shader = when (app) {
                App.RADIO -> LinearGradient(0f, 0f, 0f, h, Genre.mix(genre, 0.55f, 0xFF0B0C10.toInt()), 0xFF08090C.toInt(), Shader.TileMode.CLAMP)
                else -> LinearGradient(0f, 0f, 0f, h, 0xFF1C2A3B.toInt(), 0xFF0D1015.toInt(), Shader.TileMode.CLAMP)
            }
            when (app) {
                App.MUSIC -> {
                    val c = cover
                    if (c != null) {
                        val k = max(w / c.width, h / c.height)
                        val sw = w / k; val sh = h / k
                        src.set(((c.width - sw) / 2).toInt(), ((c.height - sh) / 2).toInt(), ((c.width + sw) / 2).toInt(), ((c.height + sh) / 2).toInt())
                        dst.set(0f, 0f, w, h)
                        canvas.drawBitmap(c, src, dst, image)
                        return
                    }
                    glass.draw(canvas, this, w, h)
                    glyph?.let { IconView.place(it, 0f, 0f, w, h, 56 * d); it.draw(canvas) }
                }
                App.RADIO -> {
                    canvas.drawRect(0f, 0f, w, h, ground)
                    val m = text.fontMetrics
                    canvas.drawText(freq, w / 2, h / 2 - (m.ascent + m.descent) / 2, text)
                }
                else -> {
                    canvas.drawRect(0f, 0f, w, h, ground)
                    glyph?.let { g ->
                        IconView.place(g, 0f, 0f, w, h, 56 * d)
                        canvas.save()
                        // the map's arrow points up and right; the handset (upright) and the gear are as drawn
                        if (app == App.MAPS) canvas.rotate(45f, w / 2, h / 2)
                        g.draw(canvas)
                        canvas.restore()
                    }
                }
            }
        }
    }

    /** `button.recents-card-close`: 56 px round, the cross in `--ink-muted`; pressed `--glass-pressed` and `--ink`. */
    @SuppressLint("ViewConstructor")
    private class CloseKey(context: Context, onClose: () -> Unit) : View(context) {
        private val d = resources.displayMetrics.density
        private val x = context.getDrawable(Icons.X)!!.mutate()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ground = ColorTransition(0)

        init { uiKey = "recents-card-close"; isClickable = true; setOnClickListener { onClose() } }

        fun theme() { ground.snap(if (isPressed) UiTheme.base.glassPressed else 0); x.setTint(if (isPressed) UiTheme.base.ink else UiTheme.base.inkMuted); invalidate() }

        override fun setPressed(pressed: Boolean) {
            val was = isPressed
            super.setPressed(pressed)
            if (was == pressed) return
            ground.to(if (pressed) UiTheme.base.glassPressed else 0, Motion.now(), UiTheme.duration(UiTheme.base.fast), Easing.EASE)
            x.setTint(if (pressed) UiTheme.base.ink else UiTheme.base.inkMuted)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            paint.color = ground.value(now)
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
            IconView.place(x, 0f, 0f, width.toFloat(), height.toFloat(), 22 * d)
            x.draw(canvas)
            if (ground.running(now)) postInvalidateOnAnimation()
        }
    }

    /** `button.recents-clear`: Close all, a 56 px pill of `--glass` with a `--hairline` edge; `--glass-pressed` pressed. */
    @SuppressLint("ViewConstructor")
    private class ClearButton(context: Context, onPress: () -> Unit) : FrameLayout(context) {
        private val label = KitText(context, TextStyle(16f, 500)).apply { uiKey = null; text = "Close all"; isSingleLine = true }
        private val box = CssBox().apply { radius = 999f; border = 1f }
        private val fill = ColorTransition(0)

        init {
            uiKey = "recents-clear"
            setWillNotDraw(false)
            isClickable = true
            setOnClickListener { onPress() }
            val p = (32 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
            addView(label, LayoutParams(-2, -2, Gravity.CENTER))
        }

        private fun rest() = if (UiTheme.glassy) UiTheme.glass.glassyFill else UiTheme.base.glass

        fun theme() {
            val b = UiTheme.base
            fill.snap(if (isPressed) b.glassPressed else rest())
            if (UiTheme.glassy) box.glassy(UiTheme.glass, fill.target) else box.solid(fill.target)
            box.borderColor = b.hairline
            label.setTextColor(b.ink)
            invalidate()
        }

        override fun dispatchSetPressed(pressed: Boolean) {
            super.dispatchSetPressed(pressed)
            fill.to(if (pressed) UiTheme.base.glassPressed else rest(), Motion.now(), UiTheme.duration(UiTheme.base.fast), Easing.EASE)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            box.fill = fill.value(now)
            box.draw(canvas, this, width.toFloat(), height.toFloat())
            if (fill.running(now)) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val CLOSE_DISTANCE = 120f
        const val CLOSE_SPEED = 0.8f
        const val TAP_SLOP = 6f
        const val LEAVE_MS = 220L

        /** Recents.tsx `APP_META`. */
        val META = mapOf(
            App.MUSIC to ("Music" to Icons.MUSIC_NOTES), App.RADIO to ("Radio" to Icons.RADIO), App.MAPS to ("Maps" to Icons.NAVIGATION_ARROW),
            App.PHONE to ("Phone" to Icons.PHONE), App.SETTINGS to ("Settings" to Icons.GEAR_SIX), App.PHONE_SCREEN to ("Phone Screen" to Icons.DEVICE_MOBILE),
        )
    }
}
