package ro.rdstr.os.cover

import ro.rdstr.car.CoverCatalog
import ro.rdstr.car.CoverIndex
import ro.rdstr.car.JpegSize
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Covers kept on disk, one file per track: a track heard again under slightly different names finds its file, and
 * a new image replaces the kept one only when it is larger. When full, the cover of the track heard longest ago
 * makes room. Called from one thread at a time.
 */
class CoverCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {
    init { dir.mkdirs() }

    /** The kept cover of a track; finding it marks the track as heard now. */
    fun find(title: String, artist: String): File? {
        val name = CoverIndex.find(dir.list()?.toList().orEmpty(), title, artist) ?: return null
        return File(dir, name).also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** Keeps [jpeg] for the track unless an equal or larger cover is kept already; [Stored.written] says which. */
    fun put(title: String, artist: String, jpeg: ByteArray): Stored {
        val kept = find(title, artist)
        if (kept != null && (JpegSize.pixels(jpeg) ?: 0) <= (JpegSize.pixels(kept.readBytes()) ?: 0)) return Stored(kept, false)
        val file = kept ?: File(dir, CoverIndex.fileName(title, artist))
        val part = File(dir, file.name + ".part")
        part.writeBytes(jpeg)
        // written aside and moved over, so a reader never sees half a cover
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        if (kept == null) trim()
        return Stored(file, true)
    }

    /** Whether the kept cover is smaller than the large catalog covers, so worth an upgrade. */
    fun small(file: File): Boolean = (JpegSize.pixels(file.readBytes()) ?: 0) < SHARP_PIXELS

    class Stored(val file: File, val written: Boolean)

    private fun trim() {
        val files = dir.listFiles() ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    private companion object {
        /** About 170 KB each: about 100 MB, the covers of the last 600 different tracks heard. */
        const val MAX_FILES = 600
        const val SHARP_PIXELS = CoverCatalog.SHARP_PX.toLong() * CoverCatalog.SHARP_PX
    }
}
