package ro.rdstr.ui.phone

import android.view.View

/**
 * The on-screen keyboard (Keyboard.tsx) as the phone app uses it: one keyboard for the contacts search and the
 * message being written. The keyboard view itself lives in `ro.rdstr.ui.keyboard`; this is what the phone app needs of
 * it, so the two connect without depending on each other.
 */
interface KeyboardSlot {
    /** The keyboard's view; the phone app places it along its bottom edge, over everything else. */
    val view: View

    /** Keyboard.tsx props: whether it is up, the submit key's label and whether it is enabled, and where the keys go. */
    fun bind(open: Boolean, submitLabel: String, submitEnabled: Boolean, keys: Keys)

    interface Keys {
        fun onKey(char: String)
        fun onBackspace()
        fun onSubmit()
    }
}
