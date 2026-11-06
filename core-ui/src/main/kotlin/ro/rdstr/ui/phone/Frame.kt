package ro.rdstr.ui.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.UiTheme

/**
 * music.css `.music-app` open and closed: opening, it rises from the bottom (420 ms, `--sheet-in`); closing, its
 * content fades first (100 ms), then its outline shrinks onto the home card (`useCloseOntoCard`: 56/880/24/24 px in
 * from the edges, round 16) while it fades (280 ms), and it is hidden.
 */
class AppFrame(private val view: ViewGroup) {
    private var animator: ValueAnimator? = null
    /** 0 open, 1 shrunk onto the card. */
    private var shut = 0f
    private val path = Path()

    fun open(on: Boolean) {
        animator?.cancel()
        val d = view.resources.displayMetrics.density
        if (on) {
            shut = 0f
            view.visibility = View.VISIBLE
            view.alpha = 1f
            kids(1f)
            if (UiTheme.speed <= 0f) { view.translationY = 0f; return }
            view.translationY = 750 * d
            view.animate().translationY(0f).setDuration(UiTheme.duration(420)).setInterpolator(Motion.interpolator(UiTheme.base.sheetIn)).start()
        } else {
            if (view.visibility != View.VISIBLE) return
            if (UiTheme.speed <= 0f) { view.visibility = View.INVISIBLE; return }
            view.animate().cancel()
            view.translationY = 0f
            val fade = CssBezier(0.4f, 0f, 1f, 1f)
            val sheet = Motion.sheetIn
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = UiTheme.duration(280)
                addUpdateListener {
                    val t = it.animatedFraction
                    kids((1f - t * 280f / 100f).coerceAtLeast(0f))
                    shut = sheet.at(t)
                    view.alpha = 1f - fade.at(t)
                    view.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator !== animation) return
                        view.visibility = View.INVISIBLE; shut = 0f; view.alpha = 1f; kids(1f)
                    }
                })
                start()
            }
        }
    }

    private fun kids(a: Float) { for (i in 0 until view.childCount) view.getChildAt(i).alpha = a }

    /** Clips [canvas] to the closing outline; true when it saved the canvas (the caller restores it). */
    fun clip(canvas: Canvas): Boolean {
        if (shut <= 0f) return false
        val d = view.resources.displayMetrics.density
        val w = view.width.toFloat(); val h = view.height.toFloat()
        path.roundRect(880 * d * shut, 56 * d * shut, w - 24 * d * shut, h - 24 * d * shut, 16 * d * shut)
        canvas.save()
        canvas.clipPath(path)
        return true
    }
}
