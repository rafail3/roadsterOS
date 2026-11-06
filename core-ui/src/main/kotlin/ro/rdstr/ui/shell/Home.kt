package ro.rdstr.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.HomeArrange
import ro.rdstr.car.LaunchableApp
import ro.rdstr.ui.R
import ro.rdstr.ui.home.Column
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssGradient
import ro.rdstr.ui.kit.Easing
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

/** An app's tile (`.app-mark`): our apps' pictures, Phone Screen's dark tile with a phone, other apps a tint of their name. */
object Marks {
    /** Screen.tsx `MARKS`; null for an app that is not ours. */
    fun key(id: String): String? = when (id) {
        "MAPS" -> "app-mark-nav"; "MUSIC" -> "app-mark-music"; "PHONE" -> "app-mark-phone"; "RADIO" -> "app-mark-radio"
        "SETTINGS" -> "app-mark-settings"; "PHONE_SCREEN" -> "app-mark-cast"; "CAMERA" -> "app-mark-camera"
        else -> null
    }

    fun picture(id: String): Int? = when (id) {
        "MAPS" -> R.drawable.rdstr_app_maps; "MUSIC" -> R.drawable.rdstr_app_music; "PHONE" -> R.drawable.rdstr_app_phone
        "RADIO" -> R.drawable.rdstr_app_radio; "SETTINGS" -> R.drawable.rdstr_app_settings; "CAMERA" -> R.drawable.rdstr_app_camera
        else -> null
    }

    /** Screen.tsx `hue`: a hue from the app's id. */
    fun hue(id: String): Int = id.fold(7) { h, c -> (h * 31 + c.code) % 360 }

    private val pictures = HashMap<Int, Bitmap>()
    fun bitmap(context: Context, res: Int): Bitmap = pictures.getOrPut(res) { BitmapFactory.decodeResource(context.resources, res) }
}

/**
 * `.app-mark` of [id], [size] px with corner [radius]: its picture (`center / cover`), Phone Screen's gradient with a
 * white phone at 46 %, or another app's gradient from its hue with a 1 px `--hairline-strong` edge. Pressed it sinks to
 * [pressScale] and dims to 0.85 brightness.
 */
