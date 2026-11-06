package ro.rdstr.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.SparseArray
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.WifiBars
import ro.rdstr.ui.home.Column
import ro.rdstr.ui.home.Row
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Notice
import ro.rdstr.ui.tokens.NoticeIsOff
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

/** A connection that came or went (store.ts `connectionNotice`): Bluetooth with the phone's name, or Wi-Fi with the network's. */
data class ConnectionNote(val id: Int, val wifi: Boolean, val connected: Boolean, val name: String) {
    companion object {
        /**
         * The notice for a change from ([btBefore], [wifiBefore]) to ([bt], [wifi]), or null: Bluetooth first, then
         * Wi-Fi, named by the network joined or the one left.
         */
        fun of(id: Int, btBefore: Boolean, bt: Boolean, device: String, wifiBefore: String?, wifi: String?): ConnectionNote? {
            if (btBefore != bt) return ConnectionNote(id, false, bt, device)
            val network = wifi ?: wifiBefore
            if (wifiBefore != wifi && network != null) return ConnectionNote(id, true, wifi != null, network)
            return null
        }
    }
}

/**
 * ConnectionNotice.tsx: a pill at the bottom whenever Bluetooth or Wi-Fi connects or drops, for 3 s; a newer one
 * replaces it, plays its icon and text in again and restarts the time. It rises on `--spring-rise` (640 ms) and sinks
 * away (240 ms); hidden while a call or the camera fills the screen. With the map offer on screen it stands 12 px over
 * the offer's 84 px, so the two never cover each other.
 */
