package ro.rdstr.car

import java.net.HttpURLConnection
import java.net.URL

/** Fetches the large catalog cover of a track as JPEG bytes; null without network or without a match. Blocking. */
object CoverFetch {
    private const val TIMEOUT_MS = 4000

    fun large(title: String, artist: String): ByteArray? = runCatching {
        val url = CoverCatalog.deezerCover(text(CoverCatalog.deezerQuery(title, artist)), title, artist)
            ?: CoverCatalog.itunesCover(text(CoverCatalog.itunesQuery(title, artist)), title, artist)
            ?: return null
        bytes(url).takeIf { it.size in 4..CoverLink.MAX_PAYLOAD && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
    }.getOrNull()

    private fun text(url: String) = bytes(url).decodeToString()

    private fun bytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