@SuppressLint("ViewConstructor")
class AppMark(context: Context, val id: String, private val size: Float, private val radius: Float) : KitView(context) {
    private val picture = Marks.picture(id)?.let { Marks.bitmap(context, it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = Path()
    private val rect = RectF()
    private val glyph = if (id == "PHONE_SCREEN") context.getDrawable(Icons.DEVICE_MOBILE)!!.mutate().apply { setTint(Color.WHITE) } else null
    /** The press (scale 0.94 and dimmed) or the lift while dragged (1.15 with a drop). */
    val scale = Transition(1f)
    var dimmed = false; set(v) { if (field != v) { field = v; invalidate() } }
    var lifted = false
    private val drop = CssBox().apply { shadows = listOf(Shadow(false, 0f, 12f, 24f, 0f, 0x80000000.toInt())) }
    /** The jiggle while arranging: ±2.2° on a 280 ms ease-in-out, each icon 70 ms ahead of the one before. */
    var jiggleFrom = -1L
    var jiggleIndex = 0

    init { uiKey = "app-mark." + (Marks.key(id) ?: "app-mark-other"); onTheme() }

    override fun onTheme() { edge.color = UiTheme.base.hairlineStrong; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (size * d + 0.5f).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        shape.reset(); shape.addRoundRect(rect, radius * d, radius * d, Path.Direction.CW)
        paint.shader = when {
            picture != null -> null
            id == "PHONE_SCREEN" -> CssGradient.line(160f, w.toFloat(), h.toFloat()).let { LinearGradient(it[0], it[1], it[2], it[3], 0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt(), Shader.TileMode.CLAMP) }
            else -> CssGradient.line(145f, w.toFloat(), h.toFloat()).let {
                val hue = Marks.hue(id).toFloat()
                LinearGradient(it[0], it[1], it[2], it[3], hsl(hue, 0.4f, 0.26f), hsl(hue, 0.4f, 0.12f), Shader.TileMode.CLAMP)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val s = scale.value(now)
        val w = width.toFloat()
        canvas.save()
        if (jiggleFrom >= 0 && !lifted) {
            // alternate: there and back every 280 ms, each half eased
            val t = ((now - jiggleFrom + jiggleIndex * 70) % 560) / 280f
            val p = if (t < 1f) EASE_IN_OUT.at(t) else 1f - EASE_IN_OUT.at(t - 1f)
            canvas.rotate(-2.2f + 4.4f * p, w / 2, w / 2)
        }
        canvas.scale(s, s, w / 2, w / 2)
        if (lifted || scale.running(now) && scale.target > 1f) drop.also { it.radius = radius }.draw(canvas, this, w, w)
        val pic = picture
        if (pic != null) {
            canvas.save(); canvas.clipPath(shape)
            canvas.drawBitmap(pic, null, rect, paint)
            canvas.restore()
        } else {
            canvas.drawPath(shape, paint)
            glyph?.let { g -> val gs = w * 0.46f; g.setBounds(((w - gs) / 2).toInt(), ((w - gs) / 2).toInt(), ((w + gs) / 2).toInt(), ((w + gs) / 2).toInt()); g.draw(canvas) }
            if (id != "PHONE_SCREEN") {
                edge.strokeWidth = d
                val h = d / 2
                canvas.drawRoundRect(h, h, w - h, w - h, radius * d - h, radius * d - h, edge)
            }
        }
        if (dimmed) canvas.drawPath(shape, dim)
        canvas.restore()
        if (scale.running(now) || jiggleFrom >= 0) postInvalidateOnAnimation()
    }

    private companion object {
        val EASE_IN_OUT: Easing = CssBezier(0.42f, 0f, 0.58f, 1f)
        /** CSS `hsl()`. */
        fun hsl(h: Float, s: Float, l: Float): Int {
            val a = s * minOf(l, 1 - l)
            fun f(n: Int): Int { val k = (n + h / 30f) % 12f; return Math.round(255 * (l - a * maxOf(-1f, minOf(k - 3, 9 - k, 1f)))) }
            return Color.rgb(f(0), f(8), f(4))
        }
    }
}

/**
 * Screen.tsx `nav.rail > .dock`: Music, Radio, Phone, Maps and Camera as 68 px tiles on a round tinted dock down the
 * left edge (`rgba(255,255,255,.09)` by night, `.55` by day; the glass material on Glassy). A tap sinks a tile to
 * 0.92 on `--spring-glyph`.
 */
@SuppressLint("ViewConstructor")
class Rail(context: Context, onPress: (String) -> Unit) : FrameLayout(context) {
    private val box = CssBox().apply { radius = 30f; border = 1f }
    private val dock = object : Column(context, 14f) {
        init { setWillNotDraw(false); uiKey = "dock" }
        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
    }

    init {
        uiKey = "rail"
        val d = resources.displayMetrics.density
        setPadding((12 * d).toInt(), 0, 0, (24 * d).toInt())
        val p = (14 * d).toInt()
        dock.setPadding(p, p, p, p)
        for ((id, label) in RAIL) {
            val mark = AppMark(context, id, 68f, 16f)
            dock.addView(FrameLayout(context).apply {
                uiKey = "dock-item"
                contentDescription = label
                isClickable = true
                addView(mark)
                setOnClickListener { onPress(id.lowercase()) }
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> mark.scale.to(0.92f, Motion.now(), UiTheme.duration(200), Motion.springGlyph)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mark.scale.to(1f, Motion.now(), UiTheme.duration(200), Motion.springGlyph)
                    }
                    mark.invalidate()
                    false
                }
            })
        }
        addView(dock, LayoutParams(-2, -2, Gravity.CENTER))
        UiTheme.link(this) { theme() }
    }

    private fun theme() {
        val light = UiTheme.light
        val fill = if (light) 0x8CFFFFFF.toInt() else 0x17FFFFFF
        if (UiTheme.glassy) {
            box.glassy(UiTheme.glass, if (light) fill else UiTheme.glass.glassyFill)
            // by day `.screen.light .dock` sets the whole background: no sheen
            if (light) box.gradient = null
        } else {
            box.solid(fill)
            box.shadows = listOf(Shadow(true, 0f, 1f, 0f, 0f, 0x14FFFFFF))
        }
        box.borderColor = if (light) 0x14141C26 else UiTheme.base.hairline
        dock.invalidate()
    }

    private companion object {
        /** Screen.tsx `RAIL`. */
        val RAIL = listOf("MUSIC" to "Music", "RADIO" to "Radio", "PHONE" to "Phone", "MAPS" to "Maps", "CAMERA" to "Camera")
    }
}

/**
 * Screen.tsx `HomeApps`: the icons chosen in the settings, centred in a row, 16 px apart. A press held 500 ms
 * (without 10 px of travel) starts arranging: they jiggle, the held one lifts and follows the finger along the row
 * while the others make room, and the drop moves it (core-car HomeArrange); a red badge takes an icon off home.
 * A move ends the arranging, as does a touch outside the row ([endArranging]); while it lasts a tap opens nothing.
 * When icons come or go, the row re-centres: each glides from where it was (320 ms), a new one grows in (240 ms).
 */
@SuppressLint("ViewConstructor")
class HomeApps(context: Context, private val actions: ShellActions) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private var apps: List<LaunchableApp> = emptyList()
    private var unread = 0
    private val icons = LinkedHashMap<String, AppIcon>()
    var arranging = false; private set
    private var held: AppIcon? = null
    private var from = 0
    private var startX = 0f
    private var dx = 0.0
    private var slot = 0.0
    private var picked = false
    private val hold = Runnable { held?.let { arranging(true); pickUp(it) } }
    private var placed: Map<String, Double> = emptyMap()

    init {
        uiKey = "apps"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
    }

    fun bind(list: List<LaunchableApp>, unread: Int) {
        this.unread = unread
        if (list == apps) { icons.values.forEach { it.badge(unread, arranging) }; return }
        val before = placed
        apps = list
        removeAllViews()
        for ((i, app) in list.withIndex()) {
            val icon = icons.getOrPut(app.id) { AppIcon(context, app) }
            icon.index = i
            icon.translationX = 0f; icon.alpha = 1f; icon.scaleX = 1f; icon.scaleY = 1f
            addView(icon, LayoutParams((100 * d).toInt(), -2).apply { if (i > 0) leftMargin = (16 * d).toInt() })
            icon.badge(unread, arranging)
        }
        icons.keys.retainAll(list.map { it.id }.toSet())
        // after the new layout: glide the ones that stay, grow the new ones (only outside a drag)
        post {
            val now = list.associate { a -> a.id to (icons[a.id]?.left?.toDouble() ?: 0.0) / d }
            if (held == null) for (g in HomeArrange.glides(before, now)) {
                val v = icons[g.id] ?: continue
                if (g.added) {
                    v.alpha = 0f; v.scaleX = 0.6f; v.scaleY = 0.6f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(UiTheme.duration(HomeArrange.GROW_MS)).setInterpolator(Motion.interpolator(GLIDE)).start()
                } else {
                    v.translationX = (g.fromDx * d).toFloat()
                    v.animate().translationX(0f).setDuration(UiTheme.duration(HomeArrange.GLIDE_MS)).setInterpolator(Motion.interpolator(GLIDE)).start()
                }
            }
            placed = now
        }
    }

    /** A touch outside the row ends the arranging. */
    fun endArranging() { if (arranging) arranging(false) }

    private fun arranging(on: Boolean) {
        arranging = on
        val now = Motion.now()
        for (icon in icons.values) { icon.mark.jiggleFrom = if (on) now else -1L; icon.mark.jiggleIndex = icon.index; icon.badge(unread, on); icon.mark.invalidate() }
        contentDescription = if (on) "Apps, arranging: drag an icon or use the arrow keys" else "Apps"
    }

    private fun pickUp(icon: AppIcon) {
        picked = true
        held = icon
        from = apps.indexOfFirst { it.id == icon.app.id }
        slot = if (childCount > 1) (100.0 + 16.0) else icon.width / d.toDouble()
        dx = 0.0
        icon.mark.lifted = true
        icon.mark.scale.to(1.15f, Motion.now(), UiTheme.duration(200), Motion.seamless)
        icon.bringToFront0()
        layoutShifts()
    }

    private fun layoutShifts() {
        val h = held ?: return
        for ((i, app) in apps.withIndex()) {
            val v = icons[app.id] ?: continue
            v.animate().cancel()
            val x = HomeArrange.offset(i, from, dx, slot, apps.size) * d
            if (v === h) v.translationX = x.toFloat()
            else if (v.translationX != x.toFloat()) v.animate().translationX(x.toFloat()).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(Motion.seamless)).start()
        }
    }