@Mirrors("ConnectionNotice.tsx")
class NoticeView(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val pill = Pill(context)
    private val rise = Transition(0f)
    private val opacity = Transition(0f)
    private val lift = Transition(0f)
    private var note: ConnectionNote? = null
    private var shown = false
    private var hidden = false
    private val timeout = Runnable { shown = false; place() }

    init {
        uiKey = null
        clipChildren = false
        addView(pill, LayoutParams(-2, (68 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (44 * d).toInt() })
        pill.alpha = 0f
    }

    /** [mapOffer]: the map offer shows (the notice then stands over it). */
    fun bind(note: ConnectionNote?, hidden: Boolean, mapOffer: Boolean) {
        if (note != null && note.id != this.note?.id) {
            this.note = note
            pill.show(note)
            shown = true
            removeCallbacks(timeout); postDelayed(timeout, VISIBLE_MS)
        }
        val to = if (mapOffer) (84 + 12).toFloat() else 0f
        if (to != lift.target) lift.to(to, Motion.now(), UiTheme.duration(UiTheme.base.base), Motion.easeOut)
        this.hidden = hidden
        place()
    }

    private fun place() {
        val on = shown && !hidden
        val now = Motion.now()
        if (on) {
            rise.to(1f, now, UiTheme.duration(640), Motion.easing(Notice.DARK.springRise))
            opacity.to(1f, now, UiTheme.duration(220), Motion.easeOut)
        } else {
            rise.to(0f, now, UiTheme.duration(240), SINK)
            opacity.to(0f, now, UiTheme.duration(200), SINK)
        }
        if (!isAttachedToWindow) { rise.snap(rise.target); opacity.snap(opacity.target); lift.snap(lift.target) }
        frame()
    }

    private fun frame() {
        val now = Motion.now()
        val p = rise.value(now)
        // translate(-50%, 160%) scale(0.9) when away
        pill.translationY = (1 - p) * 1.6f * pill.height - lift.value(now) * d
        val s = 0.9f + 0.1f * p
        pill.scaleX = s; pill.scaleY = s
        pill.alpha = opacity.value(now)
        if (rise.running(now) || opacity.running(now) || lift.running(now)) postOnAnimation { frame() }
    }

    override fun onDetachedFromWindow() { removeCallbacks(timeout); super.onDetachedFromWindow() }

    override fun dispatchTouchEvent(ev: MotionEvent?) = false

    /** `div.notice`: 68 px tall, 300 to 520 wide, on `--sheet-raised` (glass on Glassy); a disc, the title and the name. */
    private class Pill(context: Context) : Row(context, 16f) {
        private val d = resources.displayMetrics.density
        private val box = CssBox().apply { radius = 34f; border = 1f }
        private val disc = Disc(context)
        private val title = KitText(context, TextStyle(16f, 600, lineHeight = 1.25f)).apply { uiKey = "notice-title"; isSingleLine = true }
        private val name = KitText(context, TextStyle(14f, lineHeight = 1.25f)).apply { uiKey = "notice-name"; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        private val text = Column(context, 2f).apply { uiKey = "notice-text"; addView(title); addView(name) }
        private val enter = Transition(1f)
        private var off = false

        init {
            uiKey = "notice"
            setWillNotDraw(false)
            minimumWidth = (300 * d).toInt()
            setPadding((12 * d).toInt(), 0, (32 * d).toInt(), 0)
            addView(disc, LayoutParams((44 * d).toInt(), (44 * d).toInt()))
            addView(text, LayoutParams(-2, -2))
            UiTheme.link(this) { theme() }
        }

        private fun theme() {
            val b = UiTheme.base
            if (UiTheme.glassy) box.glassy(UiTheme.glass) else { box.solid(b.sheetRaised); box.shadows = listOf(Shadow(false, 0f, 6f, 16f, 0f, 0x47000000)) }
            box.borderColor = b.hairlineStrong
            title.setTextColor(b.ink); name.setTextColor(b.inkMuted)
            disc.look(off)
            invalidate()
        }

        fun show(n: ConnectionNote) {
            off = !n.connected
            title.text = "${if (n.wifi) "Wi-Fi" else "Bluetooth"} ${if (n.connected) "connected" else "disconnected"}"
            name.text = n.name
            disc.set(n.wifi)
            theme()
            // the replacing notice plays its icon and text in again
            disc.pop(); enter.snap(0f); enter.to(1f, Motion.now() + 60, UiTheme.duration(380), Motion.easeOut); text.alpha = 0f; textIn()
        }

        private fun textIn() {
            val now = Motion.now()
            val p = enter.value(now)
            text.alpha = p
            text.translationY = (1 - p) * 6 * d
            if (enter.running(now)) postOnAnimation { textIn() }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            text.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val max = (520 * d).toInt()
            super.onMeasure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), heightMeasureSpec)
        }

        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
    }

    /** `span.notice-disc`: 44 px, `--disc` (on-blue; dimmed when off, with a slash), the glyph white (off: `--ink-muted`). */
    private class Disc(context: Context) : KitView(context) {
        private val box = CssBox().apply { radius = 999f }
        private val bt = IconView(context, Icons.BLUETOOTH, 24f)
        private val bars = WifiBars(context).apply { level = 3 }
        private var wifi = false
        private var off = false
        private val pop = Transition(1f)
        private val slash = Transition(1f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var disc = 0
        private var ink = -1

        init { uiKey = "notice-disc"; onTheme() }

        override fun onTheme() = look(off)

        fun set(wifi: Boolean) { this.wifi = wifi; invalidate() }

        fun look(off: Boolean) {
            this.off = off
            disc = if (off) NoticeIsOff.of(UiTheme.light, UiTheme.glassy).disc else Notice.of(UiTheme.light, UiTheme.glassy).disc
            ink = if (off) UiTheme.base.inkMuted else -1
            if (UiTheme.glassy) box.glassy(UiTheme.glass, disc) else box.solid(disc)
            bt.color = ink; bars.color = ink
            invalidate()
        }

        fun pop() {
            val now = Motion.now()
            pop.snap(0f); pop.to(1f, now + UiTheme.duration(80), UiTheme.duration(560), Motion.easing(Notice.DARK.springRise))
            slash.snap(0f); slash.to(1f, now + UiTheme.duration(260), UiTheme.duration(260), Motion.easeOut)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val s = (24 * d).toInt()
            for (v in listOf(bt, bars)) { v.measure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY)); v.layout(0, 0, s, s) }
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            val p = pop.value(now)
            val w = width.toFloat(); val c = w / 2
            canvas.save()
            canvas.scale(0.4f + 0.6f * p, 0.4f + 0.6f * p, c, c)
            box.draw(canvas, this, w, w)
            canvas.save(); canvas.translate(c - 12 * d, c - 12 * d)
            if (wifi) bars.draw(canvas) else bt.draw(canvas)
            canvas.restore()
            if (off) {
                // ::after: a 30 x 2.5 bar across, ringed in the disc's colour, drawn in from its centre
                val k = slash.value(now)
                canvas.save(); canvas.rotate(-45f, c, c); canvas.scale(k, 1f, c, c)
                paint.color = disc
                canvas.drawRoundRect(c - 17 * d, c - 3.25f * d, c + 17 * d, c + 3.25f * d, 4 * d, 4 * d, paint)
                paint.color = ink
                canvas.drawRoundRect(c - 15 * d, c - 1.25f * d, c + 15 * d, c + 1.25f * d, 2 * d, 2 * d, paint)
                canvas.restore()
            }
            canvas.restore()
            if (pop.running(now) || slash.running(now)) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val VISIBLE_MS = 3000L
        val SINK = CssBezier(0.4f, 0f, 1f, 1f)
    }
}

