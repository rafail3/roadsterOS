package ro.rdstr.car

/** RDS data broadcast by FM stations, as the tuner passes it on. */
object Rds {
    /** Programme type names, European RDS table, by code 0..31; code 0 means none. */
    private val PTY = listOf(
        "", "News", "Current Affairs", "Information", "Sport", "Education", "Drama", "Culture",
        "Science", "Varied", "Pop Music", "Rock Music", "Easy Listening", "Light Classical", "Serious Classical", "Other Music",
        "Weather", "Finance", "Children's Programmes", "Social Affairs", "Religion", "Phone-In", "Travel", "Leisure",
        "Jazz Music", "Country Music", "National Music", "Oldies Music", "Folk Music", "Documentary", "Alarm Test", "Alarm",
    )

    fun ptyName(code: Int): String = PTY.getOrElse(code) { "" }

    /** The station name arrives as 8 characters padded with spaces. */
    fun stationName(ps: String): String = ps.trim()
}
