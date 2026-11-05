package ro.rdstr.map

import java.io.InputStream

/**
 * The map's label fonts: the OS font, Geist, as shipped in this module (the browser emulator's glyphs are made from the
 * same files). Geist has no italic, so italic labels use the regular face.
 */
object MapFonts {
    fun file(font: Font): String = when (font) {
        Font.BOLD -> "Geist-SemiBold.ttf"
        else -> "Geist-Regular.ttf"
    }

    fun open(font: Font): InputStream = MapFonts::class.java.getResourceAsStream("/ro/rdstr/map/fonts/${file(font)}")!!
}
