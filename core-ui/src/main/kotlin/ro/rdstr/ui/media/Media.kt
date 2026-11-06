package ro.rdstr.ui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CoverColors
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssBlur
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.CssFilter
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.GlassBead
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// media.tsx: the pieces the music app, the radio app and the home card share. The home card (shell) uses
// TransportRow, TransportButton, ProgressLine, MarqueeText, Crossfade and CoverArt from here.

/** What a media screen asks of the store: the two ways everything on it reaches the car and the shell. */
interface MediaActions {
    /** store `carCommand`. */
    fun carCommand(command: CarCommand)
    /** store `shellAction`. */
    fun shellAction(action: ShellAction)
}

/** Canvas px to whole device px. */
internal fun View.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
internal fun View.dpf(v: Float): Float = v * resources.displayMetrics.density

/** A text of the CSS: [lineHeight] in canvas px, 0 for `normal`. */
internal fun cssText(context: Context, size: Float, weight: Int = 400, tracking: Float = 0f, lineHeight: Float = 0f, key: String? = null) =
    KitText(context, TextStyle(size, weight, tracking, if (lineHeight > 0f) lineHeight / size else 0f)).apply {
        maxLines = 1
        isSingleLine = true
        includeFontPadding = false
        uiKey = key
    }

/** An icon of the emulator by its class (`i-rewind-fill`), keyed as the DOM keys it (`icon.i-rewind-fill`). */
internal fun icon(context: Context, cls: String, size: Float, extra: String = "") =
    IconView(context, Icons.byClass.getValue(cls), size).apply { uiKey = "icon$extra.$cls" }

internal val SEAMLESS get() = Motion.seamless
internal fun interp(e: Easing) = Motion.interpolator(e)

/**
 * The cover's two background colours (core-car `CoverColors`) from a small decode of it, cached per cover, and the
 * blurred copy the music app and the home card lay under everything, made once per cover (no live blur).
 * Shared with the home card.
 */
object CoverArt {
    private val colors = LruCache<Any, Pair<Int, Int>>(32)

    /** media.tsx `useCoverColors`: the fallback pair without a cover. */
    val FALLBACK = CoverColors.FALLBACK_TOP to CoverColors.FALLBACK_BOTTOM

    /** The cover drawn [MediaMath.COVER_SAMPLE] px square, as the emulator samples it, then `CoverColors.from`. */
    fun colors(key: Any, cover: Bitmap): Pair<Int, Int> = colors.get(key) ?: run {
        val small = shrink(cover, MediaMath.COVER_SAMPLE, MediaMath.COVER_SAMPLE)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== cover) small.recycle()
        CoverColors.from(px, MediaMath.COVER_SAMPLE).also { colors.put(key, it) }
    }

    /** [b] scaled to [w] x [h] by halving first, so each pixel is the average of the ones it covers. */
    private fun shrink(b: Bitmap, w: Int, h: Int): Bitmap {
        var cur = b
        while (cur.width / 2 >= w && cur.height / 2 >= h) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== b) cur.recycle()
            cur = next
        }
        if (cur.width == w && cur.height == h) return cur
        return Bitmap.createScaledBitmap(cur, w, h, true).also { if (cur !== b) cur.recycle() }
    }

    /** A blurred cover for a box of [w] x [h] device px, with the margin the blur spreads into ([margin] px of it). */
    class Blurred(val bitmap: Bitmap, val down: Int, val margin: Int)

    /**
     * `img { object-fit: cover; filter: blur([blur]px) saturate([saturate]) }` over a [w] x [h] device px box, made at a
     * quarter of the size (premultiplied blur, transparent around the box, as the browser filters an element).
     */
    fun blurred(cover: Bitmap, w: Int, h: Int, blur: Float, saturate: Float, density: Float): Blurred {
        val down = 4
        val sigma = CssBlur.sigma(blur) * density / down
        val margin = ceil(3 * sigma).toInt() + 1
        val bw = (w + down - 1) / down
        val bh = (h + down - 1) / down
        val out = Bitmap.createBitmap(bw + 2 * margin, bh + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // object-fit: cover, centred
        val s = max(bw.toFloat() / cover.width, bh.toFloat() / cover.height)
        val dw = cover.width * s
        val dh = cover.height * s
        canvas.clipRect(margin, margin, margin + bw, margin + bh)
        val dst = RectF(margin + (bw - dw) / 2f, margin + (bh - dh) / 2f, margin + (bw + dw) / 2f, margin + (bh + dh) / 2f)
        canvas.drawBitmap(cover, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        premultiply(px)
        CssBlur.gaussian(px, out.width, out.height, sigma)
        unpremultiply(px)
        CssFilter.apply(CssFilter.saturateBrightness(saturate, 1f), px)
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return Blurred(out, down, margin)
    }

    private fun premultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]; val a = c ushr 24
            px[i] = (a shl 24) or ((c shr 16 and 0xFF) * a / 255 shl 16) or ((c shr 8 and 0xFF) * a / 255 shl 8) or ((c and 0xFF) * a / 255)
        }
    }

    private fun unpremultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]; val a = c ushr 24
            if (a == 0) { px[i] = 0; continue }
            fun ch(v: Int) = min(255, (v * 255 + a / 2) / a)
            px[i] = (a shl 24) or (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF)
        }
    }
}

