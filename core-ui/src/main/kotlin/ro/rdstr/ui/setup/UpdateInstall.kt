package ro.rdstr.ui.setup

import ro.rdstr.car.Updater
import ro.rdstr.ui.Mirrors

/**
 * update.ts `useUpdateInstall`: where installing a new version is, as the setup's update screen and Settings > About >
 * Software Update show it. [download] is null until Install is pressed; [done] once the file is in, then the unit
 * counts [restartIn] seconds down and restarts. On the unit the work is app `Updates`; this is what it reports.
 */
@Mirrors("update.ts")
data class UpdateInstall(val download: Updater.Download? = null, val done: Boolean = false, val restartIn: Int = Updater.RESTART_IN_S) {
    companion object {
        /** update.ts `downloadFigures`: "1.1 MB of 2.4 MB · 480 KB/s · 2 s left". */
        fun figures(d: Updater.Download): String {
            val left = d.secondsLeft?.let(Updater::leftText)
            return "${Updater.sizeText(d.done)} of ${Updater.sizeText(d.total)} · ${Updater.speedText(d.bytesPerSecond)}" + (left?.let { " · $it" } ?: "")
        }
    }
}
