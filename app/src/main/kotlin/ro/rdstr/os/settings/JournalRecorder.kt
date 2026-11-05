package ro.rdstr.os.settings

import android.os.Process
import android.os.SystemClock
import android.util.Log
import ro.rdstr.car.JournalFiles
import ro.rdstr.car.JournalFilter
import ro.rdstr.car.JournalRecord
import ro.rdstr.car.Journal
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Writes the unit's journal while recording is on (core-car JournalFiles): car events, commands, screen actions, and
 * the app's own warnings and errors from all its modules, read from its logcat, plus crashes before the process ends.
 */
class JournalRecorder(private val dir: File, private val header: String) {
    private val startedAt = SystemClock.elapsedRealtime()
    private val filter = JournalFilter()
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var headed = false
    private val lock = Any()
    private var logcat: java.lang.Process? = null
    private var day = ""

    fun write(r: JournalRecord): Unit = synchronized(lock) {
        if (!filter.keep(r)) return
        val now = Date()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        if (today != day) { day = today; tidy() }
        val file = File(dir, JournalFiles.today(day))
        val t = SystemClock.elapsedRealtime() - startedAt
        // each start, and each new day's file, opens with what the lines come from
        val lines = buildList {
            if (!headed || !file.exists()) { add(JournalRecord(t, clock.format(now), start = header)); headed = true }
            add(r.copy(t = t, at = clock.format(now)))
        }
        runCatching { file.appendText(lines.joinToString("") { Journal.encodeRecord(it) + "\n" }) }
    }

    fun error(text: String) = write(JournalRecord(0, error = text))

    /** Starts or stops following the app's own warnings and errors in logcat. */
    fun followLogs(on: Boolean) {
        recording = on
        logcat?.destroy(); logcat = null
        if (!on) return
        // our own process only, warnings and up; an app may read its own log on Android 8.1
        val p = runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief", "-T", "1", "--pid=${Process.myPid()}", "*:W")) }.getOrNull() ?: return
        logcat = p
        Thread {
            p.inputStream.bufferedReader().forEachLine { if (!it.startsWith("-")) error(it) }
        }.apply { isDaemon = true; name = "journal-logcat" }.start()
    }

    /** A crash is written before the process ends, then the usual handler runs. */
    /** On while recording; crashes outside a recording go to logcat only. */
    @Volatile var recording = false

    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (recording) error("crash in ${thread.name}: ${Log.getStackTraceString(e)}")
            previous?.uncaughtException(thread, e)
        }
    }

    private fun tidy() {
        dir.mkdirs()
        val names = dir.list()?.toList().orEmpty()
        for (name in JournalFiles.toCompress(names, day)) {
            val plain = File(dir, name)
            runCatching {
                GZIPOutputStream(File(dir, name.removeSuffix(JournalFiles.SUFFIX) + JournalFiles.GZ).outputStream()).use { out -> plain.inputStream().use { it.copyTo(out) } }
                plain.delete()
            }
        }
        val sizes = dir.listFiles()?.associate { it.name to it.length() }.orEmpty()
        JournalFiles.toDelete(sizes, day).forEach { File(dir, it).delete() }
    }
}
