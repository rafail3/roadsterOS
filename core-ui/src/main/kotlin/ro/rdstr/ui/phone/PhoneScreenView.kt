package ro.rdstr.ui.phone

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import ro.rdstr.car.CarEvent
import ro.rdstr.car.ShellAction
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.GlassButton
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons

/**
 * PhoneScreenApp.tsx: the phone's screen as the Miracast receiver decodes it, fitted inside ours with black bands
 * (the stream is 16:9); until a phone shares, how to start from the phone. The close key fades 3 s after the last
 * touch while the picture plays, and takes no taps while faded.
 */
@Mirrors("PhoneScreenApp.tsx")
@SuppressLint("ViewConstructor")
class PhoneScreenView(context: Context, private val actions: Actions) : Box(context, "cast-app") {
    interface Actions {
        fun shellAction(action: ShellAction)
        fun setCastVisible(on: Boolean)
        /** store.castPicture: the surface the receiver decodes into, null when it goes. */
        fun castPicture(surface: Surface?)
    }

    private val picture = add(SurfaceView(context)) { abs = true; left = 0f; top = 15f; w = 1280f; h = 720f }
    private val status = add(Box(context, "cast-status")) { maxW = 640f }
    private val glyph = status.add(Box(context, "cast-glyph")) { w = 112f; h = 112f; mb = 12f }
    private val glyphIcon = glyph.add(icon(context, "i-device-mobile", 56f)) { w = 56f; h = 56f }
    private val title = status.add(txt(context, 34f, 700, "cast-title", tracking = -0.01f).apply { centered = true; multi = true }) { shrink = true }
    private val text = status.add(txt(context, 20f, 400, "cast-text", lineHeight = 1.4f).apply { centered = true; multi = true }) { shrink = true }
    private val action = status.add(Box(context, "cast-action")) { mt = 16f; minH = 56f }
    private val close = add(Box(context, "glass-button.cast-close")) { abs = true; top = 76f; left = 32f; w = 56f; h = 56f }
    private val closeButton = close.add(GlassButton(context, Icons.CARET_DOWN)) { abs = true; left = 0f; top = 0f; w = 56f; h = 56f }
    private var live = false
    private var open = false
    private var controls = true
    private val hideControls = Runnable { controls = false; fade() }

    init {
        lp.w = 1280f; lp.h = 750f
        dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER
        isClickable = true
        contentDescription = "Phone screen"
        picture.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = actions.castPicture(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = actions.castPicture(null)
        })
        status.dir = Dir.COLUMN; status.align = Align.CENTER; status.gap = 12f
        glyph.dir = Dir.STACK; glyph.align = Align.CENTER; glyph.justify = Align.CENTER; glyph.css.radius = 28f
        action.dir = Dir.STACK; action.align = Align.CENTER; action.justify = Align.CENTER; action.padL = 32f; action.padR = 32f; action.css.radius = 28f
        val actionTxt = action.add(txt(context, 19f, 600, null, "Make Visible"))
        action.contentDescription = "Make Visible"
        action.tappable { actions.setCastVisible(true) }
        close.add(Mark(context, "icon.i-caret-down")) { abs = true; left = 14f; top = 14f; w = 28f; h = 28f }
        closeButton.contentDescription = "Close phone screen"
        closeButton.setOnClickListener { actions.shellAction(ShellAction.Home) }
        onTheme = {
            val ink = Ink(false)
            ink.solid(this, BLACK)
            if (ink.glassy) ink.glass(glyph, BLACK) else ink.solid(glyph, Ink.FIELD)
            glyphIcon.color = -1; title.color = -1; text.color = INK_2; actionTxt.color = -1
            if (ink.glassy) ink.tinted(action, Ink.BLUE, ACTION_DOWN) else ink.solid(action, Ink.BLUE, ACTION_DOWN)
        }
        visibility = INVISIBLE
    }

    /** [cast] from the receiver, [visible] Visible to Phones, [device] the phone sharing, [name] ours (CarEvent.PhoneCast.NAME). */
    fun bind(open: Boolean, cast: CarEvent.PhoneCast.State, visible: Boolean, device: String, name: String = CarEvent.PhoneCast.NAME) {
        if (open != this.open) {
            this.open = open
            // .cast-app: a 220 ms fade, no slide
            animate().cancel()
            if (open) { visibility = VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(UiTheme.base.seamless)).start() }
            else animate().alpha(0f).setDuration(UiTheme.duration(220)).setInterpolator(Motion.interpolator(UiTheme.base.seamless)).withEndAction { if (!this.open) visibility = INVISIBLE }.start()
        }
        val wasLive = live
        live = cast == CarEvent.PhoneCast.State.LIVE
        picture.visibility = if (live) VISIBLE else GONE
        picture.contentDescription = if (live) "Screen of $device" else null
        status.visibility = if (live) GONE else VISIBLE
        val (t, s) = PhoneText.castStatus(visible, cast, device, name)
        title.text = t; text.text = s
        action.visibility = if (visible) GONE else VISIBLE
        if (live != wasLive) touched()
        requestLayout()
    }

    /** Any touch brings the close key back; while the picture plays it fades again 3 s later. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) touched()
        return super.dispatchTouchEvent(e)
    }

    private fun touched() {
        controls = true
        removeCallbacks(hideControls)
        if (live) postDelayed(hideControls, CONTROLS_MS)
        fade()
    }

    private fun fade() {
        val faded = live && !controls
        closeButton.isEnabled = !faded
        closeButton.isClickable = !faded
        close.animate().alpha(if (faded) 0f else 1f).setDuration(UiTheme.duration(300)).setInterpolator(Motion.interpolator(UiTheme.base.seamless)).start()
    }

    private companion object {
        const val CONTROLS_MS = 3000L
        const val BLACK = 0xFF000000.toInt()
        /** `.cast-text { color: rgba(235, 235, 245, 0.6) }` */
        const val INK_2 = 0x99EBEBF5.toInt()
        /** `.cast-action:active { background: #0060df }` */
        const val ACTION_DOWN = 0xFF0060DF.toInt()
    }
}
