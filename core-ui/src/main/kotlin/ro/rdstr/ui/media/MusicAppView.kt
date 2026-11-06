package ro.rdstr.ui.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.CarCommand
import ro.rdstr.car.PhoneGate
import ro.rdstr.car.PlaybackClock
import ro.rdstr.car.Rules
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.GlassButton
import ro.rdstr.ui.kit.HoldRepeat
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Immersive
import ro.rdstr.ui.tokens.MusicSeekGlassBead
import ro.rdstr.ui.tokens.MusicVolumeTrackGlassBead
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.tokens.Tx
import ro.rdstr.ui.tokens.TxPlay
import ro.rdstr.ui.uiKey
import kotlin.math.roundToInt

/** A phone the unit has paired with: its Bluetooth name and major device class (core-car PhoneGate). */
data class SavedPhone(val name: String, val major: Int)

/** What the music app shows: the store's `car`, `track`, `device`, the playback position and the saved phones. */
data class MusicState(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val muted: Boolean = false,
    val volume: Int = Rules.DEFAULT_VOLUME,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    /** The phone's last reported position and when it came (ms since 1970). */
    val positionMs: Long = 0,
    val positionAt: Long = 0,
    /** The connected phone's name. */
    val device: String = "",
    /** The track's cover, decoded; [coverKey] tells covers apart (the crossfade and the colours follow it). */
    val cover: Bitmap? = null,
    val coverKey: Any? = null,
    val saved: List<SavedPhone> = emptyList(),
    /** The saved phone a connection is being tried with, and the last one that did not answer. */
    val connecting: String? = null,
    val failed: String? = null,
)

/** The store calls the music app makes besides commands and shell actions. */
interface MusicActions : MediaActions {
    /** store `setVolume`. */
    fun setVolume(level: Int)
    /** store `stepVolume`. */
    fun stepVolume(delta: Int)
}

/** The immersive apps' custom properties: dark by day too (tokens.css `.screen.light :is(.music-app…)`). */
internal object Night {
    val tokens: Immersive get() = Immersive.of(UiTheme.light, UiTheme.glassy)
    val on get() = tokens.on
    val ink get() = tokens.ink
    /** The night glass, kept in the apps by day (tokens.css:145-150). */
    val glass: Glassy get() = Glassy.GLASSY_DARK
    fun on(a: Float) = CssColor.withAlpha(on, a)
}

/**
 * MusicApp.tsx `MusicApp` (`section.music-app`): Bluetooth music on the whole screen. The cover blurred across the
 * background over its two colours, a large cover (sitting back to 0.92 when paused), title and artist that scroll
 * when long, the timeline with the times and the device, the transport and the volume capsule; without a phone,
 * [NoPhoneView] instead. Dark by day too.
 */
@Mirrors("MusicApp.tsx", "media.tsx")
@SuppressLint("ViewConstructor")
class MusicAppView(context: Context, private val actions: MusicActions) : MediaAppFrame(context) {
    private var state = MusicState()
    /** The clock the position runs on (ms since 1970). */
    var clock: () -> Long = System::currentTimeMillis

