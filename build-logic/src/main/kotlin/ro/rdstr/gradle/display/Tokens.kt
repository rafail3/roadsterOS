package ro.rdstr.gradle.display

/**
 * Where a custom property applies: the screen itself ([chain] empty) or the elements of a component, as the chain of
 * class sets the selector names from outer to inner; with the light theme and/or the glassy design only, or always.
 */
data class Scope(val chain: List<Set<String>>, val light: Boolean, val glassy: Boolean) {
    val component get() = chain.joinToString(" ") { it.joinToString(".") }
}

/** One theme and design to resolve values in, for the elements of [chain]. */
data class Context(val chain: List<Set<String>>, val light: Boolean, val glassy: Boolean)

/** A generated class: one component (or the screen) with its fields in each theme and design. */
data class TokenClass(val name: String, val component: String, val fields: List<Field>, val instances: Map<String, Map<String, TokenValue?>>) {
    data class Field(val name: String, val property: String, val type: String, val nullable: Boolean, val files: List<String>)
}

/** The emulator's icon classes: `.i-name { --m: url(/icons/file.svg) }`. */
data class IconClass(val cssClass: String, val file: String)

/**
 * The custom properties of the emulator's sheets resolved the way the browser does: per element, the most specific
 * matching rule wins, then the latest; the screen's own rules beat `:root`; `var()` is substituted in the element's
 * own context, so a value written for the dark theme follows the light theme's `--on` where it is not set again.
 */
class Tokens(private val declarations: List<Declaration>) {
    private data class Entry(val decl: Declaration, val scope: Scope, val specificity: Int, val root: Boolean)

    val icons: List<IconClass>
    private val entries: List<Entry>

    init {
        val iconList = mutableListOf<IconClass>()
        val list = mutableListOf<Entry>()
        for (d in declarations) {
            val icon = Regex("""^\.(i-[a-z0-9-]+)$""").find(d.selector)
            if (icon != null && d.name == "--m") {
                val url = Values.parse(d.value, "${d.file} ${d.selector}") as? UrlValue
                    ?: throw DisplayBuildException("${d.file} ${d.selector}: --m must be url()")
                iconList += IconClass(icon.groupValues[1], url.url.substringAfterLast('/'))
                continue
            }
            list += Entry(d, scopeOf(d.file, d.selector), Css.specificity(d.selector), d.selector.trim() == ":root")
        }
        icons = iconList
        entries = list
    }

    /** The value of [name] for [ctx], its `var()`s substituted, or null when nothing sets it there. */
    fun raw(name: String, ctx: Context, seen: Set<String> = emptySet()): String? {
        if (name in seen) throw DisplayBuildException("var() cycle through ${seen.joinToString()} and $name")
        val hit = entries.asSequence()
            .filter { it.decl.name == name && (!it.scope.light || ctx.light) && (!it.scope.glassy || ctx.glassy) }
            .filter { it.scope.chain.isEmpty() || descendantMatch(it.scope.chain, ctx.chain) }
            .maxWithOrNull(compareBy<Entry>({ it.scope.chain.isNotEmpty() }, { !it.root }, { it.specificity }, { it.decl.order }))
            ?: return null
        return substitute(hit.decl.value, ctx, seen + name)
    }

    fun resolve(name: String, ctx: Context): TokenValue? = raw(name, ctx)?.let { Values.parse(it, "$name in ${describe(ctx)}") }

    private fun substitute(value: String, ctx: Context, seen: Set<String>): String {
        val at = value.indexOf("var(")
        if (at < 0) return value
        val close = Css.matching(value, at + 3)
        val args = Css.splitTop(value.substring(at + 4, close), ',')
        val ref = args[0].trim()
        val fallback = if (args.size > 1) value.substring(at + 4, close).substringAfter(',').trim() else null
        val inner = raw(ref, ctx, seen) ?: fallback?.let { substitute(it, ctx, seen) }
            ?: throw DisplayBuildException("var($ref) has no value in ${describe(ctx)}")
        return substitute(value.substring(0, at) + inner + value.substring(close + 1), ctx, seen)
    }