/**
 * A blurred cover layer: [CoverArt.Blurred] drawn back at its size over this view's box at [opacity] (the box is the
 * element's, `inset: -15%` of its parent in the music app). Draws past its own bounds by the blur's margin, so its
 * parent does not clip children.
 */
@SuppressLint("ViewConstructor")
class BlurredCover(context: Context, private val art: CoverArt.Blurred, private val opacity: Float) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (opacity * 255 + 0.5f).toInt() }
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        val m = (art.margin * art.down).toFloat()
        dst.set(-m, -m, art.bitmap.width * art.down - m, art.bitmap.height * art.down - m)
        canvas.drawBitmap(art.bitmap, null, dst, paint)
    }
}

/**
 * media.tsx `useCrossfade`: the newest image over the previous ones. A new key adds a layer that fades in over
 * [fadeMs] after [delayMs] on `--seamless`; with [fadeOutOld] the layers under it fade out on the same clock
 * (`.is-leaving`); layers under the newest go once it has faded in, and after [MediaMath.CROSSFADE_LIMIT_MS] at the
 * latest. Shared with the home card.
 */
class Crossfade(context: Context, private val fadeMs: Long, private val delayMs: Long, private val fadeOutOld: Boolean) : FrameLayout(context) {
    private val keys = ArrayList<Any?>()
    private var first = true
    private val cleanup = Runnable { trim(childCount - 1) }

    init { clipChildren = false; clipToPadding = false }

    /** The layer for [key], made by [make] when it is new; [animate] false shows it at once. */
    fun show(key: Any?, animate: Boolean = true, make: () -> View) {
        if (!first && keys.lastOrNull() == key) return
        first = false
        val view = make()
        keys += key
        addView(view)
        val ms = UiTheme.duration(fadeMs)
        if (!animate || ms <= 0L) { trim(childCount - 1); return }
        view.alpha = 0f
        val anims = mutableListOf<Animator>(ObjectAnimator.ofFloat(view, ALPHA, 0f, 1f))
        if (fadeOutOld) for (i in 0 until childCount - 1) anims += ObjectAnimator.ofFloat(getChildAt(i), ALPHA, getChildAt(i).alpha, 0f)
        AnimatorSet().apply {
            playTogether(anims)
            duration = ms
            startDelay = UiTheme.duration(delayMs)
            interpolator = interp(SEAMLESS)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { val i = indexOfChild(view); if (i > 0) trim(i) }
            })
            start()
        }
        removeCallbacks(cleanup)
        postDelayed(cleanup, MediaMath.CROSSFADE_LIMIT_MS)
    }

    /** Removes the layers under [index]. */
    private fun trim(index: Int) {
        repeat(index.coerceAtLeast(0)) { removeViewAt(0); keys.removeAt(0) }
    }
}

