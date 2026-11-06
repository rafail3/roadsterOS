package ro.rdstr.os.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import ro.rdstr.car.MapPacks as Packs

/**
 * The country map packs on the unit: MBTiles files in the app's own folder on the internal storage
 * (`Android/data/ro.rdstr.os/files/maps/<CODE>.mbtiles`), read with the SQLite built into Android, with no network.
 */
class MapPacks(val dir: File) : Closeable {
    private var open: List<SQLiteDatabase> = emptyList()

    @Volatile var codes: List<String> = emptyList()
        private set

    init { reload() }

    /** Opens the packs in [dir] again, after one was downloaded or removed. */
    @Synchronized fun reload() {
        open.forEach { it.close() }
        val files = files(dir)
        open = files.map { SQLiteDatabase.openDatabase(it.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS) }
        codes = files.map { it.nameWithoutExtension }
    }

    /** One tile's bytes as stored (gzipped), from the first pack that has it; MBTiles counts rows from the bottom. */
    @Synchronized fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val args = arrayOf(z.toString(), x.toString(), ((1 shl z) - 1 - y).toString())
        for (db in open) {
            db.rawQuery(QUERY, args).use { if (it.moveToFirst()) return it.getBlob(0) }
        }
        return null
    }

    /** Deletes a country's pack from the unit; the open file goes once the packs are opened again. */
    fun remove(code: String) {
        File(dir, code + Packs.EXTENSION).delete()
        reload()
    }

    @Synchronized override fun close() {
        open.forEach { it.close() }
        open = emptyList()
    }

    companion object {
        private const val QUERY = "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?"

        fun dir(context: Context) = File(context.getExternalFilesDir(null), "maps").apply { mkdirs() }

        /** The packs in [dir] by country code, without opening them. */
        fun codes(dir: File) = files(dir).map { it.nameWithoutExtension }

        private fun files(dir: File) = dir.listFiles { f -> f.name.endsWith(Packs.EXTENSION) }.orEmpty().sortedBy { it.name }
    }
}
