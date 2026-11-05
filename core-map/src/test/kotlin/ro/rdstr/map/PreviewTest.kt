package ro.rdstr.map

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** The frame drawn with java.awt, to look at the unit's renderer on the laptop. */
class AwtCanvas(private val g: Graphics2D, private val w: Int, private val h: Int) : MapCanvas {
    private val saved = ArrayList<AffineTransform>()

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun path(parts: List<FloatArray>, close: Boolean) = Path2D.Float(Path2D.WIND_NON_ZERO).apply {
        for (p in parts) {
            if (p.size < 2) continue
            moveTo(p[0], p[1])
            for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
            if (close) closePath()
        }
    }

    override fun clear(color: Int) { g.color = Color(color, true); g.fillRect(0, 0, w, h) }
    override fun fill(rings: List<FloatArray>, color: Int) { g.color = Color(color, true); g.fill(path(rings, true)) }
    override fun stroke(lines: List<FloatArray>, color: Int, width: Float, dash: FloatArray?) {
        g.color = Color(color, true)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash?.map { it * width }?.toFloatArray(), 0f)
        g.draw(path(lines, false))
    }
    override fun rotate(degrees: Float, cx: Float, cy: Float) { saved += g.transform; g.rotate(Math.toRadians(degrees.toDouble()), cx.toDouble(), cy.toDouble()) }
    override fun restore() { g.transform = saved.removeAt(saved.lastIndex) }

    private fun font(size: Float, font: Font) = java.awt.Font("SansSerif", when (font) {
        Font.BOLD -> java.awt.Font.BOLD; Font.ITALIC -> java.awt.Font.ITALIC; else -> java.awt.Font.PLAIN
    }, 1).deriveFont(size)

    override fun textWidth(text: String, size: Float, font: Font) = g.getFontMetrics(font(size, font)).stringWidth(text).toFloat()

    override fun text(text: String, x: Float, y: Float, size: Float, font: Font, color: Int, halo: Int, haloWidth: Float, degrees: Float) {
        val f = font(size, font)
        val shape = f.createGlyphVector(g.fontRenderContext, text).outline
        val bounds = shape.bounds2D
        val at = AffineTransform().apply {
            translate(x.toDouble(), y.toDouble())
            rotate(Math.toRadians(degrees.toDouble()))
            translate(-bounds.centerX, -bounds.centerY)
        }
        val placed = at.createTransformedShape(shape)
        g.color = Color(halo, true)
        g.stroke = BasicStroke(haloWidth * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(placed)
        g.color = Color(color, true)
        g.fill(placed)
    }
}

class PreviewTest {
    @Test
    fun `draws the exported tiles around the view to png`() {
        val dir = File(System.getProperty("preview.tiles") ?: return)
        if (!dir.isDirectory) return
        val frame = MapFrame(MapStyle.load())
        val cache = HashMap<String, VectorTile?>()
        val tiles = MapFrame.Tiles { z, x, y ->
            cache.getOrPut("$z/$x/$y") { File(dir, "$z/$x/$y.pbf").takeIf { it.exists() }?.let { Mvt.decode(it.readBytes()) } }
        }
        for (night in listOf(true, false)) {
            val view = View(lon = 25.5887, lat = 45.6427, zoom = 15.5, bearing = 20.0, width = 1280, height = 750, night = night)
            val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            val started = System.nanoTime()
            frame.draw(AwtCanvas(g, view.width, view.height), tiles, view)
            val ms = (System.nanoTime() - started) / 1_000_000
            g.dispose()
            val out = File(System.getProperty("preview.out") + (if (night) "-night" else "-day") + ".png")
            out.parentFile.mkdirs()
            ImageIO.write(image, "png", out)
            println("preview ${out.name}: $ms ms")
            assertTrue(out.length() > 0)
        }
    }
}
