package ro.rdstr.gradle.apk

/**
 * What the screen's components ask of the logic, against what the display app implements.
 *
 * The screen is `Screen.tsx` and every component it imports, directly or not (the emulator's control panel and
 * inspector are not reached from it). Each value they import from `emulator/car/` (store actions, hooks, data) must
 * have an Android implementation marked `@Emulates("module.name")`, with a body; each `ShellAction` and `CarCommand`
 * a component dispatches must be dispatched by the views that mirror it (`@Mirrors("Component.tsx")`).
 * Pure decisions go through `bridge.*`, which is core-car on both sides, and need nothing.
 */
object LogicCheck {
    data class Use(val component: String, val line: Int, val module: String, val name: String) {
        val key get() = "$module.$name"
    }
    data class Dispatch(val component: String, val line: Int, val kind: String, val serial: String, val kotlin: String)
    data class Implementation(val key: String, val at: String, val empty: Boolean)
    data class Mirror(val component: String, val at: String, val text: String)

    class Report(
        val screen: List<String>,
        val uses: List<Use>,
        val dispatches: List<Dispatch>,
        val implementations: List<Implementation>,
        val mirrors: List<Mirror>,
        val problems: List<String>,
    ) {
        private val implemented get() = implementations.filter { !it.empty }.map { it.key }.toSet()
        private val mirrored get() = mirrors.map { it.component }.toSet()
        val missingLogic get() = uses.filter { it.key !in implemented }
        val missingViews get() = screen.filter { it !in mirrored && !it.endsWith(".ts") }
        val missingDispatches get() = dispatches.filter { d ->
            val views = mirrors.filter { it.component == d.component }
            views.isNotEmpty() && views.none { d.kotlin in it.text }
        }

        /** Gaps the build stops on: always the ones inside mirrored components; with [strict], every gap. */
        fun failures(strict: Boolean): List<String> {
            val out = problems.toMutableList()
            val mirroredNow = mirrored
            for (u in missingLogic) if (strict || u.component in mirroredNow)
                out += "${u.component}:${u.line} uses ${u.key}, which has no @Emulates(\"${u.key}\") implementation with a body on Android"
            for (d in missingDispatches) out += "${d.component}:${d.line} dispatches ${d.kind} ${d.serial}; its views never send ${d.kotlin}"
            if (strict) for (c in missingViews) out += "$c has no view marked @Mirrors(\"$c\")"
            return out
        }

        fun markdown(): String = buildString {
            appendLine("# Display logic")
            appendLine()
            appendLine("Screen components: ${screen.size}; mirrored on Android: ${screen.count { c -> mirrors.any { it.component == c } }}.")
            appendLine("Logic they use: ${uses.map { it.key }.distinct().size}; implemented: ${uses.map { it.key }.distinct().count { it in implemented }}.")
            appendLine()
            if (problems.isNotEmpty()) { appendLine("## Errors"); problems.forEach { appendLine("- $it") }; appendLine() }
            appendLine("## Logic without an Android implementation")
            for ((key, list) in missingLogic.groupBy { it.key }.toSortedMap())
                appendLine("- `$key` — ${list.joinToString { "${it.component}:${it.line}" }}")
            appendLine()
            appendLine("## Components without a view")
            missingViews.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Dispatches missing from mirrored views")
            missingDispatches.forEach { appendLine("- ${it.component}:${it.line} ${it.kind} `${it.serial}` (${it.kotlin})") }
            appendLine()
            appendLine("## Implemented")
            for (i in implementations.sortedBy { it.key }) appendLine("- `${i.key}` — ${i.at}${if (i.empty) " (empty body)" else ""}")
        }
    }

