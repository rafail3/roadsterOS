package ro.rdstr.car

/**
 * The first start of a unit: the screens in order, which of them apply, the car's name, and the tasks the
 * "Preparing the system" screen runs. A unit switched off halfway resumes at the screen it was on.
 */
object Setup {
    enum class Step { LOGO, START, CAR_NAME, PREPARE, WIFI, UPDATE, LOCATION, LOOK, FILES, WELCOME }

    /**
     * The screen after [step]. The update screen shows only with Wi-Fi and a newer version; without them the setup
     * goes on, and Settings > About checks again whenever the unit is on Wi-Fi. Null after the welcome screen.
     */
    fun next(step: Step, wifi: Boolean, updateAvailable: Boolean): Step? {
        val after = Step.entries.getOrNull(step.ordinal + 1) ?: return null
        return if (after == Step.UPDATE && !(wifi && updateAvailable)) next(after, wifi, updateAvailable) else after
    }

    /** Where a unit starts: nothing saved (or a damaged value) is a first start; the logo is never resumed. */
    fun resumeAt(saved: String?): Step =
        Step.entries.firstOrNull { it.name == saved }?.takeIf { it != Step.LOGO } ?: Step.START

    const val CAR_NAME_MAX = 24

    /**
     * The car's name as typed: spaces trimmed and collapsed, cut to [CAR_NAME_MAX] characters (it is also the name
     * phones see for screen casting, and Wi-Fi Direct allows 32 bytes). Blank gives null: the screen keeps asking.
     */
    fun carName(typed: String): String? =
        typed.trim().split(Regex("\\s+")).joinToString(" ").take(CAR_NAME_MAX).trimEnd().ifEmpty { null }

    /**
     * What the system preparation does, in this order. [KEYS] checks that the unit's firmware trusts our signing
     * key (the public platform key), which gives our app the system's own permissions; the next two need it.
     */
    enum class Task { KEYS, PACKAGES, PERMISSIONS, HOME }

    enum class TaskState { WAITING, RUNNING, DONE, FAILED, SKIPPED }

    private val NEEDS_KEYS = setOf(Task.PACKAGES, Task.PERMISSIONS)

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
