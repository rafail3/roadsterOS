package ro.rdstr.os.maps

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/**
 * The country map packs on the unit: MBTiles files in the app's own folder on the internal storage
 * (`Android/data/ro.rdstr.os/files/maps/<CODE>.mbtiles`), read with the SQLite built into Android, with no network.
 */
class MapPacks(val dir: File) : Closeable {
    private val open = dir.listFiles { f -> f.name.endsWith(".mbtiles") }.orEmpty().sortedBy { it.name }
        .map { SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS) }

    val codes: List<String> = dir.listFiles { f -> f.name.endsWith(".mbtiles") }.orEmpty().map { it.nameWithoutExtension }.sorted()

    /** One tile's bytes as stored (gzipped), from the first pack that has it; MBTiles counts rows from the bottom. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val args = arrayOf(z.toString(), x.toString(), ((1 shl z) - 1 - y).toString())
        for (db in open) {
            db.rawQuery(QUERY, args).use { if (it.moveToFirst()) return it.getBlob(0) }
        }
        return null
    }

    override fun close() = open.forEach { it.close() }

    private companion object {
        const val QUERY = "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?"
    }
}
