package ro.rdstr.car

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The boot picture and the start screen, as dots on the 1024x600 screen: the symbol (an isometric cube with r, O and S
 * on its faces), the rdstr wordmark (pixel letters, slanted, with two dithered depth layers) and a 5x7 pixel font.
 * Both hosts draw the same dots with the same timeline ([frame]), so the unit and the emulator show the same pixels.
 */
object BootArt {
    /** The colour table the dots index into: the ramp from red (bottom) to yellow (top), then the two depth colours. */
    val COLORS = listOf("#a3120c", "#e0301e", "#ff6a14", "#ffa41b", "#ffd447", "#4a0907", "#7c1209")
    private const val RAMP = 5
    private const val DEEP = 5
    private const val DEPTH = 6

    const val BACKGROUND = "#07080a"
    /** A dot burning bright for a moment, as phosphor does when the beam first hits it. */
    const val PHOSPHOR = "#fff1c9"
    const val CAPTION = "#ffb02e"
    const val BAR = "#ff8a1a"
    const val SUBTITLE = "#c9b9a6"

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5).map { (it + 0.5) / 16 }

    /** The 4x4 ordered-dither threshold at a point. */
    fun bayer(x: Int, y: Int) = BAYER[(y and 3) * 4 + (x and 3)]

    /** A colour on the ramp at [t] (0 bottom .. 1 top), dithered between its two neighbours. */
    fun ramp(t: Double, x: Int, y: Int): Int {
        val f = max(0.0, min(0.999, t)) * (RAMP - 1)
        val i = floor(f).toInt()
        return if (f - i > bayer(x, y)) i + 1 else i
    }

    /** A fixed pseudo-random number per dot, 0..1: the order the dots dissolve in. */
    fun hash(x: Int, y: Int, k: Int = 0): Double {
        val h = ((x + 977) * 73856093) xor ((y + 331) * 19349663) xor ((k + 7) * 83492791)
        return (h.toLong() and 0xFFFFFFFFL) % 10007 / 10007.0
    }

    /** [layer] 0 is the letter's face, 1 and 2 the depth behind it; [letter] the index in the word. */
    data class WordDot(val x: Int, val y: Int, val color: Int, val layer: Int, val letter: Int, val order: Double)

    /** [face] 0 top, 1 left, 2 right; [glyph] whether the dot belongs to the face's letter. */
    data class CubeDot(val x: Int, val y: Int, val color: Int, val face: Int, val glyph: Boolean, val order: Double)

    class Word(val dots: List<WordDot>, val width: Int, val height: Int, val letters: Int)

    private val WORD = mapOf(
        'r' to listOf("##.###", "######", "###...", "##....", "##....", "##....", "##...."),
        'd' to listOf(".....##", ".....##", ".....##", ".######", "#######", "##...##", "##...##", "##...##", "#######", ".######"),
        's' to listOf(".######", "#######", "###....", ".#####.", "....###", "#######", "######."),
        't' to listOf(".##...", ".##...", "######", "######", ".##...", ".##...", ".##...", ".#####", "..####"),
    )

    /** The wordmark: each cell 3x3 dots, rows slanting right going up, the depth layers drawn first. */
    fun word(text: String = "rdstr"): Word {
        val d = 3
        val rows = 10
        val cells = mutableListOf<Triple<Int, Int, Int>>()
        var cx = 0
        text.forEachIndexed { li, ch ->
            val g = WORD.getValue(ch)
            val top = rows - g.size
            g.forEachIndexed { r, line -> line.forEachIndexed { k, c -> if (c == '#') cells += Triple(cx + k, top + r, li) } }
            cx += g[0].length + 1
        }
        val h = rows * d
        fun slant(dy: Int) = floor((h - 1 - dy) / 4.0).toInt()
        val out = mutableListOf<WordDot>()
        for (layer in intArrayOf(2, 1)) for ((x, y, li) in cells) for (i in 0 until d) for (j in 0 until d) {
            val dx = x * d + i
            val dy = y * d + j
            if (layer == 2 && (dx + dy) and 1 == 1) continue
            out += WordDot(dx + slant(dy), dy, if (layer == 2) DEEP else DEPTH, layer, li, hash(dx, dy, layer))
        }
        for ((x, y, li) in cells) for (i in 0 until d) for (j in 0 until d) {
            val dx = x * d + i
            val dy = y * d + j
            out += WordDot(dx + slant(dy), dy, ramp(1 - dy.toDouble() / (h - 1), dx, dy), 0, li, hash(dx, dy))
        }
        return Word(out, cx * d + 12, h + 6, text.length)
    }

    private val GLYPHS = mapOf(
        'r' to listOf("##.###", "######", "###...", "##....", "##....", "##....", "##...."),
        'O' to listOf(".####.", "######", "##..##", "##..##", "##..##", "######", ".####."),
        'S' to listOf(".#####", "######", "###...", ".####.", "...###", "######", "#####."),
    )

    /** Whether a point of a face's unit square (col across, row down) falls on its letter, set in the face's middle. */
    private fun inGlyph(g: Char, col: Double, row: Double, at: Double = 0.17, size: Double = 0.66): Boolean {
        val c = floor((col - at) / size * 6).toInt()
        val r = floor((row - at + 0.02) / (size + 0.04) * 7).toInt()
        return c in 0..5 && r in 0..6 && GLYPHS.getValue(g)[r][c] == '#'
    }

    /**
     * The symbol, an isometric cube of edge [n] dots centred on 0,0: r on the front-left face (roadster), O on the top
     * and S on the right (OS). Each face has its own density and tone; the letters are dense and bright.
     */
    fun cube(n: Int = 36): List<CubeDot> {
        val out = mutableListOf<CubeDot>()
        val sq = sqrt(3.0) / 2
        val seam = 0.06
        val w = ceil(n * sq).toInt() + 2
        val hh = n + 2
        for (y in -hh..hh) for (x in -w..w) {
            val u = (x / sq - 2 * y) / 2 / n
            val v = (-x / sq - 2 * y) / 2 / n
            var face = -1
            var a = 0.0
            var b = 0.0
            if (u >= 0 && v >= 0 && u <= 1 && v <= 1) { face = 0; a = u; b = v }
            else if (x <= 0 && x >= -n * sq) { val ty = (y - x * 0.5 / sq) / n; if (ty in 0.0..1.0) { face = 1; a = -x / (n * sq); b = ty } }
            else if (x > 0 && x <= n * sq) { val ty = (y + x * 0.5 / sq) / n; if (ty in 0.0..1.0) { face = 2; a = x / (n * sq); b = ty } }
            if (face < 0 || a < seam || b < seam) continue
            val letter: Boolean
            val density: Double
            val t: Double
            when (face) {
                0 -> { letter = inGlyph('O', a, 1 - b, 0.1, 0.8); density = if (letter) 1.0 else 0.42; t = if (letter) 1.0 else 0.5 }
                1 -> { letter = inGlyph('r', 1 - a, b); density = if (letter) 1.0 else 0.5; t = if (letter) 0.9 else 0.36 - b * 0.2 }
                else -> { letter = inGlyph('S', a, b); density = if (letter) 1.0 else 0.34; t = if (letter) 0.7 else 0.14 - b * 0.12 }
            }
            if (bayer(x + 64, y + 64) > density) continue
            out += CubeDot(x, y, ramp(t, x + 64, y + 64), face, letter, hash(x, y))
        }
        return out
    }

    /** The 5x7 pixel font of the captions, row by row; unknown characters draw as a space. */
    val TINY: Map<Char, String> = mapOf(
        'A' to ".###.#...##...#######...##...##...#", 'B' to "####.#...##...#####.#...##...#####.", 'C' to ".###.#...##....#....#....#...#.###.",
        'D' to "####.#...##...##...##...##...#####.", 'E' to "######....#....####.#....#....#####", 'F' to "######....#....####.#....#....#....",
        'G' to ".###.#...##....#.####...##...#.####", 'H' to "#...##...##...#######...##...##...#", 'I' to ".###...#....#....#....#....#...###.",
        'J' to "..###...#....#....#....#.#..#..##..", 'K' to "#...##..#.#.#..##...#.#..#..#.#...#", 'L' to "#....#....#....#....#....#....#####",
        'M' to "#...###.###.#.##.#.##...##...##...#", 'N' to "#...##...###..##.#.##..###...##...#", 'O' to ".###.#...##...##...##...##...#.###.",
        'P' to "####.#...##...#####.#....#....#....", 'Q' to ".###.#...##...##...##.#.##..#..##.#", 'R' to "####.#...##...#####.#.#..#..#.#...#",
        'S' to ".#####....#.....###.....#....#####.", 'T' to "#####..#....#....#....#....#....#..", 'U' to "#...##...##...##...##...##...#.###.",
        'V' to "#...##...##...##...##...#.#.#...#..", 'W' to "#...##...##...##.#.##.#.##.#.#.#.#.", 'X' to "#...##...#.#.#...#...#.#.#...##...#",
        'Y' to "#...##...#.#.#...#....#....#....#..", 'Z' to "#####....#...#...#...#...#....#####",
        '0' to ".###.#...##..###.#.###..##...#.###.", '1' to "..#...##....#....#....#....#...###.", '2' to ".###.#...#....#...#...#...#...#####",
        '3' to "####.....#....#.###.....#....#####.", '4' to "...#...##..#.#.#..#.#####...#....#.", '5' to "######....####.....#....##...#.###.",
        '6' to ".###.#....#....####.#...##...#.###.", '7' to "#####....#...#...#...#....#....#...", '8' to ".###.#...##...#.###.#...##...#.###.",
        '9' to ".###.#...##...#.####....#....#.###.", '.' to "................................#..", ' ' to "...................................",
        '_' to "..............................#####", '-' to "...............#####...............",
        '%' to "##...##..#...#...#...#...#..##...##", '>' to ".#.....#.....#.....#...#...#...#...",
    )

    private fun clamp(v: Double) = max(0.0, min(1.0, v))
    fun outCubic(t: Double) = 1 - (1 - clamp(t)).pow(3)
    fun outExpo(t: Double): Double { val c = clamp(t); return if (c == 1.0) 1.0 else 1 - 2.0.pow(-10 * c) }
    /** Ease-out with a small overshoot, for things that land. */
    fun outBack(t: Double): Double { val c = clamp(t); val k = 1.6; return 1 + (k + 1) * (c - 1).pow(3) + k * (c - 1).pow(2) }

    const val CAPTION_TEXT = "ROADSTEROS"
    /** The line under the wordmark on the start screen. */
    const val SUBTITLE_TEXT = "THE OS FOR YOUR CAR"
    /** The cursor's half period, and the soft refresh band's full sweep. */
    const val BLINK_MS = 530.0
    const val ROLL_MS = 5200.0
    /** The picture is complete after this; the boot screen can give way to what comes next. */
    const val DONE_MS = 2700.0

    /** The tube lighting up: a rectangle centred on the screen, [alpha] of the phosphor colour. Null once it is out. */
    class Tube(val x: Double, val y: Double, val w: Double, val h: Double, val alpha: Double)

    /** Everything the boot screen draws at one moment, [t] ms after it starts. */
    class Frame(
        val tube: Tube?,
        /** Per face (top, left, right): how far it has dissolved in, 0 hidden .. 1 whole; null before the cube starts. */
        val cube: DoubleArray?,
        /** The cube's letters burn bright once, as the cube completes. */
        val flash: Boolean,
        /** Per letter of the word, 0 hidden .. 1 landed; null before the word starts. */
        val letters: DoubleArray?,
        /** The depth sliding out from behind the letters, 0..1. */
        val depth: Double,
        /** How many characters of the caption are typed, -1 before it starts, and whether the block cursor shows. */
        val typed: Int,
        val cursor: Boolean,
        /** The darkening of the two flickers right after the tube lights. */
        val flicker: Boolean,
    )

    fun frame(t: Double, letters: Int = 5): Frame {
        val tube = if (t > 100 && t < 760) {
            val a = if (t < 480) 1.0 else 1 - clamp((t - 480) / 280)
            if (t < 220) {
                val dot = outExpo((t - 100) / 140)
                Tube(512 - 4 * dot, 300 - 4 * dot, 8 * dot, 8 * dot, a)
            } else {
                val w = 8 + outExpo((t - 220) / 240) * 1016
                val h = 2 + outExpo((t - 440) / 200) * 598
                Tube(512 - w / 2, 300 - h / 2, w, h, a)
            }
        } else null
        fun face(start: Double) = outCubic((t - start) / 420)
        val cube = if (t > 640) doubleArrayOf(face(800.0), face(640.0), face(960.0)) else null
        val word = if (t > 1300) DoubleArray(letters) { li -> clamp((t - 1300 - li * 85) / 300) } else null
        val typed = if (t > 2150) min(CAPTION_TEXT.length, floor((t - 2150) / 45).toInt()) else -1
        return Frame(
            tube = tube,
            cube = cube,
            flash = t > 1420 && t < 1500,
            letters = word,
            depth = clamp((t - 1820) / 320),
            typed = typed,
            cursor = typed >= 0 && (typed < CAPTION_TEXT.length || floor(t / BLINK_MS).toInt() % 2 == 0),
            flicker = t > 700 && t < 820 && floor(t / 40).toInt() % 2 == 0,
        )
    }

    /** The top of the soft refresh band at [t]; it is 120 px tall and sweeps past the bottom edge. */
    fun rollY(t: Double) = (t % ROLL_MS) / ROLL_MS * 760 - 80
}