/**
 * DevOverlays.tsx: the Developer section's tools on our screens: a dot under each finger and the frame rate in a
 * corner. (The animation speed is UiTheme.speed, which every view's durations follow.) The touches come from the
 * shell's root ([ShellView.touchSpy]).
 */
@Mirrors("DevOverlays.tsx")
class DevOverlaysView(context: Context) : KitView(context), Choreographer.FrameCallback {
    var touches = false; set(v) { if (field != v) { field = v; dots.clear(); spy(); invalidate() } }
    var fps = false; set(v) { if (field != v) { field = v; frames = 0; since = 0L; if (v) Choreographer.getInstance().postFrameCallback(this) else Choreographer.getInstance().removeFrameCallback(this); invalidate() } }
    private val dots = SparseArray<FloatArray>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF30D158.toInt() }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private var rate = 0
    private var frames = 0
    private var since = 0L

    init { uiKey = "dev-overlays"; onTheme() }

    override fun onTheme() { ro.rdstr.ui.kit.Type.apply(label, TextStyle(15f, 600), context) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); spy() }

    private fun spy() { (parent as? ShellView)?.touchSpy = if (touches) ::onScreenTouch else null }

    /** Every touch on the screen, before any view takes it. */
    fun onScreenTouch(e: MotionEvent) {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> dots.put(e.getPointerId(i), floatArrayOf(e.getX(i), e.getY(i)))
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) dots.get(e.getPointerId(p))?.let { it[0] = e.getX(p); it[1] = e.getY(p) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> dots.remove(e.getPointerId(i))
            MotionEvent.ACTION_CANCEL -> dots.clear()
        }
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!fps) return
        if (since == 0L) since = frameTimeNanos
        frames++
        val elapsed = frameTimeNanos - since
        if (elapsed >= 1_000_000_000L) { rate = Math.round(frames * 1e9 / elapsed).toInt(); frames = 0; since = frameTimeNanos; invalidate() }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow() }

    override fun dispatchTouchEvent(event: MotionEvent?) = false

    override fun onDraw(canvas: Canvas) {
        if (touches) {
            ring.strokeWidth = 2 * d
            for (k in 0 until dots.size()) {
                val p = dots.valueAt(k)
                canvas.drawCircle(p[0], p[1], 22 * d, fill)
                canvas.drawCircle(p[0], p[1], 21 * d, ring)
            }
        }
        if (fps) {
            val text = "$rate fps"
            val m = label.fontMetrics
            val tw = label.measureText(text)
            val r = width - 12 * d
            val b = height - 34 * d
            val h = (m.descent - m.ascent) + 8 * d
            canvas.drawRoundRect(r - tw - 20 * d, b - h, r, b, 8 * d, 8 * d, chip)
            canvas.drawText(text, r - 10 * d - tw, b - 4 * d - m.descent, label)
        }
    }
}
