package ro.rdstr.cover

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** One screen: what the app still needs, a button for each, and the last cover it sent. */
class MainActivity : Activity() {
    private lateinit var access: TextView
    private lateinit var accessButton: Button
    private lateinit var bluetooth: TextView
    private lateinit var bluetoothButton: Button
    private lateinit var last: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); gravity = Gravity.TOP }
        fun text(size: Float) = TextView(this).apply { textSize = size; setPadding(0, pad / 3, 0, pad / 3) }
        column.addView(text(22f).apply { text = getString(R.string.app_name) })
        column.addView(text(15f).apply { text = "Sends the cover of the song playing on this phone to the car's display over Bluetooth." })
        access = text(16f).also(column::addView)
        accessButton = Button(this).apply { text = "Allow notification access"; setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }.also(column::addView)
        bluetooth = text(16f).also(column::addView)
        bluetoothButton = Button(this).apply {
            text = "Allow Bluetooth"
            setOnClickListener { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
        }.also(column::addView)
        last = text(15f).also(column::addView)
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    private fun refresh() {
        val listening = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            .contains(ComponentName(this, CoverListener::class.java).flattenToString())
        val bluetoothOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        access.text = if (listening) "Notification access: on" else "Notification access: needed, to see which song plays and its cover"
        accessButton.visibility = if (listening) Button.GONE else Button.VISIBLE
        bluetooth.text = if (bluetoothOk) "Bluetooth: allowed" else "Bluetooth: needed, to reach the car"
        bluetoothButton.visibility = if (bluetoothOk) Button.GONE else Button.VISIBLE
        last.text = Status.line(this)
    }
}