    private val backdrop = MusicBackdrop(context)
    private val close = GlassButton(context, Icons.CARET_DOWN).apply {
        uiKey = "glass-button.music-close"; contentDescription = "Close music"
        setOnClickListener { actions.shellAction(ShellAction.Home) }
    }
    private val sound = GlassButton(context, Icons.SND_FADERS).apply {
        uiKey = "glass-button.music-sound"; contentDescription = "Sound"
        setOnClickListener { actions.shellAction(ShellAction.OpenSound) }
    }
    private val devices = GlassButton(context, Icons.BLUETOOTH).apply {
        uiKey = "glass-button.music-devices"; contentDescription = "Devices"
        setOnClickListener { actions.shellAction(ShellAction.OpenDevices) }
    }
    private val cover = MusicCover(context)
    private val side = LinearLayout(context).apply { uiKey = "music-side"; orientation = LinearLayout.VERTICAL; clipChildren = false; gap(dp(32f)) }
    private val meta = LinearLayout(context).apply { uiKey = "music-meta"; orientation = LinearLayout.VERTICAL; gap(dp(4f)) }
    private val title = MarqueeText(context, TextStyle(36f, 600, -0.02f, 44f / 36f)).apply { uiKey = "music-title.marquee"; setTextColor(0xFFFFFFFF.toInt()) }
    private val artist = MarqueeText(context, TextStyle(26f, 400, 0f, 34f / 26f)).apply { uiKey = "music-artist.marquee" }
    private val seek = LinearLayout(context).apply { uiKey = "music-seek"; orientation = LinearLayout.VERTICAL; clipChildren = false }
    private val progress = ProgressLine(context, 8f, MusicSeekGlassBead.DARK.bead)
    private val times = LinearLayout(context).apply { uiKey = "music-times"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val timeStyle = TextStyle(16f, 600, 0.02f)
    private val elapsedText = KitText(context, timeStyle).apply { uiKey = "span"; includeFontPadding = false }
    private val leftText = KitText(context, timeStyle).apply { uiKey = "span"; includeFontPadding = false }
    private val device = Surface(context, "music-device").apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        radius = 999f; glassRule = Surface.GlassRule.BASE; glassPress = true
        isClickable = true
        setOnClickListener { actions.shellAction(ShellAction.OpenDevices) }
    }
    private val deviceIcon = icon(context, "i-bluetooth", 16f)
    private val deviceName = KitText(context, TextStyle(14f, 600, 0.02f)).apply { includeFontPadding = false }
    private val transport = TransportRow(context, TxSizes(36f, Tx.DARK.txSize, Tx.DARK.txGlyph, TxPlay.DARK.txSize, TxPlay.DARK.txGlyph))
    private val volume = MusicVolume(context, actions)
    private val noPhone = NoPhoneView(context, actions, forCalls = false)

    private val tick = object : Runnable {
        override fun run() { position(animate = true); if (state.playing && state.connected) postDelayed(this, MediaMath.TICK_MS) }
    }

    private val topColor = ColorTransition(CoverArt.FALLBACK.first)
    private val bottomColor = ColorTransition(CoverArt.FALLBACK.second)
    private val groundPaint = Paint()
    private var shaderTop = 0
    private var shaderBottom = 0
    private var shaderH = 0

