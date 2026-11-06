package ro.rdstr.os.boot

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import ro.rdstr.car.BootArt
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.setup.BootPainter
import kotlin.math.min

/**
 * The boot screen and the setup's start screen, drawn dot by dot on the unit's 1024x600 pixels as the browser
 * emulator draws them (emulator/components/Boot.tsx), with core-ui's BootPainter. A screen of another size gets the
 * picture scaled to fit.
 */
@Mirrors("Boot.tsx")
class BootView(context: Context, private val mode: Mode, private val version: String = "", private val onDone: () -> Unit) : View(context) {
    enum class Mode { BOOT, START }

    private val art = BootPainter()
    private val start = SystemClock.uptimeMillis()
    private var done = false
    /** The start screen's still parts, drawn once. */
    private val still by lazy { art.startStill(version) }

    init {
        isClickable = true
        contentDescription = if (mode == Mode.BOOT) "roadsterOS is starting" else "Touch to start the setup"
        setOnClickListener { finish() }
    }

    private fun finish() {
        if (done) return
        done = true
        onDone()
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start).toDouble()
        canvas.drawColor(art.background)
        val scale = min(width / BootPainter.W, height / BootPainter.H)
        canvas.save()
        canvas.translate((width - BootPainter.W * scale) / 2, (height - BootPainter.H * scale) / 2)
        canvas.scale(scale, scale)
        if (mode == Mode.BOOT) art.drawBoot(canvas, t) else art.drawStart(canvas, t, still)
        canvas.restore()
        if (mode == Mode.BOOT && t >= BootArt.DONE_MS + BootPainter.HOLD_MS) finish() else postInvalidateOnAnimation()
    }
}