    /** One icon's touch: hold to arrange, drag while arranging, tap to open. */
    internal fun touch(icon: AppIcon, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX
                picked = false
                held = null
                if (arranging) pickUp(icon) else { held = icon; postDelayed(hold, HomeArrange.HOLD_MS) }
                icon.press(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val mx = (e.rawX - startX) / d
                if (picked) { dx = mx.toDouble(); parent?.requestDisallowInterceptTouchEvent(true); layoutShifts() }
                else if (HomeArrange.cancelsHold(mx.toDouble())) { removeCallbacks(hold); held = null; icon.press(false) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                icon.press(false)
                val h = held
                if (picked && h != null) drop(h)
                else if (e.actionMasked == MotionEvent.ACTION_UP && HomeArrange.opens(arranging, picked)) actions.launchApp(icon.app.id)
                held = null
            }
        }
        return true
    }

    private fun drop(icon: AppIcon) {
        val count = apps.size
        val to = HomeArrange.dropIndex(from, dx, slot, count)
        icon.mark.lifted = false
        icon.mark.scale.to(1f, Motion.now(), UiTheme.duration(200), Motion.seamless)
        for (v in icons.values) if (v !== icon) { v.animate().cancel(); v.translationX = 0f }
        if (to != from) {
            // the icon is in its new slot after the move: drawn where the finger left it, then gliding the rest
            val settle = HomeArrange.settle(from, dx, slot, count) * d
            held = null
            actions.moveHomeApp(icon.app.id, to - from)
            post {
                icon.translationX = settle.toFloat()
                icon.animate().translationX(0f).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(Motion.seamless)).start()
            }
        } else icon.animate().translationX(0f).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(Motion.seamless)).start()
        if (!HomeArrange.keepsArranging(from, to)) arranging(false)
    }

    internal fun remove(icon: AppIcon) {
        icon.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(UiTheme.duration(HomeArrange.REMOVE_MS)).setInterpolator(Motion.interpolator(Motion.seamless))
            .withEndAction { actions.toggleHomeApp(icon.app.id) }.start()
    }

    /** Screen.tsx `button.app`: the tile, its unread count (Phone) or remove badge (arranging), the name below. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    internal inner class AppIcon(context: Context, val app: LaunchableApp) : Column(context, 8f) {
        val mark = AppMark(context, app.id, 80f, 19f)
        private val name = KitText(context, TextStyle(16f, 500)).apply { uiKey = "span"; text = app.label; gravity = Gravity.CENTER; isSingleLine = true }
        private val count = KitText(context, TextStyle(14f, 700)).apply { uiKey = "app-badge"; gravity = Gravity.CENTER; setTextColor(-1) }
        private val minus = RemoveBadge(context) { remove(this) }
        private val frame = FrameLayout(context).apply { uiKey = null; clipChildren = false }
        private val ink = ColorTransition(0)
        var index = 0

        init {
            uiKey = "app"
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, p)
            frame.addView(mark)
            val dd = resources.displayMetrics.density
            frame.addView(count, FrameLayout.LayoutParams(-2, (24 * dd).toInt(), Gravity.TOP or Gravity.END).apply { topMargin = (-8 * dd).toInt(); rightMargin = (-8 * dd).toInt() })
            frame.addView(minus, FrameLayout.LayoutParams((28 * dd).toInt(), (28 * dd).toInt(), Gravity.TOP or Gravity.END).apply { topMargin = (-9 * dd).toInt(); rightMargin = (-9 * dd).toInt() })
            count.minWidth = (24 * dd).toInt()
            count.setPadding((7 * dd).toInt(), 0, (7 * dd).toInt(), 0)
            count.background = BadgeBack(count)
            addView(frame, LayoutParams(-2, -2))
            addView(name, LayoutParams(-1, -2))
            isClickable = true
            setOnTouchListener { _, e -> touch(this, e) }
            UiTheme.link(this) { val c = UiTheme.base.inkMuted; if (isAttachedToWindow) ink.to(c, Motion.now(), UiTheme.duration(UiTheme.base.fast), Easing.EASE) else ink.snap(c); name.setTextColor(c); (count.background as BadgeBack).ring = UiTheme.base.canvas; minus.invalidate() }
        }

        fun press(on: Boolean) {
            mark.dimmed = on && !arranging
            if (!mark.lifted) mark.scale.to(if (on && !arranging) 0.94f else 1f, Motion.now(), UiTheme.duration(200), Motion.seamless)
            mark.invalidate()
        }

        fun badge(unread: Int, arranging: Boolean) {
            val n = if (app.id == "PHONE") unread else 0
            count.visibility = if (n > 0 && !arranging) VISIBLE else GONE
            count.text = n.toString()
            minus.visibility = if (arranging) VISIBLE else GONE
            contentDescription = if (n > 0) "${app.label}, $n unread messages" else app.label
        }

        fun bringToFront0() { (parent as? ViewGroup)?.let { translationZ = 1f } }
    }

    /** `.app-badge`'s ground: `--call-red`, 12 px round, a 2 px `--canvas` ring. */
    private class BadgeBack(private val view: View) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var ring = 0; set(v) { field = v; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val d = view.resources.displayMetrics.density
            val r = RectF(bounds)
            paint.color = ring; canvas.drawRoundRect(r, 12 * d, 12 * d, paint)
            r.inset(2 * d, 2 * d)
            paint.color = 0xFFF62B31.toInt(); canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** `.app-remove`: a 28 px red disc with a white minus, ringed in `--canvas`, popping in (180 ms); `#d70015` pressed. */
    @SuppressLint("ViewConstructor")
    private class RemoveBadge(context: Context, private val onRemove: () -> Unit) : KitView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val drop = CssBox().apply { radius = 999f; shadows = listOf(Shadow(false, 0f, 2f, 6f, 0f, 0x66000000)) }
        private val pop = Transition(1f)

        init { uiKey = "app-remove"; isClickable = true; contentDescription = "Remove from home"; setOnClickListener { onRemove() }; onTheme() }

        override fun onTheme() = invalidate()

        override fun setVisibility(visibility: Int) {
            if (visibility == VISIBLE && this.visibility != VISIBLE) { pop.snap(0f); pop.to(1f, Motion.now(), UiTheme.duration(180), Motion.seamless) }
            super.setVisibility(visibility)
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            val p = pop.value(now)
            val w = width.toFloat(); val c = w / 2
            canvas.save(); canvas.scale(0.4f + 0.6f * p, 0.4f + 0.6f * p, c, c)
            val a = (p * 255).toInt()
            drop.draw(canvas, this, w, w)
            paint.color = UiTheme.base.canvas; paint.alpha = a; canvas.drawCircle(c, c, c, paint)
            paint.color = if (isPressed) 0xFFD70015.toInt() else 0xFFFF3B30.toInt(); paint.alpha = a; canvas.drawCircle(c, c, c - 2 * d, paint)
            paint.color = Color.WHITE; paint.alpha = a
            canvas.drawRoundRect(c - 6 * d, c - 1.25f * d, c + 6 * d, c + 1.25f * d, 2 * d, 2 * d, paint)
            canvas.restore()
            if (pop.running(now)) postInvalidateOnAnimation()
        }
    }

    private companion object { val GLIDE = CssBezier(0.2f, 0f, 0f, 1f) }
}

/**
 * Screen.tsx `section.field`: the clock block ([clock], where Clock.tsx's view goes) centred above the app icons,
 * which sit at the bottom (32 24 px in from the top and bottom, 24 32 from the sides) and reach 24 px up into the
 * clock's room.
 */
@SuppressLint("ViewConstructor")
class Field(context: Context, val clock: FrameLayout, val apps: HomeApps) : ViewGroup(context) {
    private val d = resources.displayMetrics.density

    init {
        uiKey = "field"
        clipChildren = false
        addView(clock); addView(apps)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = w - ((24 + 32) * d).toInt()
        apps.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val ch = h - ((32 + 24) * d).toInt() - apps.measuredHeight + (24 * d).toInt()
        clock.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxOf(0, ch), MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = (24 * d).toInt()
        val top = (32 * d).toInt()
        val bottom = (b - t) - (24 * d).toInt()
        apps.layout(left, bottom - apps.measuredHeight, left + apps.measuredWidth, bottom)
        clock.layout(left, top, left + clock.measuredWidth, top + clock.measuredHeight)
    }
}
