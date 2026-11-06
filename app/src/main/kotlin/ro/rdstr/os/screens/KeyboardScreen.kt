package ro.rdstr.os.screens

import android.app.Activity
import android.view.Gravity
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import ro.rdstr.ui.keyboard.KeyboardView
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.sheets.KeyboardSlot

/**
 * The screen's keyboard (NetworksSheet.tsx portals Keyboard.tsx to `.screen`): full width at the bottom, over the
 * sheets, typing the Wi-Fi password of the sheet and of Settings > Wi-Fi. The phone app keeps its own, inside its view.
 */
class KeyboardScreen(activity: Activity) : Screen {
    private val keyboard = KeyboardView(activity).also { kb ->
        kb.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        UiTheme.link(kb) { kb.setTheme(UiTheme.light, UiTheme.glassy); kb.setFont(if (UiTheme.geist) "Geist" else "Inter") }
    }
    override val view get() = keyboard

    init {
        NetworksScreen.keyboard = object : KeyboardSlot {
            override fun open(onKey: (String) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit) {
                keyboard.onKey = { onKey(it.toString()) }
                keyboard.onBackspace = onBackspace
                keyboard.onSubmit = onSubmit
                keyboard.open = true
            }
            override fun close() { keyboard.open = false }
            override fun submit(label: String, enabled: Boolean) { keyboard.submitLabel = label; keyboard.submitEnabled = enabled }
        }
    }

    override fun bind(scope: CoroutineScope) = Unit
}
