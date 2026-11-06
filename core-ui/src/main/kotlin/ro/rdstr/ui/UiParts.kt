package ro.rdstr.ui

/**
 * For the visual parity check: a view that paints, by itself, what the emulator has as elements of their own (the dots
 * of a meter, the keys of a picture), or a box colour the capture cannot read from its background drawable.
 */
interface UiParts {
    /** The CSS `background-color` this view paints itself; null leaves it out of the comparison. */
    val uiBackground: Int? get() = null
    /** The CSS `color` this view draws with (an svg's `currentColor`); null leaves it out. */
    val uiColor: Int? get() = null
    /** The elements this view draws, each under its key relative to this view's ("sf-block:0/i:3"). */
    fun uiParts(out: MutableList<UiPart>) {}
}

/** One drawn element: its box in px from the view's top-left, and what the emulator reports of its style. */
class UiPart(
    val key: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val background: Int? = null,
    val color: Int? = null,
    val opacity: Float = 1f,
    val text: String? = null,
)