/**
 * media.tsx `Marquee`: one line that scrolls slowly (30 px/s) when it does not fit, after a 1.2 s pause, a third of
 * the box between the copies (`p.{class}.marquee` > `span.marquee-text` [+ its copy]). Key it as the paragraph
 * (`music-title.marquee`). Shared with the home card.
 */
class MarqueeText(context: Context, style: TextStyle) : FrameLayout(context) {
    private val first = KitText(context, style).apply { isSingleLine = true; includeFontPadding = false; uiKey = "marquee-text" }
    private val copy = KitText(context, style).apply { isSingleLine = true; includeFontPadding = false; uiKey = "marquee-text"; visibility = GONE }
    private var run: MediaMath.Run? = null
    private var started = 0L
    private val tick = object : Runnable {
        override fun run() {
            val r = run ?: return
            val shift = if (UiTheme.speed <= 0f) 0f else MediaMath.marqueeShift(r, ((Motion.now() - started) / UiTheme.speed).toLong())
            first.translationX = shift; copy.translationX = shift
            postOnAnimation(this)
        }
    }

    var text: CharSequence = ""
        set(v) { if (field.toString() == v.toString()) return; field = v; first.text = v; copy.text = v; contentDescription = v; requestLayout() }

    fun setTextColor(c: Int) { first.setTextColor(c); copy.setTextColor(c) }

    init {
        addView(first, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(copy, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbound = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        first.measure(unbound, unbound)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val next = MediaMath.marquee(first.measuredWidth.toFloat(), w.toFloat())
        if ((next == null) != (run == null) || next?.distance != run?.distance) {
            run = next; started = Motion.now()
            removeCallbacks(tick); first.translationX = 0f; copy.translationX = 0f
            if (next != null && isAttachedToWindow) postOnAnimation(tick)
        }
        // running: each copy carries the gap as its right padding
        val gap = if (next != null) (w * MediaMath.MARQUEE_GAP).roundToInt() else 0
        first.setPadding(0, 0, gap, 0); copy.setPadding(0, 0, gap, 0)
        copy.visibility = if (next != null) VISIBLE else GONE
        first.measure(unbound, unbound); copy.measure(unbound, unbound)
        setMeasuredDimension(w, resolveSize(first.measuredHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        first.layout(0, 0, first.measuredWidth, first.measuredHeight)
        copy.layout(first.measuredWidth, 0, first.measuredWidth + copy.measuredWidth, copy.measuredHeight)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (run != null) { started = Motion.now(); postOnAnimation(tick) } }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}

/** glassy.css `.glass-bead` as a view of its own size (the kit's [GlassBead]); keyed `glass-bead`. */
class BeadView(context: Context, private val size: Float) : View(context) {
    private val bead = GlassBead()
    init { uiKey = "glass-bead" }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(size), dp(size))
    override fun onDraw(canvas: Canvas) = bead.draw(canvas, this, width / 2f, height / 2f, size)
}

/** A view that is its colour, rounded by [radius] canvas px (999 for a pill). */
internal class Fill(context: Context, key: String?) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var color = 0; set(v) { if (field != v) { field = v; invalidate() } }
    var radius = 0f; set(v) { field = v; invalidate() }
    init { uiKey = key }
    override fun onDraw(canvas: Canvas) {
        if (CssColor.alpha(color) == 0) return
        paint.color = color
        val r = min(radius * resources.displayMetrics.density, min(width, height) / 2f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
    }
}

/** Clips a view to a round rect of its size, [radius] canvas px. */
internal fun View.roundClip(radius: Float) {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) =
            outline.setRoundRect(0, 0, view.width, view.height, min(radius * view.resources.displayMetrics.density, min(view.width, view.height) / 2f))
    }
}

/**
 * media.tsx `Progress` (`div.progress-line` > `div.progress-line-fill` [+ `span.glass-bead`]): a pill rail [height]
 * px tall, the played share filled from the left (250 ms linear), on Glassy a recessed glass rail, an accent fill
 * with round ends and a glass bead of [bead] px at the position. Colours are the caller's ([rail], [fill]: Solid's
 * `rgb(var(--on) / .16)` / `.82` in the apps; the home card passes its own); Glassy's [accent].
 * Its parent must not clip children: the bead stands out of the rail. Shared with the home card.
 */
class ProgressLine(context: Context, private val height: Float, private val bead: Float) : ViewGroup(context) {
    private val fillView = Fill(context, "progress-line-fill")
    private val beadView = BeadView(context, bead)
    private val box = CssBox().apply { radius = 999f }
    private val p = Transition(0f)
    var rail = 0; set(v) { field = v; style() }
    var fill = 0; set(v) { field = v; style() }
    var accent = 0; set(v) { field = v; style() }
    private var glassy = false
    private val tick = object : Runnable {
        override fun run() { place(); if (p.running(Motion.now())) postOnAnimation(this) }
    }