    /**
     * [components] and [carModules] by file name (`Drawer.tsx`, `store.ts`), [coreCar] the common Kotlin sources of
     * core-car, [android] the Kotlin sources of the display app by path.
     */
    fun run(components: Map<String, String>, carModules: Map<String, String>, coreCar: List<String>, android: Map<String, String>, root: String = "Screen.tsx"): Report {
        val problems = mutableListOf<String>()
        val screen = reach(components, root)
        val exports = carModules.mapValues { (_, text) -> valueExports(text) }
        val serials = serialNames(coreCar)

        val uses = mutableListOf<Use>()
        val dispatches = mutableListOf<Dispatch>()
        for (file in screen) {
            val text = components.getValue(file)
            for (imp in imports(text)) {
                val module = imp.from.removePrefix("../car/").takeIf { imp.from.startsWith("../car/") } ?: continue
                if (module == "bridge" || imp.typeOnly) continue
                val values = exports[module] ?: run { problems += "$file imports ../car/$module, which is not in emulator/car"; continue }
                // an import the file never uses asks nothing of the logic
                for (name in imp.names) if (name in values) firstUse(text, name, imp.endLine)?.let { uses += Use(file, it, module, name) }
            }
            text.lines().forEachIndexed { i, line ->
                if (!Regex("""\btype:""").containsMatchIn(line)) return@forEachIndexed
                for (m in Regex(""""([A-Za-z][\w.]*)"""").findAll(line)) {
                    val serial = m.groupValues[1]
                    serials[serial]?.let { (kind, kotlin) -> dispatches += Dispatch(file, i + 1, kind, serial, kotlin) }
                }
            }
        }

        val implementations = mutableListOf<Implementation>()
        val mirrors = mutableListOf<Mirror>()
        val allExports = exports.flatMap { (m, v) -> v.map { "$m.$it" } }.toSet()
        for ((path, text) in android) {
            for (m in Regex("""@Emulates\(([^)]*)\)""").findAll(text)) {
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                val keys = Regex(""""([^"]+)"""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
                val empty = emptyBody(text, m.range.last + 1)
                for (key in keys) {
                    if (key !in allExports) problems += "$path:$line @Emulates(\"$key\"): emulator/car exports no such value"
                    implementations += Implementation(key, "$path:$line", empty)
                }
            }
            for (m in Regex("""@Mirrors\(([^)]*)\)""").findAll(text)) {
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                for (c in Regex(""""([^"]+)"""").findAll(m.groupValues[1]).map { it.groupValues[1] }) {
                    if (c !in components) problems += "$path:$line @Mirrors(\"$c\"): emulator/components has no such file"
                    else if (c !in screen) problems += "$path:$line @Mirrors(\"$c\"): $c is not part of the screen (not reached from $root)"
                    mirrors += Mirror(c, "$path:$line", text)
                }
            }
        }
        return Report(screen.sorted(), uses, dispatches, implementations, mirrors, problems)
    }

    /** Files reached from [root] through `./` imports. */
    fun reach(components: Map<String, String>, root: String): Set<String> {
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (!seen.add(f)) continue
            val text = components[f] ?: throw ApkBuildException("emulator/components/$f is imported but missing")
            for (imp in imports(text)) if (imp.from.startsWith("./")) {
                val stem = imp.from.removePrefix("./")
                queue += listOf("$stem.tsx", "$stem.ts").firstOrNull { it in components }
                    ?: throw ApkBuildException("$f imports ${imp.from}, which is not in emulator/components")
            }
        }
        return seen
    }

    data class Import(val names: List<String>, val from: String, val typeOnly: Boolean, val endLine: Int)

    /** `import [type] { a, type B, c as d } from "x"` statements, multi-line ones too; names as the file uses them. */
    fun imports(text: String): List<Import> =
        Regex("""import\s+(type\s+)?\{([^}]*)\}\s*from\s*"([^"]+)"""").findAll(text).map { m ->
            val names = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("type ") }
                .map { it.substringAfter(" as ").trim() }
            Import(names, m.groupValues[3], m.groupValues[1].isNotEmpty(), text.substring(0, m.range.last).count { it == '\n' } + 1)
        }.toList()

    /** The runtime values a module exports: functions, constants, classes, enums (not types). */
    fun valueExports(text: String): Set<String> =
        Regex("""^export\s+(?:async\s+)?(?:function\*?|const|let|class|enum)\s+([A-Za-z_]\w*)""", RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1] }.toSet()

    private fun firstUse(text: String, name: String, after: Int): Int? {
        val lines = text.lines()
        for (i in after until lines.size) if (Regex("""(?<![\w.])${Regex.escape(name)}\b""").containsMatchIn(lines[i])) return i + 1
        return null
    }

    /**
     * `@SerialName` of every `ShellAction` and `CarCommand` subclass, with the Kotlin name a view writes to send it
     * (`ShellAction.OpenDevices`, `CarCommand.Bluetooth.Connect`).
     */
    fun serialNames(sources: List<String>): Map<String, Pair<String, String>> {
        val out = mutableMapOf<String, Pair<String, String>>()
        val decl = Regex("""\b(?:class|object|interface)\s+([A-Z]\w*)""")
        for (text in sources) {
            val stack = ArrayDeque<Pair<Int, String>>()
            var depth = 0
            for (line in text.lines()) {
                val name = decl.find(line)?.groupValues?.get(1)
                val serial = Regex("""@SerialName\("([^"]+)"\)""").find(line)?.groupValues?.get(1)
                if (name != null && serial != null) {
                    val path = stack.map { it.second } + name
                    if (path.first() == "ShellAction" || path.first() == "CarCommand")
                        out[serial] = path.first() to path.joinToString(".")
                }
                val before = depth
                for (c in line) { if (c == '{') depth++; if (c == '}') depth-- }
                while (stack.isNotEmpty() && depth <= stack.last().first) stack.removeLast()
                if (name != null && depth > before) stack.addLast(before to name)
            }
        }
        return out
    }

    /** The function after an annotation has no body, an empty one, or only a `TODO()`; a property or class counts as one. */
    fun emptyBody(text: String, from: Int): Boolean {
        val funAt = Regex("""\b(fun|val|var|class|object|interface)\s""").find(text, from) ?: return true
        if (funAt.groupValues[1] != "fun") return false
        val open = text.indexOf('{', funAt.range.last)
        val eq = text.indexOf('=', funAt.range.last)
        val sig = text.indexOf(')', funAt.range.last)
        if (eq in (sig + 1) until (if (open < 0) Int.MAX_VALUE else open)) {
            // an expression body may start on the next line
            val expr = text.substring(eq + 1).trimStart().lineSequence().first().trim()
            return expr.isEmpty() || expr.startsWith("TODO(") || expr == "Unit"
        }
        if (open < 0) return true
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}') { depth--; if (depth == 0) {
                val body = text.substring(open + 1, i).lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
                return body.isEmpty() || body.all { it.startsWith("TODO(") }
            } }
        }
        return true
    }
}