    init {
        uiKey = "music-app"
        contentDescription = "Music"
        clipChildren = false
        addView(backdrop)
        addView(close); addView(sound); addView(devices)
        addView(cover)
        meta.addView(title, LinearLayout.LayoutParams(-1, -2))
        meta.addView(artist, LinearLayout.LayoutParams(-1, -2))
        side.addView(meta, LinearLayout.LayoutParams(-1, -2))
        seek.addView(progress, LinearLayout.LayoutParams(-1, -2))
        device.addView(deviceIcon)
        device.addView(deviceName, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6f) })
        device.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        times.addView(elapsedText)
        times.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        times.addView(device)
        times.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        times.addView(leftText)
        seek.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        side.addView(seek, LinearLayout.LayoutParams(-1, -2))
        side.addView(transport, LinearLayout.LayoutParams(-1, -2))
        side.addView(volume, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        addView(side)
        addView(noPhone)
        transport.prev.onPress = { actions.carCommand(CarCommand.Media.Prev) }
        transport.play.onPress = { actions.carCommand(if (state.playing) CarCommand.Media.Pause else CarCommand.Media.Play) }
        transport.next.onPress = { actions.carCommand(CarCommand.Media.Next) }
        for (s in listOf(device)) s.frost = frost
        volume.frost = frost
        cover.frost = frost
        noPhone.frost = frost
        UiTheme.link(this) { theme() }
        theme()
        bind(state, animate = false)
    }

    private fun theme() {
        val on = Night.on
        artist.setTextColor(Night.on(0.78f))
        val muted = Night.on(0.62f)
        elapsedText.setTextColor(muted); leftText.setTextColor(muted)
        device.fill = Night.on(0.1f); device.pressedFill = Night.on(0.2f)
        deviceIcon.color = muted; deviceName.setTextColor(muted)
        progress.rail = Night.on(0.16f); progress.fill = Night.on(0.82f)
        progress.accent = Base.of(UiTheme.light, UiTheme.glassy).accent
        for (b in transport.buttons) { b.on = on; b.glass = { Night.glass } }
        invalidate()
    }

    /** Shows [s]; [animate] false jumps to it (a still picture). */
    fun bind(s: MusicState, animate: Boolean = true) {
        val before = state
        state = s
        val art = if (s.connected) s.cover else null
        val artKey = if (art != null) s.coverKey ?: art else null
        backdrop.show(artKey, art, animate) { groundChanged() }
        val (top, bottom) = if (art != null) CoverArt.colors(artKey!!, art) else CoverArt.FALLBACK
        backdropColors(top, bottom, animate)
        cover.isVisible(s.connected)
        side.visibility = if (s.connected) VISIBLE else GONE
        noPhone.visibility = if (s.connected) GONE else VISIBLE
        if (!s.connected) noPhone.bind(s.saved, s.connecting, s.failed)
        cover.show(artKey, art, s.title, s.playing, animate)
        if (before.title != s.title || title.text.isEmpty() && s.title.isNotEmpty()) {
            title.text = s.title; artist.text = s.artist
            if (animate && UiTheme.speed > 0f) {
                meta.alpha = 0f
                meta.animate().alpha(1f).setDuration(UiTheme.duration(380)).setStartDelay(UiTheme.duration(200)).setInterpolator(interp(SEAMLESS)).start()
            }
        } else artist.text = s.artist
        deviceName.text = s.device
        device.contentDescription = "${s.device}, choose device"
        transport.play.playing = s.playing
        volume.bind(s.volume, s.muted, Rules.MAX_VOLUME, animate)
        position(animate = animate && before.title == s.title)
        removeCallbacks(tick)
        if (s.playing && s.connected) postDelayed(tick, MediaMath.TICK_MS)
        requestLayout()
    }

    private fun backdropColors(top: Int, bottom: Int, animate: Boolean) {
        val now = Motion.now()
        if (animate && UiTheme.speed > 0f) {
            topColor.to(top, now, UiTheme.duration(800), SEAMLESS); bottomColor.to(bottom, now, UiTheme.duration(800), SEAMLESS)
            postDelayed({ groundChanged() }, UiTheme.duration(800))
        } else { topColor.snap(top); bottomColor.snap(bottom) }
        invalidate()
    }

    private fun position(animate: Boolean) {
        val elapsed = PlaybackClock.elapsedMs(state.positionMs, state.positionAt, state.playing, state.durationMs, clock())
        elapsedText.text = MediaMath.time(elapsed)
        leftText.text = "-" + MediaMath.time(state.durationMs - elapsed)
        progress.set(MediaMath.fraction(elapsed, state.durationMs), animate)
        progress.contentDescription = "${MediaMath.time(elapsed)} of ${MediaMath.time(state.durationMs)}"
    }

    override fun drawGround(canvas: Canvas) {
        val now = Motion.now()
        val top = topColor.value(now)
        val bottom = bottomColor.value(now)
        // linear-gradient(180deg, top 0%, top 60.6%, bottom 100%)
        if (top != shaderTop || bottom != shaderBottom || height != shaderH) {
            shaderTop = top; shaderBottom = bottom; shaderH = height
            groundPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(top, top, bottom), floatArrayOf(0f, 0.606f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), groundPaint)
        if (topColor.running(now) || bottomColor.running(now)) postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val d = resources.displayMetrics.density
        val cw = w / d; val ch = h / d
        measureAt(backdrop, cw, ch)
        for (b in listOf(close, sound, devices)) measureAt(b, 56f, 56f)
        measureAt(cover, 480f, 480f)
        measureAt(side, cw - 112f - 96f - 480f - 80f, -1f)
        measureAt(noPhone, minOf(560f, cw - 208f), -1f)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = resources.displayMetrics.density
        val cw = width / d; val ch = height / d
        place(backdrop, 0f, 0f)
        place(close, 32f, 76f)
        place(devices, cw - 32f - 56f, 76f)
        place(sound, cw - 32f - 56f, 144f)
        // grid: 480 px | 80 | the rest, padding 56 96 0 112, items centred in the rows' height
        val rowH = ch - 56f
        place(cover, 112f, 56f + (rowH - 480f) / 2f)
        place(side, 112f + 480f + 80f, 56f + (rowH - side.measuredHeight / d) / 2f)
        place(noPhone, 112f + (cw - 208f - noPhone.measuredWidth / d) / 2f, 56f + (rowH - noPhone.measuredHeight / d) / 2f)
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (state.playing && state.connected) postDelayed(tick, MediaMath.TICK_MS) }

    /** For the frost: the gradient, then the blurred cover with its scrim as drawn. */
    override fun frostGround(canvas: Canvas) { drawGround(canvas); backdrop.draw(canvas) }
}