    init {
        uiKey = "progress-line"
        setWillNotDraw(false)
        clipChildren = false
        addView(fillView)
        addView(beadView)
        UiTheme.link(this) { style() }
    }

    /** The share played, 0..1; [animate] false jumps (a new track). */
    fun set(fraction: Float, animate: Boolean = true) {
        val now = Motion.now()
        if (animate && UiTheme.speed > 0f) p.to(fraction, now, UiTheme.duration(250), Easing.LINEAR) else p.snap(fraction)
        removeCallbacks(tick)
        place()
        if (p.running(now)) postOnAnimation(tick)
    }

    private fun style() {
        glassy = UiTheme.glassy
        beadView.visibility = if (glassy) VISIBLE else GONE
        clipToOutline = false
        if (glassy) {
            box.fill = 0x24FFFFFF
            box.shadows = listOf(Shadow(true, 0f, 1f, 2f, 0f, 0x59000000), Shadow(false, 0f, 0.5f, 0f, 0f, 0x2EFFFFFF))
            fillView.color = accent; fillView.radius = 999f
        } else {
            box.fill = rail; box.shadows = emptyList()
            fillView.color = fill; fillView.radius = 0f
            roundClip(999f)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dp(height)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
        fillView.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        beadView.measure(0, 0)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        fillView.layout(0, 0, width, measuredHeight)
        val s = beadView.measuredWidth
        val top = ((measuredHeight - dpf(bead)) / 2f).roundToInt()
        beadView.layout(0, top, s, top + s)
        place()
    }

    /** The fill's right edge and the bead's centre at the share shown now. */
    private fun place() {
        val f = p.value(Motion.now()).coerceIn(0f, 1f)
        fillView.right = (width * f).roundToInt()
        beadView.translationX = width * f - beadView.width / 2f
    }

    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), measuredHeight.toFloat())
}

/** Sizes of a transport row: the gap, prev/next's box and glyph, play's box and glyph (`--tx-*`). */
class TxSizes(val gap: Float, val size: Float, val glyph: Float, val playSize: Float, val playGlyph: Float)

/**
 * media.tsx `TransportButton` (`button.tx.tx-{kind}` > `span.icon.tx-glyph.{icon}`): a filled glyph without a plate;
 * pressed, a soft disc blooms behind it (60 ms in, 320 ms out on `--seamless`) and the glyph squeezes (play to 0.2
 * in 220 ms, prev/next to 0.88 in 120 ms), springing back over 485 ms on `--spring-glyph`. After a tap prev/next
 * nudge 8 px their way (340 ms); play stays squeezed until the other glyph replaces it or 600 ms pass, and a new
 * glyph pops in from 0.2 (350 ms `--spring-swap`, fading in 120 ms, the pause glyph 120 ms late). On Glassy it sits
 * on a glass disc inset [plate] of its size (`--glassy-fill-strong` pressed; play's disc `rgb(255 255 255 / .16)`).
 *
 * [color] is `--tx-color`, [bloom] `--tx-bloom` (null: `rgb(var(--on) / .16)` of [on]); [glass] the material under it
 * (the apps keep the night glass by day). Shared with the home card and the map's strip.
 */
