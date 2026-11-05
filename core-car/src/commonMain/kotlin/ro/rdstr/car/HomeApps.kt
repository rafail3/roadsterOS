package ro.rdstr.car

/** An app the unit can start: one of ours by name, or another installed app by its package. */
@kotlinx.serialization.Serializable
data class LaunchableApp(val id: String, val label: String)

/** The row of app icons on home: which apps, in which order, chosen in the settings. */
object HomeApps {
    /** The row fits this many icons beside the card. */
    const val MAX = 4
    val DEFAULT = listOf("MAPS", "MUSIC", "PHONE")
    /** Our own apps, always there to choose from. */
    val OWN = listOf(
        LaunchableApp("MAPS", "Maps"), LaunchableApp("MUSIC", "Music"), LaunchableApp("PHONE", "Phone"),
        LaunchableApp("RADIO", "Radio"), LaunchableApp("SETTINGS", "Settings"),
    )

    fun toggle(chosen: List<String>, id: String): List<String> = when {
        id in chosen -> chosen - id
        chosen.size >= MAX -> chosen
        else -> chosen + id
    }

    fun move(chosen: List<String>, id: String, by: Int): List<String> {
        val i = chosen.indexOf(id)
        val j = (i + by).coerceIn(0, chosen.lastIndex)
        if (i < 0 || i == j) return chosen
        return chosen.toMutableList().apply { removeAt(i); add(j, id) }
    }

    /** The icons home shows: the chosen ones still installed, in their order. */
    fun shown(chosen: List<String>, installed: List<LaunchableApp>): List<LaunchableApp> {
        val all = (OWN + installed).associateBy { it.id }
        return chosen.mapNotNull { all[it] }
    }
}
