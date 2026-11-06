package ro.rdstr.car

/**
 * The first start of a unit: the screens in order, which of them apply, the car's name, and the tasks the
 * "Preparing the system" screen runs. A unit switched off halfway resumes at the screen it was on.
 */
object Setup {
    @kotlinx.serialization.Serializable
    enum class Step { LOGO, START, CAR_NAME, PREPARE, WIFI, UPDATE, LOCATION, LOOK, FILES, WELCOME }

    /**
     * The screen after [step]. The update screen shows only with Wi-Fi and a newer version; without them the setup
     * goes on, and Settings > About checks again whenever the unit is on Wi-Fi. Null after the welcome screen.
     */
    fun next(step: Step, wifi: Boolean, updateAvailable: Boolean): Step? {
        val after = Step.entries.getOrNull(step.ordinal + 1) ?: return null
        return if (after == Step.UPDATE && !(wifi && updateAvailable)) next(after, wifi, updateAvailable) else after
    }

    /**
     * The screen before [step]. Back never reaches the logo or the start screen, and skips the update and the system
     * preparation: one is gone once the update is in, the other runs once.
     */
    fun back(step: Step): Step {
        var at = step
        while (at.ordinal > Step.CAR_NAME.ordinal) {
            at = Step.entries[at.ordinal - 1]
            if (at != Step.UPDATE && at != Step.PREPARE) return at
        }
        return at.takeIf { it.ordinal >= Step.START.ordinal } ?: step
    }

    /** A Wi-Fi network in range: its name, its signal 0..4, whether it asks for a password. */
    @kotlinx.serialization.Serializable
    data class Network(val ssid: String, val signal: Int, val secure: Boolean)

    /** The Wi-Fi screen's list: the joined network first, then the saved ones, then by signal; each name once. */
    fun networkOrder(networks: List<Network>, connected: String?, saved: Set<String>): List<Network> =
        networks.sortedByDescending { it.signal }.distinctBy { it.ssid }
            .sortedBy { if (it.ssid == connected) 0 else if (it.ssid in saved) 1 else 2 }

    const val CAR_NAME_MIN = 3
    const val CAR_NAME_MAX = 24

    /** What a car name is made of: letters, digits, and spaces between words. */
    fun carNameChar(c: Char) = c.isLetterOrDigit() || c == ' '

    /**
     * The car's name as typed: spaces trimmed and collapsed, cut to [CAR_NAME_MAX] characters (it is also the name
     * phones see for screen casting, and Wi-Fi Direct allows 32 bytes). Null for anything but letters, digits and
     * spaces, or fewer than [CAR_NAME_MIN] letters and digits: the screen keeps asking.
     */
    fun carName(typed: String): String? {
        val name = typed.trim().split(Regex("\\s+")).joinToString(" ").take(CAR_NAME_MAX).trimEnd()
        return name.takeIf { n -> n.all(::carNameChar) && n.count { it.isLetterOrDigit() } >= CAR_NAME_MIN }
    }

    /**
     * What the system preparation does, in this order. [KEYS] checks that the unit's firmware trusts our signing
     * key (the public platform key), which gives our app the system's own permissions; the next two need it.
     */
    @kotlinx.serialization.Serializable
    enum class Task { KEYS, PACKAGES, PERMISSIONS, HOME }

    @kotlinx.serialization.Serializable
    enum class TaskState { WAITING, RUNNING, DONE, FAILED, SKIPPED }

    private val NEEDS_KEYS = setOf(Task.PACKAGES, Task.PERMISSIONS)

    @kotlinx.serialization.Serializable
    data class Prepare(val tasks: Map<Task, TaskState> = Task.entries.associateWith { TaskState.WAITING }) {
        /** The next task to run, or null when all have an outcome. */
        val next get() = Task.entries.firstOrNull { tasks[it] == TaskState.WAITING }
        val finished get() = next == null && TaskState.RUNNING !in tasks.values
        val progress get() = tasks.values.count { it != TaskState.WAITING && it != TaskState.RUNNING }.toFloat() / tasks.size
        /** The setup moves on only when nothing failed; a failed task shows a retry. */
        val ok get() = finished && TaskState.FAILED !in tasks.values

        fun running(task: Task) = copy(tasks = tasks + (task to TaskState.RUNNING))

        /**
         * A task's outcome. A firmware that does not trust our key is not an error: the unit gives our app no
         * system permissions, so packages and permissions are left to the installer on the laptop (adb) and skipped.
         */
        fun result(task: Task, done: Boolean) = copy(tasks = tasks + when {
            done -> mapOf(task to TaskState.DONE)
            task == Task.KEYS -> (NEEDS_KEYS + Task.KEYS).associateWith { TaskState.SKIPPED }
            else -> mapOf(task to TaskState.FAILED)
        })

        /** Failed tasks go back to waiting. */
        fun retry() = copy(tasks = tasks.mapValues { (_, s) -> if (s == TaskState.FAILED) TaskState.WAITING else s })
    }
}
