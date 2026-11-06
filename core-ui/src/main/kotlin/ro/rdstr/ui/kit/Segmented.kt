package ro.rdstr.ui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.MotionEvent
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.tokens.SetSegGlass
import ro.rdstr.ui.tokens.Shadow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SettingsApp.tsx `Segmented`: on Solid `.set-seg` (settings.css), on Glassy glisse's `SegmentedControl` at
 * `.set-seg-glass` (44 px tabs, 17 px labels, glassy.css), chosen as glassy.tsx `useGlisse` chooses.
 *
 * Controlled: [onChange] gets the index tapped (or dragged to), the control shows [selected] as the caller sets it.
 *
 * Solid: segments as wide as their label needs (96 px at least), the chosen one on `rgb(var(--on) / .2)` in `--ink`,
 * the others in `--ink-muted`, both cross-fading 220 ms on `--seamless`.
 * Glassy: equal cells as wide as the longest label needs (SettingsApp.tsx `segmentWidth`), a thumb that springs
 * between them (glisse's rest springs 1024/58.9 and 676/47.8), and, held, lifts into a lens 24 px wider and 16 px
 * taller that follows the finger (drag springs 2500/94 and 576/44.2) and lands on the nearest cell.
 */
@Mirrors("glassy.tsx")
class KitSegmented(context: Context) : KitView(context) {
    var labels: List<String> = emptyList()
        set(v) { field = v; measureLabels(); requestLayout(); invalidate() }
    var selected = -1
        set(v) { if (field == v) return; field = v; changed() }
    var onChange: ((Int) -> Unit)? = null
    /** Solid only: equal segments across the whole width it is given (`flex: 1; min-width: 0` on each button). */
    var stretch = false
        set(v) { field = v; requestLayout() }

    private var glassy = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var baseline = 0f
    private var widths = FloatArray(0)

    // Solid: one background and one ink transition per segment
    private var fills = arrayOf<ColorTransition>()
    private var inks = arrayOf<ColorTransition>()

    // Glassy
    private var tab = 96f
    private val tabH get() = SetSegGlass.of(UiTheme.light).lgTabHeight
    private val x = Spring(0.0, GlisseTokens.SEG_REST[0], GlisseTokens.SEG_REST[1])
    private val lift = Spring(0.0, GlisseTokens.SEG_REST[2], GlisseTokens.SEG_REST[3])
    private val thumbAlpha = Transition(1f)
    private val glowAlpha = Transition(0f)
    private val lensFill = ColorTransition(0)
    private var shown = false
    private var refracting = false
    private var lightPage = false
    private val bar = CssBox()
    private val lens = CssBox()
    private var glow: RadialGradient? = null
    private val glowMatrix = Matrix()
    private var glowX = 0f
    private var lensRim: Bitmap? = null

    // the press
    private var pressing = false
    private var moved = false
    private var downX = 0f
    private var downAt = 0L
    private var anchor = 0f

    init {
        isClickable = true
        onTheme()
    }

    override fun onTheme() {
        val wasGlassy = glassy
        glassy = UiTheme.glassy
        val st = UiTheme.settings
        val seg = TextStyle(17f, if (glassy) 400 else 600)
        Type.apply(text, seg, context)
        Type.apply(textSelected, if (glassy) TextStyle(17f, 500) else seg, context)
        if (glassy) {
            val bounds = Rect()
            text.getTextBounds("H", 0, 1, bounds)
            baseline = -bounds.top / 2f
        } else {
            val m = text.fontMetrics
            baseline = -(m.ascent + m.descent) / 2f
        }
        val now = Motion.now()
        for (i in fills.indices) {
            fills[i].snap(if (i == selected) CssColor.withAlpha(UiTheme.base.on, 0.2f) else 0)
            inks[i].snap(if (i == selected) st.ink else st.inkMuted)
        }
        if (wasGlassy != glassy) { shown = false; settle(instant = true) }
        measureLabels()
        requestLayout()
        if (glassy) glass(now)
    }

    private fun measureLabels() {
        widths = FloatArray(labels.size) { max(96f, text.measureText(labels[it]) / d + 36f) }
        // SettingsApp.tsx segmentWidth: the longest label at 10 px a character, beside 36 px of padding
        tab = max(96f, 36f + 10f * (labels.maxOfOrNull { it.length } ?: 0))
        if (fills.size != labels.size) {
            val st = UiTheme.settings
            fills = Array(labels.size) { ColorTransition(if (it == selected) CssColor.withAlpha(UiTheme.base.on, 0.2f) else 0) }
            inks = Array(labels.size) { ColorTransition(if (it == selected) st.ink else st.inkMuted) }
        }
    }

    /** The glass parts that change with the size or the theme: the track's rim, the lens's rim, the glow. */
    private fun glass(now: Long) {
        val g = UiTheme.glisse
        val w = tab * labels.size
        bar.radius = tabH / 2f
        bar.fill = CssColor.over(g.segmentTrack, g.glass)
        bar.shadows = if (CssColor.alpha(g.drop) > 0) listOf(Shadow(false, 0f, 1f, 3f, 0f, g.drop)) else emptyList()
        bar.image = if (w > 0) GlisseImages.rim(w, tabH, tabH / 2f, g.rimShine, d, g.edge) else null
        lens.radius = tabH / 2f - THUMB_INSET
        // CSS divides the lens's drop by its stretch so it keeps its size; here it is fixed at the full lift's
        lens.shadows = if (CssColor.alpha(g.lensDrop) > 0) listOf(Shadow(false, 0f, 7f / (1 + GROW_Y / (tabH - 2 * THUMB_INSET)), 11f / (1 + GROW_Y / (tabH - 2 * THUMB_INSET)), 0f, g.lensDrop)) else emptyList()
        lensFill.snap(if (pressing) g.segmentLens else 0)
        glow = RadialGradient(0f, 0f, 260f * d, intArrayOf(0x17FFFFFF, 0x0BFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        lightPage = g.lightPage
        val tw = tab - 2 * THUMB_INSET
        val th = tabH - 2 * THUMB_INSET
        lensRim = GlisseImages.selection(tw, th, th / 2f, d, lightPage)
        if (now > 0) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w: Float
        val h: Float
        if (glassy) { w = tab * labels.size; h = tabH } else {
            w = 2 * SOLID_PAD + widths.sum() + SOLID_GAP * max(0, labels.size - 1); h = 2 * SOLID_PAD + SOLID_H
        }
        if (stretch && !glassy && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && labels.isNotEmpty()) {
            val all = MeasureSpec.getSize(widthMeasureSpec)
            val each = (all / d - 2 * SOLID_PAD - SOLID_GAP * (labels.size - 1)) / labels.size
            widths = FloatArray(labels.size) { each }
            setMeasuredDimension(all, resolveSize((h * d + 0.5f).toInt(), heightMeasureSpec))
            return
        }
        // a stretched control keeps its own width on Glassy, whatever its parent gives it
        val mw = if (stretch) (w * d + 0.5f).toInt() else resolveSize((w * d + 0.5f).toInt(), widthMeasureSpec)
        setMeasuredDimension(mw, resolveSize((h * d + 0.5f).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (glassy) glass(0) }

    private fun changed() {
        val now = Motion.now()
        val st = UiTheme.settings
        for (i in fills.indices) {
            fills[i].to(if (i == selected) CssColor.withAlpha(UiTheme.base.on, 0.2f) else 0, now, UiTheme.duration(220), Motion.seamless)
            inks[i].to(if (i == selected) st.ink else st.inkMuted, now, UiTheme.duration(220), Motion.seamless)
        }
        settle()
        invalidate()
    }

    // glisse segments.tsx: move, settle

    private fun move(target: Double, lifted: Boolean, instant: Boolean) {
        val p = if (pressing) GlisseTokens.SEG_DRAG else GlisseTokens.SEG_REST
        if (!x.moving && !lift.moving) restartClock()
        x.tune(p[0], p[1]); lift.tune(p[2], p[3])
        val skip = instant || UiTheme.speed <= 0f
        x.set(target, skip); lift.set(if (lifted) 1.0 else 0.0, skip)
        invalidate()
    }

    private fun settle(instant: Boolean = false) {
        if (pressing) return
        if (selected >= 0) move(selected * tab.toDouble(), false, instant || !shown)
        shown = selected >= 0
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        if (glassy) drawGlass(canvas, now) else drawSolid(canvas, now)
    }

    private fun drawSolid(canvas: Canvas, now: Long) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = SOLID_TRACK
        canvas.drawRoundRect(rect, 12f * d, 12f * d, paint)
        var left = SOLID_PAD
        var running = false
        for (i in labels.indices) {
            rect.set(left * d, SOLID_PAD * d, (left + widths[i]) * d, (SOLID_PAD + SOLID_H) * d)
            paint.color = fills[i].value(now)
            if (CssColor.alpha(paint.color) > 0) canvas.drawRoundRect(rect, 10f * d, 10f * d, paint)
            val p = if (i == selected) textSelected else text
            p.color = inks[i].value(now)
            canvas.drawText(labels[i], rect.centerX(), rect.centerY() + baseline, p)
            running = running || fills[i].running(now) || inks[i].running(now)
            left += widths[i] + SOLID_GAP
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun drawGlass(canvas: Canvas, now: Long) {
        val dt = frameSeconds(now)
        x.step(dt); lift.step(dt)
        val g = UiTheme.glisse
        val h = tabH
        val l = lift.value.coerceIn(0.0, 1.0).toFloat()
        if (!pressing && lift.value < 0.01) refracting = false
        bar.draw(canvas, this, width.toFloat(), height.toFloat())
        // the light under the finger while held (.lg-tab-bar-glass::before)
        val ga = glowAlpha.value(now)
        glow?.takeIf { ga > 0f }?.let {
            glowMatrix.setTranslate(glowX * d, h / 2f * d); it.setLocalMatrix(glowMatrix)
            paint.shader = it; paint.alpha = (ga * 255).roundToInt()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, h / 2f * d, h / 2f * d, paint)
            paint.shader = null; paint.alpha = 255
        }
        val hidden = selected < 0 && !pressing
        // thumb and lens: translateX(x) scale(sx, sy) about their centre
        val tw = tab - 2 * THUMB_INSET
        val th = h - 2 * THUMB_INSET
        val sx = 1 + GROW_X / tw * lift.value.toFloat()
        val sy = 1 + GROW_Y / th * lift.value.toFloat()
        val cx = (THUMB_INSET + x.value.toFloat() + tw / 2f) * d
        val cy = h / 2f * d
        val ta = thumbAlpha.value(now)
        if (!hidden && ta > 0f) {
            canvas.save(); canvas.translate(cx, cy); canvas.scale(sx, sy)
            paint.color = CssColor.fade(g.segmentThumb, ta)
            rect.set(-tw / 2 * d, -th / 2 * d, tw / 2 * d, th / 2 * d)
            canvas.drawRoundRect(rect, th / 2 * d, th / 2 * d, paint)
            canvas.restore()
        }
        val under = refracting && lightPage
        if (!hidden && under) drawLens(canvas, now, cx, cy, sx, sy, tw, th, l)
        for (i in labels.indices) {
            val p = if (i == selected) textSelected else text
            p.color = g.segmentLabel
            canvas.drawText(labels[i], (i * tab + tab / 2f) * d, cy + baseline, p)
        }
        if (!hidden && !under) drawLens(canvas, now, cx, cy, sx, sy, tw, th, l)
        if (x.moving || lift.moving || thumbAlpha.running(now) || glowAlpha.running(now) || lensFill.running(now)) postInvalidateOnAnimation()
    }

    private fun drawLens(canvas: Canvas, now: Long, cx: Float, cy: Float, sx: Float, sy: Float, tw: Float, th: Float, l: Float) {
        lens.fill = lensFill.value(now)
        lens.image = if (refracting) lensRim else null
        lens.shadowAlpha = l
        canvas.save(); canvas.translate(cx, cy); canvas.scale(sx, sy); canvas.translate(-tw / 2 * d, -th / 2 * d)
        lens.draw(canvas, this, tw * d, th * d)
        canvas.restore()
    }

    // glisse segments.tsx attach(): the pointer handlers

    private fun index(px: Float): Int {
        if (glassy) return (px / d / tab).toInt().coerceIn(0, labels.size - 1)
        var left = SOLID_PAD
        for (i in widths.indices) { if (px / d < left + widths[i] + SOLID_GAP / 2) return i; left += widths[i] + SOLID_GAP }
        return labels.size - 1
    }

    private fun position(e: MotionEvent): Double = (anchor + (e.x - downX) / d).toDouble().coerceIn(0.0, tab * (labels.size - 1).toDouble())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (labels.isEmpty() || !isEnabled) return false
        if (!glassy) {
            if (e.actionMasked == MotionEvent.ACTION_UP && e.x >= 0 && e.x <= width && e.y >= 0 && e.y <= height) onChange?.invoke(index(e.x))
            return super.onTouchEvent(e)
        }
        val now = Motion.now()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressing = true; moved = false; downX = e.x; downAt = now
                anchor = index(e.x) * tab
                refracting = true
                glowX = e.x / d
                thumbAlpha.to(0f, now, UiTheme.duration(100), Easing.EASE)
                glowAlpha.to(1f, now, UiTheme.duration(120), Easing.EASE)
                lensFill.to(UiTheme.glisse.segmentLens, now, UiTheme.duration(100), Easing.EASE)
                move(anchor.toDouble(), true, !shown)
                shown = true
            }
            MotionEvent.ACTION_MOVE -> if (pressing) {
                if (!moved && abs(e.x - downX) > 6) moved = true
                if (moved || now - downAt >= GlisseTokens.HOLD_MS) parent?.requestDisallowInterceptTouchEvent(true)
                if (moved) move(position(e), true, false)
                glowX = (e.x / d).coerceIn(0f, width / d)
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (pressing) {
                val i = (position(e) / tab).roundToInt()
                release(now)
                val near = e.y >= -24 && e.y <= height + 24
                if (moved || near) { onChange?.invoke(i); post { settle() } } else settle()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (pressing) { release(now); settle() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun release(now: Long) {
        pressing = false
        thumbAlpha.to(1f, now, UiTheme.duration(140), Easing.EASE)
        glowAlpha.to(0f, now, UiTheme.duration(160), Easing.EASE)
        lensFill.to(0, now, UiTheme.duration(140), Easing.EASE)
        invalidate()
    }

    private companion object {
        // settings.css .set-seg
        const val SOLID_PAD = 3f
        const val SOLID_GAP = 2f
        const val SOLID_H = 48f
        const val SOLID_TRACK = 0x3D767680
        // glisse .lg-segmented
        const val THUMB_INSET = 2f
        const val GROW_X = 24f
        const val GROW_Y = 16f
    }
}

/** glisse's rim images, made once per size and theme (see [GlisseMaps]). */
internal object GlisseImages {
    private val cache = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun rim(w: Float, h: Float, r: Float, shine: Float, density: Float, edge: Int): Bitmap {
        val key = "r$w,$h,$r,$shine,$density,$edge"
        return cache.get(key) ?: GlisseMaps.rimSize(w, h, density).let { s ->
            Bitmap.createBitmap(GlisseMaps.rim(w, h, r, shine, density, edge), s[0], s[1], Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
        }
    }

    fun selection(w: Float, h: Float, r: Float, density: Float, bright: Boolean): Bitmap {
        val key = "s$w,$h,$r,$density,$bright"
        return cache.get(key) ?: GlisseMaps.selectionSize(w, h, density).let { s ->
            Bitmap.createBitmap(GlisseMaps.selection(w, h, r, density, bright), s[0], s[1], Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
        }
    }
}