/**
 * `.music-backdrop`: the cover blurred 80 px and saturated 1.5 at half opacity over the whole screen (the image box is
 * 130 % of it, centred), under a darkening scrim; made once per cover, crossfading 1100 ms after 360 ms.
 */
internal class MusicBackdrop(context: Context) : FrameLayout(context) {
    private val layers = Crossfade(context, 1100, 360, fadeOutOld = true)
    private val scrim = Paint()
    private var scrimH = 0

    init {
        uiKey = "music-backdrop"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(layers, LayoutParams(-1, -1))
        setWillNotDraw(false)
    }

    fun show(key: Any?, art: Bitmap?, animate: Boolean, settled: () -> Unit) {
        layers.show(key, animate) {
            if (art == null) View(context) else {
                val w = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val h = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                val bw = (w * 1.3f).roundToInt(); val bh = (h * 1.3f).roundToInt()
                BlurredCover(context, CoverArt.blurred(art, bw, bh, 80f, 1.5f, resources.displayMetrics.density), 0.5f).apply {
                    uiKey = "img"
                    layoutParams = LayoutParams(bw, bh).apply { leftMargin = -(w * 0.15f).roundToInt(); topMargin = -(h * 0.15f).roundToInt() }
                }
            }
        }
        if (animate) postDelayed(settled, UiTheme.duration(1100 + 360)) else settled()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // ::after: linear-gradient(180deg, rgba(0,0,0,.1), rgba(0,0,0,.38))
        if (scrimH != height) { scrimH = height; scrim.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x1A000000, 0x61000000, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
    }
}

/**
 * `.music-cover`: 480 px square, rounded 16, sitting back (0.92, a lighter shadow) when paused and forward (1) when
 * playing over 720 ms on `--cover-settle`; the cover crossfades in (450 ms after 90 ms), or the music notes on a
 * pale disc without one (`.music-cover-empty`, glass on Glassy).
 */
internal class MusicCover(context: Context) : FrameLayout(context) {
    private val clip = FrameLayout(context).apply { roundClip(16f) }
    private val layers = Crossfade(context, 450, 90, fadeOutOld = false)
    private val restShadow = CssBox().apply { radius = 16f; shadows = listOf(Shadow(false, 0f, 14f, 36f, 0f, 0x5C000000)) }
    private val playShadow = CssBox().apply { radius = 16f; shadows = listOf(Shadow(false, 0f, 24f, 60f, 0f, 0x80000000.toInt())) }
    private val p = Transition(0f)
    var frost: FrostSource? = null
    private var first = true

    init {
        uiKey = "music-cover"
        setWillNotDraw(false)
        clipChildren = false
        clip.addView(layers, LayoutParams(-1, -1))
        addView(clip, LayoutParams(-1, -1))
    }

    fun isVisible(on: Boolean) { visibility = if (on) VISIBLE else GONE }

    fun show(key: Any?, art: Bitmap?, title: String, playing: Boolean, animate: Boolean) {
        layers.show(key, animate) {
            if (art != null) CoverImage(context, art).apply { contentDescription = "Cover of $title" }
            else Surface(context, "music-cover-empty").apply {
                gravity = Gravity.CENTER
                radius = 16f; glassRule = Surface.GlassRule.BASE; frost = this@MusicCover.frost
                fill = Night.on(0.2f)
                addView(icon(context, "i-music-notes", 120f).apply { color = Night.on(0.6f) })
            }
        }
        val target = if (playing) 1f else 0f
        if (!animate || first || UiTheme.speed <= 0f) p.snap(target) else p.to(target, Motion.now(), UiTheme.duration(720), Motion.easing(UiTheme.base.coverSettle))
        first = false
        apply(Motion.now())
        invalidate()
    }