@SuppressLint("ViewConstructor")
class TransportButton(context: Context, val kind: Kind, private var size: Float, private var glyphSize: Float) : FrameLayout(context) {
    enum class Kind { PREV, PLAY, NEXT }

    var color = 0xFFFFFFFF.toInt(); set(v) { field = v; glyph.color = v }
    var bloom: Int? = null; set(v) { field = v; invalidate() }
    var on = 0xFFFFFFFF.toInt()
    var plate = 0.17f; set(v) { field = v; invalidate() }
    var glass: () -> Glassy = { UiTheme.glass }
    var onPress: (() -> Unit)? = null

    var playing = false
        set(v) { if (field == v) return; field = v; swap() }

    private var iconClass = iconFor()
    private var glyph = icon(context, iconClass, glyphSize, ".tx-glyph")
    private val bloomAlpha = Transition(0f)
    private val bloomScale = Transition(0.85f)
    private val disc = CssBox().apply { radius = 999f }
    private val discFill = ColorTransition(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var down = false
    private var waitingOn: String? = null
    private val stopWaiting = Runnable { waitingOn = null; squeeze(false) }

    init {
        isClickable = true
        setWillNotDraw(false)
        clipChildren = false
        uiKey = "tx.tx-${kind.name.lowercase()}"
        contentDescription = label()
        addView(glyph, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        glyph.color = color
        setOnClickListener { press() }
        UiTheme.link(this) { discFill.snap(restFill()); invalidate() }
    }

    fun resize(size: Float, glyphSize: Float) { this.size = size; this.glyphSize = glyphSize; glyph.size = glyphSize; requestLayout() }

    private fun iconFor() = when (kind) {
        Kind.PREV -> "i-rewind-fill"
        Kind.NEXT -> "i-fast-forward-fill"
        Kind.PLAY -> if (playing) "i-pause-fill" else "i-play-fill"
    }

    private fun label() = when (kind) { Kind.PREV -> "Previous"; Kind.NEXT -> "Next"; Kind.PLAY -> if (playing) "Pause" else "Play" }

    private fun restFill(): Int = if (kind == Kind.PLAY) 0x29FFFFFF else glass().glassyFill

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.makeMeasureSpec(dp(size), MeasureSpec.EXACTLY)
        super.onMeasure(s, s)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed(false)
            MotionEvent.ACTION_MOVE -> if (e.x < 0 || e.y < 0 || e.x > width || e.y > height) pressed(false)
        }
        return super.onTouchEvent(e)
    }

    /** `:active`: the bloom, the disc's strong fill, the glyph squeezed. */
    private fun pressed(on: Boolean) {
        if (down == on) return
        down = on
        val now = Motion.now()
        if (on) {
            bloomAlpha.to(1f, now, UiTheme.duration(60), Easing.LINEAR); bloomScale.to(1f, now, UiTheme.duration(60), Easing.LINEAR)
        } else {
            bloomAlpha.to(0f, now, UiTheme.duration(320), SEAMLESS); bloomScale.to(0.85f, now, UiTheme.duration(320), SEAMLESS)
        }
        discFill.to(if (on) glass().glassyFillStrong else restFill(), now, UiTheme.duration(UiTheme.base.fast), Motion.easeOut)
        squeeze(on || waitingOn == iconClass)
        invalidate()
    }

    private fun press() {
        onPress?.invoke()
        if (kind != Kind.PLAY) return nudge()
        waitingOn = iconClass
        removeCallbacks(stopWaiting)
        postDelayed(stopWaiting, MediaMath.SWAP_WAIT_MS)
        squeeze(true)
    }

