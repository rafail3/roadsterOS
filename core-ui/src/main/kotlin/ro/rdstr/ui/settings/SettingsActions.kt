package ro.rdstr.ui.settings

import android.content.Context
import android.view.View
import ro.rdstr.car.CameraSettings
import ro.rdstr.car.CarCommand
import ro.rdstr.car.ShellAction
import ro.rdstr.car.SoundSettings
import ro.rdstr.car.TimeSource

/** Everything the settings app and its restart question ask of the runtime (SettingsApp.tsx, RestartPrompt.tsx). */
interface SettingsActions {
    /** `store.shellAction`. */
    fun shell(action: ShellAction)
    /** `store.carCommand` (the dash display's keys). */
    fun car(command: CarCommand)
    fun setWifi(on: Boolean)
    fun setBluetooth(on: Boolean)
    fun setLocation(on: Boolean)
    fun setTimeSync(auto: Boolean, source: TimeSource)
    /** Moves the unit's clock by [ms]. */
    fun shiftClock(ms: Long)
    fun setTimeZone(id: String)
    /** Every zone named after a place (`store.allTimeZones`) and a zone's offset from GMT now, in minutes. */
    fun timeZones(): List<String>
    fun zoneOffsetMinutes(id: String): Int
    fun setVolume(level: Int)
    fun setSound(next: SoundSettings)
    fun setCamera(next: CameraSettings)
    fun setCastVisible(on: Boolean)
    fun removePack(code: String)
    fun checkForUpdate()
    /** Download and Install, for the release found. */
    fun installUpdate()
    fun askRestart(on: Boolean)
    fun restartUnit()
    fun openAndroidSettings()
    fun factoryReset()
}

/**
 * The parts of the settings app built elsewhere, made by the display app and placed in the slots this view keeps:
 * the Wi-Fi networks list under the Wi-Fi switch (NetworksSheet.tsx `WifiNetworks`, `ro.rdstr.ui.sheets`) and the
 * unit's picture at the top of About (UnitPicture.tsx, `ro.rdstr.ui.setup`). Each is made once and kept; a slot is
 * attached to the window only while its page shows it, which is the `shown` of `WifiNetworks`.
 */
interface SettingsSlots {
    /** The networks list at the page's width (692 px), drawing and scanning on its own while attached. */
    fun wifiNetworks(context: Context): View
    /** The unit's picture, [width] px wide, right-aligned in the page. */
    fun unitPicture(context: Context, width: Float): View
}
