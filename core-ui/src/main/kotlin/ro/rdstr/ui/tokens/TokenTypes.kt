package ro.rdstr.ui.tokens

// Value types of the generated token classes (Tokens.kt), one per kind of CSS value the emulator's sheets use.

/** `cubic-bezier(x1, y1, x2, y2)` of a transition. */
class Bezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** `linear(…)` easing: the output [values] at each input point [at], 0..1. */
class Stops(val values: FloatArray, val at: FloatArray)

/** `linear-gradient(angle, …)`: CSS angle (0 = to top, clockwise), straight-alpha [colors] at [at] 0..1. */
class Gradient(val angle: Float, val colors: IntArray, val at: FloatArray)

/** One `box-shadow` layer, lengths in canvas px. */
class Shadow(val inset: Boolean, val x: Float, val y: Float, val blur: Float, val spread: Float, val color: Int)

/** `backdrop-filter: blur() saturate() brightness()` of the glassy design. */
class Frost(val blur: Float, val saturate: Float, val brightness: Float)