    /** The glyph squeezed (pressed or waiting) or back at rest. */
    private fun squeeze(on: Boolean) {
        val (to, ms, ease) = when {
            !on -> Triple(1f, 485L, Motion.springGlyph)
            kind == Kind.PLAY -> Triple(0.2f, 220L, CssBezier(0.4f, 0f, 0.2f, 1f))
            else -> Triple(0.88f, 120L, CssBezier(0.2f, 0f, 0f, 1f))
        }
        glyph.animate().scaleX(to).scaleY(to).setDuration(UiTheme.duration(ms)).setStartDelay(0).setInterpolator(interp(ease)).start()
    }

    /** `tx-nudge-fwd` / `tx-nudge-back`: 8 px its way at 38 %, back by the end, each half on `--seamless`. */
    private fun nudge() {
        val by = dpf(if (kind == Kind.NEXT) 8f else -8f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UiTheme.duration(340)
            addUpdateListener {
                val t = it.animatedValue as Float
                glyph.translationX = by * if (t < 0.38f) SEAMLESS.at(t / 0.38f) else 1f - SEAMLESS.at((t - 0.38f) / 0.62f)
            }
            start()
        }
    }

    /** Play and pause swap: the new glyph is a new element (keyed by its icon) that pops in. */
    private fun swap() {
        contentDescription = label()
        iconClass = iconFor()
        removeView(glyph)
        glyph = icon(context, iconClass, glyphSize, ".tx-glyph").apply { color = this@TransportButton.color }
        addView(glyph, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (waitingOn != null) { waitingOn = null; removeCallbacks(stopWaiting) }
        if (!isAttachedToWindow || UiTheme.speed <= 0f) return
        glyph.scaleX = 0.2f; glyph.scaleY = 0.2f; glyph.alpha = 0f
        val delay = UiTheme.duration(if (playing) 120 else 0)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glyph, SCALE_X, 0.2f, if (down) 0.2f else 1f).setDuration(UiTheme.duration(350)).apply { interpolator = interp(Motion.springSwap) },
                ObjectAnimator.ofFloat(glyph, SCALE_Y, 0.2f, if (down) 0.2f else 1f).setDuration(UiTheme.duration(350)).apply { interpolator = interp(Motion.springSwap) },
                ObjectAnimator.ofFloat(glyph, ALPHA, 0f, 1f).setDuration(UiTheme.duration(120)),
            )
            startDelay = delay
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val inset = if (UiTheme.glassy) width * plate else 0f
        val r = width / 2f - inset
        if (UiTheme.glassy) {
            val g = glass()
            disc.glassy(g, discFill.value(now))
            canvas.save(); canvas.translate(inset, inset)
            disc.draw(canvas, this, width - 2 * inset, height - 2 * inset)
            canvas.restore()
        }
        val a = bloomAlpha.value(now)
        if (a > 0f) {
            val c = if (UiTheme.glassy) 0x1FFFFFFF else bloom ?: CssColor.withAlpha(on, 0.16f)
            paint.color = CssColor.fade(c, a)
            canvas.drawCircle(width / 2f, height / 2f, r * bloomScale.value(now), paint)
        }
        if (bloomAlpha.running(now) || discFill.running(now)) postInvalidateOnAnimation()
    }
}

/**
 * media.tsx `TransportRow` (`div.transport`): prev, play, next centred, [TxSizes.gap] apart. Shared with the home
 * card (its `--tx-*` sizes and ink) and the radio app.
 */
class TransportRow(context: Context, sizes: TxSizes, onPress: ((Int) -> Unit)? = null) : LinearLayout(context) {
    val prev = TransportButton(context, TransportButton.Kind.PREV, sizes.size, sizes.glyph)
    val play = TransportButton(context, TransportButton.Kind.PLAY, sizes.playSize, sizes.playGlyph)
    val next = TransportButton(context, TransportButton.Kind.NEXT, sizes.size, sizes.glyph)
    private val gapPx = dp(sizes.gap)

    var playing: Boolean
        get() = play.playing
        set(v) { play.playing = v }

