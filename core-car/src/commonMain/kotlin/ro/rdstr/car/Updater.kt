package ro.rdstr.car

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Updates come from the repository's GitHub Releases: the latest release carries the signed APK, and the `maps`
 * release carries one pack per country. The unit reads the release JSON from the GitHub API, compares, downloads
 * over Wi-Fi, checks the SHA-256 GitHub publishes for each file, and installs only while the car is parked.
 */
object Updater {
    const val REPO = "rafail3/roadsterOS"
    const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val MAPS = "https://api.github.com/repos/$REPO/releases/tags/maps"
    /** The APK's file name in every release. */
    const val APK = "roadsterOS.apk"

    /** A file of a release; [sha256] is GitHub's digest in lower-case hex, null on files uploaded before digests. */
    data class Asset(val name: String, val url: String, val bytes: Long, val sha256: String?)

    data class Release(val version: String, val notes: String, val assets: List<Asset>) {
        val apk get() = assets.firstOrNull { it.name == APK }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** A release from the GitHub API, or null for a damaged or unexpected text (an error page, a rate limit). */
    fun parseRelease(text: String): Release? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val assets = o.getValue("assets").jsonArray.map { a ->
            val f = a.jsonObject
            Asset(
                name = f.getValue("name").jsonPrimitive.content,
                url = f.getValue("browser_download_url").jsonPrimitive.content,
                bytes = f.getValue("size").jsonPrimitive.long,
                sha256 = f["digest"]?.jsonPrimitive?.content?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            )
        }
        Release(o.getValue("tag_name").jsonPrimitive.content.removePrefix("v"), o["body"]?.jsonPrimitive?.content.orEmpty(), assets)
    }.getOrNull()

    /** "1.16.0" against "1.15.2", part by part; a missing or non-numeric part counts as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    sealed interface Check {
        data object UpToDate : Check
        data class Available(val release: Release, val apk: Asset) : Check
        /** No answer, or an answer that could not be read: the screen offers to try again. */
        data object Failed : Check
    }

    /** What the latest release means for a unit running [current]; a release without the APK is not an update. */
    fun check(current: String, latest: String?): Check {
        val release = latest?.let(::parseRelease) ?: return Check.Failed
        val apk = release.apk ?: return Check.UpToDate
        return if (isNewer(release.version, current)) Check.Available(release, apk) else Check.UpToDate
    }

    /** Checked by itself at most this often, and only on Wi-Fi; About checks on demand at any time. */
    const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    fun dueForCheck(lastMs: Long?, nowMs: Long, wifi: Boolean) = wifi && (lastMs == null || nowMs - lastMs >= CHECK_EVERY_MS || nowMs < lastMs)

    /** Why an install waits. Restarting the app stops the screens and the sound for a few seconds. */
    enum class Wait { NONE, MOVING, CALL, REVERSE, SPACE }

    /** The unit keeps three times the file free: the download, the installed copy, and room left over. */
    fun installWait(speedKmh: Double, inCall: Boolean, reversing: Boolean, freeBytes: Long, fileBytes: Long): Wait = when {
        freeBytes < fileBytes * 3 -> Wait.SPACE
        reversing -> Wait.REVERSE
        inCall -> Wait.CALL
        speedKmh > 3 -> Wait.MOVING
        else -> Wait.NONE
    }

    /**
     * The country packs to download again: those on the unit whose file in the `maps` release has another digest
     * than the one saved when it was installed. Packs without a digest on either side are left alone.
     */
    fun mapUpdates(maps: Release, installed: Map<String, String?>): List<Asset> = maps.assets.filter { a ->
        val code = a.name.removeSuffix(MapPacks.EXTENSION)
        val have = installed[code]
        a.name.endsWith(MapPacks.EXTENSION) && code in installed && have != null && a.sha256 != null && have != a.sha256
    }
}