    private fun apply(now: Long) {
        val s = 0.92f + 0.08f * p.value(now)
        scaleX = s; scaleY = s
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val v = p.value(now)
        restShadow.shadowAlpha = 1f - v; playShadow.shadowAlpha = v
        if (v < 1f) restShadow.draw(canvas, this, width.toFloat(), height.toFloat())
        if (v > 0f) playShadow.draw(canvas, this, width.toFloat(), height.toFloat())
        if (p.running(now)) { apply(now); postInvalidateOnAnimation() }
    }
}

/**
 * `.music-volume`: the volume in its own capsule. The mute key (red while muted), a track the finger drags anywhere
 * along (the bar thickens 10 to 14 px while held, 420 ms `--spring-glyph`), the fill (120 ms linear; 480 ms when
 * the mute changes, VolumeHud.tsx `useMuteFill`), on Glassy an accent fill and a glass bead; the up key repeats while
 * held. There is no down key: the left one mutes.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class MusicVolume(context: Context, private val actions: MusicActions) : Surface(context, "music-volume") {
    private val mute = VolumeStep(context, "music-volume-step.music-mute", "i-speaker-low-fill")
    private val up = VolumeStep(context, "music-volume-step", "i-speaker-high-fill")
    private val track = FrameLayout(context).apply { uiKey = "music-volume-track"; clipChildren = false; contentDescription = "Volume" }
    private val bar = FrameLayout(context).apply { uiKey = "music-volume-bar"; roundClip(999f) }
    private val barFill = Fill(context, null)
    private val fillView = Fill(context, "music-volume-fill")
    private val bead = BeadView(context, MusicVolumeTrackGlassBead.DARK.bead)
    private val shown = Transition(0f)
    private val barH = Transition(10f)
    private var level = 0
    private var muted = false
    private var max = Rules.MAX_VOLUME
    private var dragging = false
    private val tick = object : Runnable {
        override fun run() {
            place()
            val now = Motion.now()
            if (shown.running(now) || barH.running(now)) postOnAnimation(this)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        radius = 999f; border = 1f
        glassRule = GlassRule.BASE
        val pad = dp(4f) + (UiScaleBorder.px(this))
        setPadding(pad, pad, pad, pad)
        gap(dp(4f))
        bar.addView(barFill, FrameLayout.LayoutParams(-1, -1))
        bar.addView(fillView, FrameLayout.LayoutParams(-1, -1))
        track.addView(bar, FrameLayout.LayoutParams(-1, dp(10f), Gravity.CENTER_VERTICAL))
        track.addView(bead, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
        addView(mute)
        addView(track, LinearLayout.LayoutParams(0, dp(48f), 1f))
        addView(up)
        mute.setOnClickListener { actions.carCommand(CarCommand.Audio.Mute(!muted)) }
        HoldRepeat.attach(up, { actions.stepVolume(1) })
        up.contentDescription = "Volume up"
        track.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { drag(true); v.parent.requestDisallowInterceptTouchEvent(true); at(e.x, v.width) }
                MotionEvent.ACTION_MOVE -> if (dragging) at(e.x, v.width)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag(false)
            }
            true
        }
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun at(x: Float, w: Int) {
        if (w <= 0) return
        actions.setVolume(((x / w).coerceIn(0f, 1f) * max).roundToInt())
    }

    private fun drag(on: Boolean) {
        if (dragging == on) return
        dragging = on
        barH.to(if (on) 14f else 10f, Motion.now(), UiTheme.duration(420), Motion.springGlyph)
        removeCallbacks(tick); postOnAnimation(tick)
    }

    private fun theme() {
        material = { Night.glass }
        fill = 0x52171717
        borderColor = Night.on(0.1f)
        val ink = Night.on(0.62f)
        up.ink = ink
        mute.ink = if (muted) Base.of(UiTheme.light, UiTheme.glassy).mute else ink
        barFill.color = Night.on(0.15f)
        fillView.color = if (UiTheme.glassy) Base.of(UiTheme.light, UiTheme.glassy).accent else Night.on(0.7f)
        bead.visibility = if (UiTheme.glassy) VISIBLE else GONE
        mute.frost = frost; up.frost = frost
    }

    fun bind(volume: Int, muted: Boolean, max: Int, animate: Boolean) {
        val muteChanged = muted != this.muted
        level = volume; this.max = max; this.muted = muted
        mute.setIcon(if (muted) "i-speaker-slash-fill" else "i-speaker-low-fill")
        mute.contentDescription = if (muted) "Sound on" else "Mute"
        track.stateDescriptionCompat(if (muted) "Muted" else "$volume")
        theme()
        val target = if (muted || max <= 0) 0f else volume.toFloat() / max
        val now = Motion.now()
        if (!animate || UiTheme.speed <= 0f) shown.snap(target)
        else if (muteChanged) shown.to(target, now, UiTheme.duration(480), CssBezier(0.33f, 1f, 0.68f, 1f))
        else shown.to(target, now, UiTheme.duration(120), Easing.LINEAR)
        removeCallbacks(tick); post { place(); postOnAnimation(tick) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); place() }

    /** The bar's height, the fill's right edge and the bead's centre as shown now. */
    private fun place() {
        val now = Motion.now()
        val h = dp(barH.value(now))
        if (track.height > 0) {
            val top = (track.height - h) / 2
            bar.top = top; bar.bottom = top + h
            barFill.right = bar.width; barFill.bottom = h
            fillView.bottom = h
        }
        val f = shown.value(now).coerceIn(0f, 1f)
        fillView.right = (bar.width * f).roundToInt()
        bead.translationX = track.width * f - bead.width / 2f
    }
}