    init {
        uiKey = "transport"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        addView(prev); addView(play, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = gapPx; rightMargin = gapPx }); addView(next)
        for (b in buttons) b.layoutParams = (b.layoutParams as LayoutParams).apply { gravity = Gravity.CENTER_VERTICAL }
        // glassy.css `.tx-play { --tx-plate: .15 }`
        play.plate = 0.15f
        if (onPress != null) for ((i, b) in buttons.withIndex()) b.onPress = { onPress(i) }
    }

    val buttons get() = listOf(prev, play, next)

    /** `--tx-color` and `--tx-bloom` of every key. */
    fun colors(ink: Int, bloom: Int) { for (b in buttons) { b.color = ink; b.bloom = bloom } }

    /** `--tx-plate` of every key (the strip's 0.12). */
    fun plates(plate: Float) { for (b in buttons) b.plate = plate }

    companion object {
        /** `.np .tx`: 76 px (play 92), glyphs 36 (48), 4 px apart. */
        val CARD = TxSizes(4f, 76f, 36f, 92f, 48f)
        /** `.np .card-strip .tx`: 46 px (play 52), glyphs 22 (28), no gap; its discs at 0.12 ([plates]). */
        val STRIP = TxSizes(0f, 46f, 22f, 52f, 28f)
    }
}

/**
 * The `.music-app` frame the full-screen apps share (Music, Radio; Phone and Maps take it too): the whole screen,
 * dark by day too (`Immersive`). Opening, it rises from the bottom (420 ms `--sheet-in`) and its content fades in
 * (100 ms). Closing, the content fades first (100 ms), then its outline closes onto the card on home with the
 * card's rounded corners (280 ms `--sheet-in`) while the whole fades (280 ms `cubic-bezier(.4, 0, 1, 1)`).
 *
 * [closeOnto] is the card's box in this frame's layout, canvas px (media.tsx `useCloseOntoCard`; the host measures
 * it); null closes onto the CSS fallback (20 % 2 % 2 % 72 %). Subclasses lay their children out.
 */
abstract class MediaAppFrame(context: Context) : ViewGroup(context) {
    var closeOnto: RectF? = null
    var isOpen = false; private set
    private var clipP = 0f
    private val clip = Path()
    private val clipRect = RectF()
    private var motion: Animator? = null

