package ro.rdstr.ui.shell

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import ro.rdstr.ui.R

/**
 * Screen.tsx `div.screen`: the shell's root, 1280 x 750 canvas px. Every screen's view goes in with [put] at its
 * [Layer] and is drawn in layer order.
 */
class ShellView(context: Context) : FrameLayout(context) {
    /** The clock block's place in the field ([Layer.CLOCK]). */
    val clockSlot = FrameLayout(context)
    /** The always-on display's dial ([Layer.AOD_DIAL]). */
    val aodDialSlot = FrameLayout(context)

    /** Puts [view] at [layer], over the views already in that layer. */
    fun put(layer: Layer, view: View) {
        when (layer) {
            Layer.CLOCK -> clockSlot.addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            Layer.AOD_DIAL -> aodDialSlot.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            else -> {
                view.setTag(R.id.rdstr_layer, layer)
                var at = childCount
                while (at > 0 && (getChildAt(at - 1).getTag(R.id.rdstr_layer) as Layer).ordinal > layer.ordinal) at--
                addView(view, at, view.layoutParams as? LayoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
    }

    /** Takes [view] out of the shell. */
    fun remove(view: View) = (view.parent as? android.view.ViewGroup)?.removeView(view)
}
