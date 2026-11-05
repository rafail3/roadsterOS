package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalFilesTest {
    @Test
    fun `every kind of line is written compactly and the simulator reads back only the car events`() {
        val text = listOf(
            JournalRecord(10, event = CarEvent.ReverseGear(true)),
            JournalRecord(20, command = CarCommand.Phone.Dial("112")),
            JournalRecord(30, shell = ShellAction.Home),
            JournalRecord(40, error = "x".repeat(5000)),
            JournalRecord(50, event = CarEvent.ReverseGear(false)),
        ).joinToString("\n") { Journal.encodeRecord(it) }
        assertEquals(listOf(10L, 50L), Journal.events(text).map { it.t })
        assertEquals("""{"t":20,"command":{"type":"Phone.Dial","number":"112"}}""", text.lines()[1])
        assertTrue(text.lines()[3].length < Journal.MAX_ERROR + 30)
    }

    @Test
    fun `repeats and the playback position are dropped, the rest kept`() {
        val f = JournalFilter()
        val status = JournalRecord(0, event = CarEvent.PhoneStatus(battery = 4))
        assertTrue(f.keep(status))
        assertEquals(false, f.keep(status))
        assertTrue(f.keep(JournalRecord(0, event = CarEvent.PhoneStatus(battery = 3))))
        assertTrue(f.keep(JournalRecord(0, event = CarEvent.Playback(true, 1000))))
        assertEquals(false, f.keep(JournalRecord(0, event = CarEvent.Playback(true, 2000))))
        assertTrue(f.keep(JournalRecord(0, event = CarEvent.Playback(false, 2500))))
        // never dropped: commands, screen actions, errors
        assertTrue(f.keep(JournalRecord(0, shell = ShellAction.Home)))
        assertTrue(f.keep(JournalRecord(0, shell = ShellAction.Home)))
        assertTrue(f.keep(JournalRecord(0, error = "E/rdstr.phone: no phone")))
    }

    @Test
    fun `a line carries its date and time`() {
        val line = Journal.encodeRecord(JournalRecord(5, at = "2026-11-06 14:05:09.123", event = CarEvent.Power(true)))
        assertEquals("""{"t":5,"at":"2026-11-06 14:05:09.123","event":{"type":"Power","acc":true}}""", line)
    }

    @Test
    fun `ended days are compressed and the oldest go once the journal is too big`() {
        val names = listOf("events-2026-11-04.jsonl", "events-2026-11-05.jsonl.gz", "events-2026-11-06.jsonl")
        assertEquals(listOf("events-2026-11-04.jsonl"), JournalFiles.toCompress(names, "2026-11-06"))
        val files = mapOf("events-2026-11-03.jsonl.gz" to 400L, "events-2026-11-04.jsonl.gz" to 400L, "events-2026-11-05.jsonl.gz" to 400L, "events-2026-11-06.jsonl" to 300L)
        assertEquals(listOf("events-2026-11-04.jsonl.gz", "events-2026-11-03.jsonl.gz"), JournalFiles.toDelete(files, "2026-11-06", max = 1000))
        // today's file stays even when it alone is too big
        assertEquals(emptyList(), JournalFiles.toDelete(mapOf("events-2026-11-06.jsonl" to 5000L), "2026-11-06", max = 1000))
    }
}