    init {
        setWillNotDraw(false)
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Opens or closes; [animate] false jumps (a still picture, a start on this app). */
    fun open(open: Boolean, animate: Boolean = true) {
        if (open == isOpen && motion == null) return
        isOpen = open
        motion?.cancel(); motion = null
        importantForAccessibility = if (open) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val run = animate && UiTheme.speed > 0f && isAttachedToWindow
        for (i in 0 until childCount) getChildAt(i).animate().alpha(if (open) 1f else 0f).setDuration(if (run) UiTheme.duration(100) else 0).setInterpolator(interp(Motion.easeOut)).start()
        if (open) {
            visibility = VISIBLE; alpha = 1f; clipP = 0f
            translationY = 0f
            if (run) motion = ObjectAnimator.ofFloat(this, TRANSLATION_Y, height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat(), 0f).apply {
                duration = UiTheme.duration(420); interpolator = interp(Motion.sheetIn)
                addListener(done()); start()
            }
        } else if (!run) { visibility = INVISIBLE; clipP = 1f }
        else motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UiTheme.duration(280)
            val sheet = Motion.sheetIn
            val fade = CssBezier(0.4f, 0f, 1f, 1f)
            addUpdateListener { val t = it.animatedFraction; clipP = sheet.at(t); alpha = 1f - fade.at(t); invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (motion === animation) { motion = null; if (!isOpen) visibility = INVISIBLE } }
            })
            start()
        }
        invalidate()
    }

    private fun done() = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) { if (motion === animation) motion = null }
    }

    override fun draw(canvas: Canvas) {
        if (clipP <= 0f) return super.draw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val card = closeOnto
        val ins = if (card != null) MediaMath.closeOnto(w / d, h / d, card.left, card.top, card.width(), card.height()) else MediaMath.closeFallback(w / d, h / d)
        clipRect.set(ins[3] * d * clipP, ins[0] * d * clipP, w - ins[1] * d * clipP, h - ins[2] * d * clipP)
        val r = UiTheme.base.radius * d * clipP
        clip.reset(); clip.addRoundRect(clipRect, r, r, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip); super.draw(canvas); canvas.restore()
    }

    /** The app's own ground (its gradient, the music app's blurred cover), under everything and under the frost. */
    protected abstract fun drawGround(canvas: Canvas)

    override fun onDraw(canvas: Canvas) = drawGround(canvas)

    /** What the frost blurs: the ground, and what a subclass draws over it under its surfaces. */
    protected open fun frostGround(canvas: Canvas) = drawGround(canvas)

    private var frosted: Bitmap? = null
    private val frostPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val refresh = Runnable { refreshFrost() }

    /**
     * The Glassy frost of this app's surfaces (`backdrop-filter: var(--glassy-frost)`): the ground drawn once at a
     * quarter of the size, blurred, saturated and brightened, and each surface draws the part under itself. Made
     * again when the ground changes ([groundChanged]), never while drawing.
     */
    val frost = FrostSource { c, v -> drawFrost(c, v) }

    /** The ground changed (a new cover, a new genre colour): the frost is made again on the next frame. */
    fun groundChanged() { removeCallbacks(refresh); postOnAnimation(refresh) }

    /** Makes the frost now (a still picture). */
    fun refreshFrost() {
        if (!UiTheme.glassy || width == 0 || height == 0) return
        val down = FROST_DOWN
        val bw = (width + down - 1) / down
        val bh = (height + down - 1) / down
        val bmp = frosted?.takeIf { it.width == bw && it.height == bh } ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0)
        val c = Canvas(bmp)
        c.scale(1f / down, 1f / down)
        frostGround(c)
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        val f = Glassy.GLASSY_DARK.glassyFrost
        CssBlur.gaussian(px, bw, bh, CssBlur.sigma(f.blur) * resources.displayMetrics.density / down)
        CssFilter.apply(CssFilter.saturateBrightness(f.saturate, f.brightness), px)
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
        frosted = bmp
        invalidateAll(this)
    }

    private fun invalidateAll(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateAll(v.getChildAt(i))
    }

    private fun drawFrost(c: Canvas, v: View) {
        val b = frosted ?: return
        var x = 0f
        var y = 0f
        var at: View = v
        while (at !== this) {
            x += at.left + at.translationX - at.scrollX
            y += at.top + at.translationY - at.scrollY
            at = at.parent as? View ?: return
        }
        c.save()
        c.translate(-x, -y)
        c.scale(FROST_DOWN.toFloat(), FROST_DOWN.toFloat())
        c.drawBitmap(b, 0f, 0f, frostPaint)
        c.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = groundChanged()

    private var stopTheme: (() -> Unit)? = null
    override fun onAttachedToWindow() { super.onAttachedToWindow(); stopTheme = UiTheme.observe { groundChanged() } }
    override fun onDetachedFromWindow() { stopTheme?.invoke(); stopTheme = null; super.onDetachedFromWindow() }

    /** Measures [child] at exactly [w] x [h] canvas px; a negative size wraps up to the frame's. */
    protected fun measureAt(child: View, w: Float, h: Float) {
        fun spec(v: Float, max: Int) = if (v >= 0f) MeasureSpec.makeMeasureSpec(dp(v), MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST)
        child.measure(spec(w, measuredWidth), spec(h, measuredHeight))
    }

    /** Places [child] at ([x], [y]) canvas px at its measured size. */
    protected fun place(child: View, x: Float, y: Float) {
        val l = dp(x); val t = dp(y)
        child.layout(l, t, l + child.measuredWidth, t + child.measuredHeight)
    }

    override fun shouldDelayChildPressedState() = false

    private companion object { const val FROST_DOWN = 4 }
}
