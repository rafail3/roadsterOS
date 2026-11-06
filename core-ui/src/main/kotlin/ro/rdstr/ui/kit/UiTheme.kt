package ro.rdstr.ui.kit

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.View
import ro.rdstr.car.Design
import ro.rdstr.car.Shell
import ro.rdstr.car.ShellState
import ro.rdstr.car.Theme
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Bezier
import ro.rdstr.ui.tokens.Fonts
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.SettingsApp
import ro.rdstr.ui.tokens.Stops
import java.lang.ref.WeakReference

/**
 * The look every kit view draws with: theme (light/dark), design (Solid/Glassy), font family and animation speed,
 * from the shell's state. Views listen with [observe] (or [link]) and redraw when it changes.
 *
 * A change of theme cross-fades the whole screen over `--theme` (700 ms, `--ease-out`), as `.screen.theming` does:
 * one snapshot of the screen before the change, drawn over the new one and faded out (see [fadeRoot]).
 */
object UiTheme {
    var light = false; private set
    var glassy = false; private set
    var geist = true; private set
    /** `Shell.animationScale`: every duration is multiplied by it (1 normal, 0.5 fast, 0 off). */
    var speed = 1f; private set

    private var root = WeakReference<View>(null)
    /** The screen's root, for the theme cross-fade; null draws theme changes at once. Held weakly. */
    var fadeRoot: View?
        get() = root.get()
        set(v) { root = WeakReference(v) }

    val base: Base get() = Base.of(light, glassy)
    val glass: Glassy get() = Glassy.of(light)
    /** The settings app's scope (`.settings-app`): its ink, ground and group colours. */
    val settings: SettingsApp get() = SettingsApp.of(light, glassy)
    val glisse: GlisseTokens get() = GlisseTokens.of(light)

    private val listeners = ArrayList<() -> Unit>()

    /** Follows [shell], with [theme] the effective one (`Shell.effectiveTheme`). */
    fun follow(shell: ShellState, theme: Theme) =
        set(theme == Theme.LIGHT, shell.design == Design.GLASSY, shell.font == "geist", Shell.animationScale(shell))

    /** Follows [shell] at [hour], deciding the auto theme as the shell does. */
    fun follow(shell: ShellState, hour: Int) = follow(shell, Shell.effectiveTheme(shell, hour))

    fun set(light: Boolean, glassy: Boolean, geist: Boolean, speed: Float) {
        if (light == this.light && glassy == this.glassy && geist == this.geist && speed == this.speed) return
        val apply = {
            this.light = light; this.glassy = glassy; this.geist = geist; this.speed = speed
            for (l in listeners.toTypedArray()) l()
        }
        val root = fadeRoot
        if (light != this.light && root != null && speed > 0f && root.isAttachedToWindow && root.width > 0) {
            ThemeFade.run(root, (Base.DARK.theme * speed).toLong(), Motion.interpolator(Base.DARK.easeOut), apply)
        } else apply()
    }

    /** Calls [listener] after every change; returns the call that stops it. */
    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Keeps [view] following the theme while it is attached: [onChange] runs at attach and after each change. */
    fun link(view: View, onChange: () -> Unit) {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            var stop: (() -> Unit)? = null
            override fun onViewAttachedToWindow(v: View) { stop = observe(onChange); onChange() }
            override fun onViewDetachedFromWindow(v: View) { stop?.invoke(); stop = null }
        })
    }

    /** [ms] at the animation speed. */
    fun duration(ms: Long): Long = (ms * speed).toLong()

    private val faces = HashMap<Int, Typeface>()

    /** The variable face of the chosen family (`--font`: Inter, or Geist with `.font-geist`). */
    fun typeface(context: Context): Typeface {
        val id = if (geist) Fonts.GEIST else Fonts.INTER
        return faces.getOrPut(id) { context.resources.getFont(id) }
    }
}

/** The theme cross-fade: a snapshot of the screen before the change, faded out over the changed screen. */
private object ThemeFade {
    private var running: ValueAnimator? = null

    fun run(root: View, durationMs: Long, ease: TimeInterpolator, change: () -> Unit) {
        // the snapshot is the frame on screen now, an unfinished fade included
        val shot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(shot))
        running?.end()
        change()
        val layer = BitmapDrawable(root.resources, shot).apply { setBounds(0, 0, root.width, root.height) }
        root.overlay.add(layer)
        running = ValueAnimator.ofInt(255, 0).apply {
            duration = durationMs
            interpolator = ease
            addUpdateListener { layer.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.overlay.remove(layer)
                    shot.recycle()
                    if (running === animation) running = null
                }
            })
            start()
        }
    }
}

/** Our UI's density: 1 canvas px = 1 dp, the 1280 px canvas across the panel (density 128 on the 1024 px one). */
object UiDensity {
    /** The context to give an Activity in `attachBaseContext`. */
    fun wrap(base: Context): Context {
        val m = base.resources.displayMetrics
        val config = Configuration(base.resources.configuration).apply {
            densityDpi = UiScale.densityDpi(maxOf(m.widthPixels, m.heightPixels))
            fontScale = 1f
        }
        return base.createConfigurationContext(config)
    }
}

/** [css] canvas px in this view's device px. */
fun View.px(css: Float): Float = css * resources.displayMetrics.density

/** The token easings as Android interpolators, and token durations at the animation speed. */
object Motion {
    fun easing(b: Bezier): Easing = CssBezier(b.x1, b.y1, b.x2, b.y2)
    fun easing(s: Stops): Easing = CssStops(s.values, s.at)
    fun interpolator(e: Easing) = TimeInterpolator { e.at(it) }
    fun interpolator(b: Bezier) = interpolator(easing(b))
    fun interpolator(s: Stops) = interpolator(easing(s))

    // the tokens' easings, made once
    val easeOut = easing(Base.DARK.easeOut)
    val seamless = easing(Base.DARK.seamless)
    val springGlyph = easing(Base.DARK.springGlyph)
    val springSwap = easing(Base.DARK.springSwap)
    val sheetIn = easing(Base.DARK.sheetIn)

    /** The frame clock the kit's transitions run on, in ms. */
    fun now(): Long = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
}
