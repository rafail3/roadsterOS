package ro.rdstr.os.settings

import android.view.Choreographer

/** Frames drawn per second on the main thread, reported once a second (Show Frame Rate). */
class FrameMeter(private val onRate: (Int) -> Unit) : Choreographer.FrameCallback {
    private var running = false
    private var frames = 0
    private var since = 0L

    fun start() {
        if (running) return
        running = true
        frames = 0
        since = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (since == 0L) since = frameTimeNanos
        frames++
        val elapsed = frameTimeNanos - since
        if (elapsed >= NANOS_PER_SECOND) {
            onRate((frames * NANOS_PER_SECOND / elapsed).toInt())
            frames = 0
            since = frameTimeNanos
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private companion object { const val NANOS_PER_SECOND = 1_000_000_000L }
}
