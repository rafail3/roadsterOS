package ro.rdstr.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Gap
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

/**
 * The question before the unit restarts (RestartPrompt.tsx, music.css `.alert`): a 420 px card over a
 * `rgba(0,0,0,.45)` scrim, the power glyph on a blue disc, the question, what a restart does, and Restart over Cancel.
 * It opens fading in 220 ms (`--seamless`) from 1.08 down to its size in 320 ms (`--spring-glyph`); a tap on the scrim
 * cancels. On Glassy the card, the disc and the buttons are glass, Restart keeping its blue under the sheen.
 *
 * Laid out over the whole screen, above the drawer; touches pass through while it is closed.
 */
@Mirrors("RestartPrompt.tsx")
class RestartPromptView(context: Context) : FrameLayout(context) {
    var actions: SettingsActions? = null

    private val scrim = View(context).apply { uiKey = "alert-scrim"; contentDescription = "Cancel"; alpha = 0f }
    private val card = LinearLayout(context)
    private val cardBox = Plate(card, 28f)
    private val disc = FrameLayout(context)
    private val discBox = Plate(disc, 999f)
    private val glyph = icon(context, "power", 30f)
    private val title = text(context, "h2", TextStyle(22f, 600)).apply { text = "Restart the unit?"; gravity = Gravity.CENTER }
    private val body = text(context, "p", TextStyle(16f, 400, lineHeight = 1.4f), single = false).apply {
        text = "The screen goes dark and the music stops while the unit starts again. It takes about a minute."; gravity = Gravity.CENTER
    }
    private val actionsBox = LinearLayout(context)
    private val restart = button("Restart", primary = true)
    private val cancel = button("Cancel", primary = false)

    var open = false
        set(v) {
            if (field == v) return
            field = v
            val ms = UiTheme.duration(220)
            scrim.animate().alpha(if (v) 1f else 0f).setDuration(ms).setInterpolator(Motion.interpolator(Motion.seamless))
            card.animate().alpha(if (v) 1f else 0f).setDuration(ms).setInterpolator(Motion.interpolator(Motion.seamless))
            card.animate().scaleX(if (v) 1f else 1.08f).scaleY(if (v) 1f else 1.08f).setDuration(UiTheme.duration(320)).setInterpolator(Motion.interpolator(Motion.springGlyph))
            isClickable = v
            scrim.isClickable = v
        }

    init {
        uiKey = "alert"
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrim.setOnClickListener { actions?.askRestart(false) }
        card.uiKey = "alert-card"
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = cardBox
        cardBox.box.border = 1f
        card.setPadding(card.dp(24f), card.dp(28f), card.dp(24f), card.dp(20f))
        card.dividerDrawable = Gap(card.dp(10f)); card.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        card.alpha = 0f; card.scaleX = 1.08f; card.scaleY = 1.08f
        card.contentDescription = "Restart the unit?"
        disc.uiKey = "alert-icon"
        disc.background = discBox
        disc.addView(glyph, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        card.addView(disc, LinearLayout.LayoutParams(card.dp(64f), card.dp(64f)))
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = card.dp(8f) })
        card.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        actionsBox.uiKey = "alert-actions"
        actionsBox.orientation = LinearLayout.VERTICAL
        actionsBox.dividerDrawable = Gap(card.dp(10f)); actionsBox.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        actionsBox.clipChildren = false
        actionsBox.addView(restart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, card.dp(56f)))
        actionsBox.addView(cancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, card.dp(56f)))
        card.addView(actionsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = card.dp(14f) })
        card.clipChildren = false
        addView(card, LayoutParams(card.dp(420f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        restart.setOnClickListener { actions?.restartUnit() }
        cancel.setOnClickListener { actions?.askRestart(false) }
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun button(label: String, primary: Boolean) = text(context, "alert-button", TextStyle(18f, 600)).apply {
        text = label
        gravity = Gravity.CENTER
        isClickable = true
        background = Plate(this, 999f)
        tag = primary
        // :active { transform: scale(.97) }, 120 ms in, 420 ms back on --spring-glyph
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(UiTheme.duration(120))
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(UiTheme.duration(420)).setInterpolator(Motion.interpolator(Motion.springGlyph))
            }
            false
        }
    }

    private fun theme() {
        val b = UiTheme.base
        val g = UiTheme.glass
        val glassy = UiTheme.glassy
        if (glassy) cardBox.box.glassy(g) else { cardBox.box.solid(b.panel); cardBox.box.shadows = listOf(Shadow(false, 0f, 18f, 48f, 0f, 0x73000000)) }
        cardBox.box.borderColor = CssColor.withAlpha(b.on, 0.1f)
        cardBox.look(cardBox.box.fill)
        if (glassy) discBox.box.glassy(g) else discBox.box.solid(0x2E0A84FF)
        discBox.look(discBox.box.fill)
        glyph.color = BLUE
        title.setTextColor(b.ink)
        body.setTextColor(CssColor.withAlpha(b.on, 0.62f))
        scrim.setBackgroundColor(0x73000000)
        for (bt in listOf(restart, cancel)) {
            val primary = bt.tag as Boolean
            val plate = bt.background as Plate
            val fill = if (primary) BLUE else if (glassy) g.glassyFill else CssColor.withAlpha(b.on, 0.12f)
            if (glassy) { plate.box.glassy(g, fill) } else plate.box.solid(fill)
            plate.look(fill)
            bt.setTextColor(if (primary) -1 else b.ink)
        }
        card.invalidate(); disc.invalidate()
    }

    private companion object { const val BLUE = 0xFF0A84FF.toInt() }
}
