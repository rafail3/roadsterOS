package ro.rdstr.carvendor

import android.util.Log
import ro.rdstr.car.DashClock

/**
 * The dash display's keys, with the codes the vendor's Car Info screen sends for this car (com.ts.MainUI,
 * CanMZDKeyActivity: CanJni.MZDSendKey with the code on press and 0 on release). The CAN library (libcan50.so) runs
 * inside the vendor's process, so for now each key is logged with its code; the press goes through once the unit
 * shows which way in works.
 */
class VendorDash {
    fun press(key: DashClock.Key) {
        Log.i(TAG, "dash key $key code=${code(key)} release=$RELEASE")
    }

    companion object {
        private const val TAG = "rdstr.dash"
        const val RELEASE = 0
        fun code(key: DashClock.Key) = when (key) {
            DashClock.Key.HOUR -> 64
            DashClock.Key.CLOCK -> 32
            DashClock.Key.MINUTE -> 128
        }
    }
}
