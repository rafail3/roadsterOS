package ro.rdstr.car

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Large covers (1000 px) from two public music catalogs, Deezer first and iTunes second, for the same track only.
 * This part builds the queries and picks the result; fetching is per platform.
 */
object CoverCatalog {
    /** Covers at least this large are already sharp; smaller ones are worth a catalog lookup. */
    const val SHARP_PX = 600

    private val json = Json { ignoreUnknownKeys = true }

    fun deezerQuery(title: String, artist: String) = "https://api.deezer.com/search?q=${encode("$title $artist")}&limit=5"

    fun itunesQuery(title: String, artist: String) = "https://itunes.apple.com/search?term=${encode("$title $artist")}&entity=song&limit=5"

    /** The 1000 px cover of the first result that is the same track, or null. */
    fun deezerCover(body: String, title: String, artist: String): String? = results(body, "data").firstOrNull {
        TrackMatch.same(title, artist, it.text("title").orEmpty(), it["artist"]?.jsonObject?.text("name").orEmpty())
    }?.get("album")?.jsonObject?.text("cover_xl")

    fun itunesCover(body: String, title: String, artist: String): String? = results(body, "results").firstOrNull {
        TrackMatch.same(title, artist, it.text("trackName").orEmpty(), it.text("artistName").orEmpty())
    }?.text("artworkUrl100")?.replace("100x100bb", "1000x1000bb")

    private fun results(body: String, key: String): List<JsonObject> = runCatching {
        (json.parseToJsonElement(body).jsonObject[key] as? JsonArray).orEmpty().map { it.jsonObject }
    }.getOrDefault(emptyList())

    private fun JsonObject.text(key: String): String? = runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()

    private fun encode(s: String): String = buildString {
        for (byte in s.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-_.~") append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }
}
