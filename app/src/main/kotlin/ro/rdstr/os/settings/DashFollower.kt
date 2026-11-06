package ro.rdstr.os.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import ro.rdstr.car.DashClock
import java.util.TimeZone

/**
 * Keeps the dash display on the unit's clock once it was matched by hand: every minute tick, set time and zone change
 * compares the clock with its last mark, and a jump of the local time is pressed into the display (core-car DashClock).
 * The last mark is kept across starts, so summer time that changed while the car was off is caught at the next start.
 */
class DashFollower(private val context: Context, private val prefs: SharedPreferences, private val press: (DashClock.Key) -> Unit) {
    private var mark = now()

    fun start(matched: () -> Boolean) {
        val stored = stored()
        val n = now()
        if (stored != null && matched()) follow(n, DashClock.jumpAcrossRestart(stored, n))
        save(n)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val next = now()
                val jump = DashClock.jump(mark, next)
                if (jump != 0 && matched()) follow(next, jump)
                save(next)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
    }

    /** The display was just matched: it shows the unit's time now. */
    fun matched() = save(now())

    private fun follow(now: DashClock.Mark, jump: Int) {
        val keys = DashClock.follow(DashClock.localMinutes(now) - jump, jump)
        Log.i(Radios.TAG, "dash follows a jump of $jump min: ${keys.size} keys")
        keys.forEach(press)
    }

    private fun now(): DashClock.Mark {
        val wall = System.currentTimeMillis()
        return DashClock.Mark(wall, SystemClock.elapsedRealtime(), TimeZone.getDefault().getOffset(wall) / 60_000)
    }

    private fun save(m: DashClock.Mark) {
        mark = m
        prefs.edit().putLong(WALL, m.wallMs).putLong(ELAPSED, m.elapsedMs).putInt(OFFSET, m.offsetMin).apply()
    }

    private fun stored(): DashClock.Mark? =
        if (!prefs.contains(WALL)) null else DashClock.Mark(prefs.getLong(WALL, 0), prefs.getLong(ELAPSED, 0), prefs.getInt(OFFSET, 0))

    private companion object {
        const val WALL = "dash.wall"
        const val ELAPSED = "dash.elapsed"
        const val OFFSET = "dash.offset"
    }
}