/** A border's device px: a whole one at least (Chrome's rounding of a 1 px border under the screen's zoom). */
internal object UiScaleBorder {
    fun px(v: View): Int = ro.rdstr.ui.kit.UiScale.borderPx(1f, v.resources.displayMetrics.density).toInt()
}

/** API 30's stateDescription is not there on 27: the value goes into the description. */
internal fun View.stateDescriptionCompat(value: String) { contentDescription = "${contentDescription?.toString()?.substringBefore(',') ?: ""}, $value" }

/** `.music-volume-step`: a round 56 px key, `rgb(var(--on) / .14)` and white while pressed. */
@SuppressLint("ViewConstructor")
internal class VolumeStep(context: Context, key: String, cls: String) : Surface(context, key) {
    private var glyph: IconView = icon(context, cls, 28f)
    var ink = 0; set(v) { field = v; if (!isPressed) glyph.color = v }

    init {
        gravity = Gravity.CENTER
        radius = 999f
        isClickable = true
        addView(glyph)
        UiTheme.link(this) { pressedFill = Night.on(0.14f) }
    }

    fun setIcon(cls: String) {
        if (glyph.uiKey == "icon.$cls") return
        removeView(glyph)
        glyph = icon(context, cls, 28f).apply { color = ink }
        addView(glyph)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.makeMeasureSpec(dp(56f), MeasureSpec.EXACTLY)
        super.onMeasure(s, s)
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        glyph.color = if (pressed) 0xFFFFFFFF.toInt() else ink
    }
}

/**
 * MusicApp.tsx `NoPhone` (`div.music-nophone`): no phone connected. The saved phones to connect with one tap
 * (disabled while one is tried; "Connecting…", "· not available"), then "Other devices"; with none saved, one blue
 * "Pair a phone" key. [forCalls] offers only phones (core-car `PhoneGate.callCapable`): the phone app uses it too.
 */
