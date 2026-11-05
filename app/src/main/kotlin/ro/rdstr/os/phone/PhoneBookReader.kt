package ro.rdstr.os.phone

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import ro.rdstr.car.CallRecord
import ro.rdstr.car.CallType
import ro.rdstr.car.Contact
import ro.rdstr.car.PhoneNumber

/**
 * The phone book and call history Android's Bluetooth stack copies from the phone (PBAP client) into the system
 * contacts and call log. Needs READ_CONTACTS and READ_CALL_LOG, granted by the device owner.
 */
object PhoneBookReader {
    private const val TAG = "rdstr.phone"
    /** The call history goes back this far on screen. */
    private const val MAX_CALLS = 200

    fun contacts(context: Context): List<Contact> = runCatching {
        val byId = linkedMapOf<Long, Contact>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1).orEmpty()
                val number = c.getString(2) ?: continue
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString().lowercase()
                val known = byId[id]
                byId[id] = known?.copy(numbers = known.numbers + PhoneNumber(number, label)) ?: Contact(name.ifEmpty { number }, listOf(PhoneNumber(number, label)))
            }
        }
        byId.values.toList()
    }.onFailure { Log.w(TAG, "contacts unreadable", it) }.getOrDefault(emptyList())

    fun calls(context: Context): List<CallRecord> = runCatching {
        val out = mutableListOf<CallRecord>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX_CALLS) {
                val type = when (c.getInt(2)) {
                    CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CallType.MISSED
                    else -> CallType.INCOMING
                }
                out += CallRecord(c.getString(0).orEmpty(), c.getString(1).orEmpty(), type, c.getLong(3))
            }
        }
        out
    }.onFailure { Log.w(TAG, "call log unreadable", it) }.getOrDefault(emptyList())
}