    /** The generated classes: `Base` for the screen, `Glassy` for the glassy design's own, one per component. */
    fun classes(): List<TokenClass> {
        val out = mutableListOf<TokenClass>()
        val global = entries.filter { it.scope.chain.isEmpty() }
        val glassyOnly = global.groupBy { it.decl.name }.filterValues { e -> e.all { it.scope.glassy } }.keys
        out += build("Base", "", emptyList(), global.map { it.decl.name }.filter { it !in glassyOnly })
        out += build("Glassy", "", emptyList(), glassyOnly.toList())
        val components = entries.filter { it.scope.chain.isNotEmpty() }.groupBy { it.scope.component }
        val names = mutableSetOf("Base", "Glassy")
        for ((component, list) in components.toSortedMap()) {
            val name = className(component)
            if (!names.add(name)) throw DisplayBuildException("two components named $name")
            out += build(name, component, list.first().scope.chain, list.map { it.decl.name })
        }
        return out
    }

    private fun build(name: String, component: String, chain: List<Set<String>>, properties: List<String>): TokenClass {
        val props = properties.distinct()
        val all = linkedMapOf<String, Map<String, TokenValue?>>()
        for ((instance, light, glassy) in INSTANCES) {
            val ctx = Context(chain, light, glassy)
            all[instance] = props.associateWith { resolve(it, ctx) }
        }
        // a design or theme that sets none of the fields has no instance (the glassy design's own tokens under solid)
        val instances = all.filterValues { v -> v.values.any { it != null } }
        val fields = props.map { p ->
            val types = instances.values.mapNotNull { it[p]?.type }.distinct()
            if (types.size > 1) throw DisplayBuildException("$p in ${component.ifEmpty { "the screen" }} is ${types.joinToString(" and ")} in different themes")
            val files = entries.filter { it.decl.name == p && it.scope.component == component }.map { it.decl.file }.distinct()
            TokenClass.Field(fieldName(p), p, types.single(), instances.values.any { it[p] == null }, files)
        }
        return TokenClass(name, component, fields, instances)
    }

    private fun describe(ctx: Context) =
        "${ctx.chain.joinToString(" ") { it.joinToString(".", ".") }.ifEmpty { "the screen" }} (${if (ctx.light) "light" else "dark"}${if (ctx.glassy) ", glassy" else ""})"

    companion object {
        val INSTANCES = listOf(Triple("DARK", false, false), Triple("LIGHT", true, false), Triple("GLASSY_DARK", false, true), Triple("GLASSY_LIGHT", true, true))

        /**
         * Selectors that are not a plain chain of classes, each standing for the elements it names; a new one fails the
         * build until it is named here, so nothing is guessed.
         */
        val ALIASES = mapOf(
            // the apps with their own coloured backdrop and the call, dark by day too (tokens.css)
            ":is(.music-app:not(.settings-app, .phone-app, .maps-app), .call)" to ".immersive",
            // the status bar over an open music or radio app (music.css)
            ".screen:has(.music-app:not(.settings-app, .phone-app, .maps-app).is-open) :is(.bar, .bar-text, #bar-theme)" to ".screen .music-bar",
        )

        fun scopeOf(file: String, selector: String): Scope {
            var s = selector
            for ((from, to) in ALIASES) s = s.replace(from, to)
            val light = Regex("""\.screen\.light|\[data-theme="light"]|\.light(?![\w-])""").containsMatchIn(s)
            val glassy = s.contains("""[data-design="glassy"]""")
            s = s.replace(":root", " ").replace("""[data-design="glassy"]""", " ").replace("""[data-theme="light"]""", " ")
                .replace(Regex("""\.light(?![\w-])"""), " ").replace(Regex("""\.screen(?![\w-])"""), " ")
            s = Css.normalize(s)
            if (s.isNotEmpty() && !Regex("""^(\.[a-z0-9-]+)+( (\.[a-z0-9-]+)+)*$""").matches(s))
                throw DisplayBuildException("$file: selector `$selector` is not a chain of classes; name it in Tokens.ALIASES")
            val chain = if (s.isEmpty()) emptyList() else s.split(' ').map { c -> c.split('.').filter { it.isNotEmpty() }.toSet() }
            return Scope(chain, light, glassy)
        }

        /** A rule's chain matches an element's when its last part is on the element and the others on its ancestors, in order. */
        fun descendantMatch(rule: List<Set<String>>, element: List<Set<String>>): Boolean {
            if (rule.isEmpty()) return true
            if (element.isEmpty() || !element.last().containsAll(rule.last())) return false
            var j = element.size - 2
            for (i in rule.size - 2 downTo 0) {
                while (j >= 0 && !element[j].containsAll(rule[i])) j--
                if (j < 0) return false
                j--
            }
            return true
        }

        fun className(component: String) = component.split(' ', '.', '-').filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercase) }

        fun fieldName(property: String): String {
            val parts = property.removePrefix("--").split('-').filter { it.isNotEmpty() }
            return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        }
    }
}
