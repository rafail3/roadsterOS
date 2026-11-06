package ro.rdstr.os.setup

import ro.rdstr.car.Updater
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Plain HTTPS for the updates and the map packs: GitHub's API and its release files. Call off the main thread. */
object Http {
    private const val TIMEOUT_MS = 15_000
    /** The bar moves at most this often; each step carries the bytes since the last one. */
    private const val STEP_MS = 200L

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "roadsterOS")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** The body of a GET, or null for any failure (no network, a rate limit, an error page). */
    fun text(url: String): String? = runCatching {
        val c = open(url)
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    /**
     * Downloads [url] to [to] (a partial file first, renamed when whole), reporting the bar's figures as they change.
     * With [resume], a partial file left by a broken download is continued with an HTTP range request.
     * Returns the file's SHA-256 in lower-case hex, to compare with the digest GitHub publishes.
     */
    fun download(url: String, to: File, total: Long, resume: Boolean = false, onProgress: (Updater.Download) -> Unit): String {
        val part = File(to.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val have = if (resume && part.exists()) part.length() else 0L
        val c = open(url)
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            // a range past the end: the partial file is not of this download
            if (c.responseCode == 416) part.delete()
            check(c.responseCode == 200 || c.responseCode == 206) { "HTTP ${c.responseCode}" }
            val from = if (c.responseCode == 206) have else 0L
            if (from > 0) part.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val size = if (c.contentLengthLong > 0) from + c.contentLengthLong else total
            var d = Updater.Download(size, done = from)
            onProgress(d)
            var since = System.currentTimeMillis()
            var pending = 0L
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, from > 0).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        pending += n
                        val now = System.currentTimeMillis()
                        if (now - since >= STEP_MS) {
                            d = d.step(pending, now - since)
                            onProgress(d)
                            pending = 0
                            since = now
                        }
                    }
                }
            }
            onProgress(d.step(pending, System.currentTimeMillis() - since))
        } finally {
            c.disconnect()
        }
        check(part.renameTo(to) || (to.delete() && part.renameTo(to))) { "cannot keep ${to.name}" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
