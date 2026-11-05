package ro.rdstr.os.cover

import ro.rdstr.car.CoverIndex
import java.io.File

/** Covers kept on disk by track, the least recently shown dropped first. Called from one thread at a time. */
class CoverCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {
    init { dir.mkdirs() }

    fun find(title: String, artist: String): File? {
        val name = CoverIndex.find(dir.list()?.toList().orEmpty(), title, artist) ?: return null
        return File(dir, name).also { it.setLastModified(System.currentTimeMillis()) }
    }

    fun put(title: String, artist: String, jpeg: ByteArray): File {
        val file = File(dir, CoverIndex.fileName(title, artist))
        val part = File(dir, file.name + ".part")
        part.writeBytes(jpeg)
        part.renameTo(file)
        trim()
        return file
    }

    private fun trim() {
        val files = dir.listFiles() ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    private companion object {
        /** About 170 KB each: under 90 MB, the covers of the last 500 different tracks heard. */
        const val MAX_FILES = 500
    }
}