@Mirrors("MusicApp.tsx")
@SuppressLint("ViewConstructor")
class NoPhoneView(context: Context, private val actions: MediaActions, private val forCalls: Boolean) : LinearLayout(context) {
    var frost: FrostSource? = null
    private val iconDisc = Surface(context, "music-nophone-icon").apply { gravity = Gravity.CENTER; radius = 999f; glassRule = Surface.GlassRule.BASE }
    private val iconGlyph = icon(context, "i-device-mobile", 44f)
    private val heading = cssText(context, 32f, 600, -0.01f, key = "h2").apply { text = "No phone connected"; gravity = Gravity.CENTER }
    private val line = cssText(context, 18f, key = "p").apply { gravity = Gravity.CENTER }
    private val list = LinearLayout(context).apply { uiKey = "music-nophone-list"; orientation = VERTICAL; gap(dp(12f)); contentDescription = "Saved phones" }
    private val more = cssText(context, 18f, 600, key = "music-nophone-more").apply {
        text = "Other devices"; gravity = Gravity.CENTER; isClickable = true
        setPadding(dp(28f), 0, dp(28f), 0)
        setOnClickListener { actions.shellAction(ShellAction.OpenDevices) }
    }
    private val morePress = PressFill(more)
    private val pair = Surface(context, "music-nophone-pair").apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        radius = 999f; fill = BLUE; pressedFill = 0xFF0070E0.toInt(); glassRule = Surface.GlassRule.TINTED
        isClickable = true
        setPadding(dp(36f), 0, dp(36f), 0)
        addView(icon(context, "i-bluetooth", 24f).apply { color = 0xFFFFFFFF.toInt() })
        addView(KitText(context, TextStyle(20f, 600)).apply { includeFontPadding = false; text = "Pair a phone"; setTextColor(0xFFFFFFFF.toInt()) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) })
        setOnClickListener { actions.shellAction(ShellAction.OpenDevices) }
    }
    private var rows: List<SavedPhone> = emptyList()

    init {
        uiKey = "music-nophone"
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        gap(dp(12f))
        iconDisc.addView(iconGlyph)
        addView(iconDisc, LayoutParams(dp(96f), dp(96f)))
        addView(heading, LayoutParams(-2, -2).apply { topMargin = dp(16f) })
        addView(line, LayoutParams(-2, -2))
        addView(list, LayoutParams(-1, -2).apply { topMargin = dp(24f) })
        addView(more, LayoutParams(-2, dp(56f)).apply { topMargin = dp(12f) })
        addView(pair, LayoutParams(-2, dp(64f)).apply { topMargin = dp(24f) })
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        iconDisc.fill = Night.on(0.12f)
        iconGlyph.color = Night.ink
        heading.setTextColor(Night.ink)
        line.setTextColor(Night.on(0.62f))
        more.setTextColor(Night.on(0.86f))
        morePress.color = Night.on(0.12f)
        for (s in listOf(iconDisc, pair)) { s.material = { Night.glass }; s.frost = frost }
        for (i in 0 until list.childCount) ((list.getChildAt(i) as FrameLayout).getChildAt(0) as PhoneRow).theme()
    }

    fun bind(saved: List<SavedPhone>, connecting: String?, failed: String?) {
        val shown = if (forCalls) saved.filter { PhoneGate.callCapable(it.major) } else saved
        val purpose = if (forCalls) "make calls" else "play its music"
        line.text = if (shown.isNotEmpty()) "Connect a phone to $purpose." else "Pair a phone to $purpose."
        if (shown != rows) {
            rows = shown
            list.removeAllViews()
            for (p in shown) list.addView(FrameLayout(context).apply { uiKey = "li"; addView(PhoneRow(context, p.name), FrameLayout.LayoutParams(-1, dp(72f))) }, LayoutParams(-1, -2))
        }
        for (i in 0 until list.childCount) {
            val row = (list.getChildAt(i) as FrameLayout).getChildAt(0) as PhoneRow
            val name = shown[i].name
            row.label = when (name) { connecting -> "Connecting…"; failed -> "$name · not available"; else -> name }
            row.isEnabled = connecting == null
            row.alpha = if (connecting == null) 1f else 0.55f
        }
        list.visibility = if (shown.isNotEmpty()) VISIBLE else GONE
        more.visibility = if (shown.isNotEmpty()) VISIBLE else GONE
        pair.visibility = if (shown.isNotEmpty()) GONE else VISIBLE
    }

    /** `.music-nophone-phone`: a 72 px pill, the Bluetooth glyph in blue and the name; a tap connects that phone. */
    private inner class PhoneRow(context: Context, val name: String) : Surface(context, "music-nophone-phone") {
        private val glyph = icon(context, "i-bluetooth", 26f).apply { color = BLUE }
        private val text = KitText(context, TextStyle(20f, 600)).apply { uiKey = "span"; includeFontPadding = false; isSingleLine = true }
        var label: String = ""; set(v) { field = v; text.text = v }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            radius = 999f; glassRule = GlassRule.BASE
            isClickable = true
            setPadding(dp(28f), 0, dp(28f), 0)
            addView(glyph)
            addView(text, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16f) })
            setOnClickListener { actions.carCommand(CarCommand.Bluetooth.Connect(name)) }
            theme()
        }

        fun theme() {
            fill = Night.on(0.14f); pressedFill = Night.on(0.22f)
            shadows = listOf(Shadow(true, 0f, 1f, 0f, 0f, Night.on(0.14f)))
            text.setTextColor(Night.ink)
            material = { Night.glass }; frost = this@NoPhoneView.frost
        }
    }

    private companion object { const val BLUE = 0xFF0A84FF.toInt() }
}

/** A pressed background on a text key (`:active { background }`), a pill, over `--fast`. */
internal class PressFill(private val view: KitText) {
    var color = 0
    init {
        view.background = object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                if (!view.isPressed) return
                paint.color = color
                val b = bounds
                canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), b.height() / 2f, b.height() / 2f, paint)
            }
            override fun isStateful() = true
            override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }
}
