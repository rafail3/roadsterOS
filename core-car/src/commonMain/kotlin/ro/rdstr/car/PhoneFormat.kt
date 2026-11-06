package ro.rdstr.car

/**
 * Phone numbers grouped as each country writes them, from libphonenumber's formats ([PhoneFormats]), also while they
 * are typed: "+40721308375" reads "+40 721 308 375", "0721308375" stays national as "0721 308 375". A number written
 * without "+" follows the [format] call's home region (the Number Country setting); only the display changes, the
 * digits that go to the phone stay as typed. Codes (*, #), short numbers and unknown input are left as they are.
 */
object PhoneFormat {
    private class Layout(
        val pattern: Regex, val format: String, val intl: String, val rule: String, val leading: List<Regex>, val groups: List<Int>,
    )

    private class Region(val id: String, val code: Int, val nationalPrefix: String, val intlPrefix: Regex?, val main: Boolean, val layouts: List<Layout>)

    private const val SHORT = 4
    private const val FALLBACK_REGION = "RO"

    private val all: List<Region> by lazy { PhoneFormats.REGIONS.map(::parse) }
    private val byId by lazy { all.associateBy { it.id } }
    private val byCode by lazy { all.groupBy { it.code }.mapValues { (_, rs) -> rs.firstOrNull { it.main } ?: rs.first() } }

    /** The regions there are formats for, by ISO code. */
    fun regions(): List<String> = all.map { it.id }.sorted()

    fun callingCode(region: String): Int? = byId[region]?.code

    fun format(typed: String, home: String): String {
        val text = typed.trim()
        if (text.isEmpty() || text.any { it == '*' || it == '#' || it == ',' || it == ';' || it.isLetter() }) return text
        val digits = text.filter { it in '0'..'9' }
        val region = byId[home] ?: byId.getValue(FALLBACK_REGION)
        val plus = text.startsWith("+")
        val viaPrefix = if (plus) null else region.intlPrefix?.find(digits)?.takeIf { it.range.first == 0 && digits.length > it.value.length }
        if (plus || viaPrefix != null) return international(digits.drop(viaPrefix?.value?.length ?: 0))
        if (digits.length <= SHORT) return digits
        val np = region.nationalPrefix
        return if (np.isNotEmpty() && digits.startsWith(np)) national(region, digits.drop(np.length), np) else national(region, digits, "")
    }

    private fun international(digits: String): String {
        val code = (1..minOf(3, digits.length)).firstNotNullOfOrNull { n -> digits.take(n).toInt().takeIf { it in byCode } }
            ?: return "+$digits"
        val rest = digits.drop(code.toString().length)
        if (rest.isEmpty()) return "+$code"
        val region = byCode.getValue(code)
        return "+$code " + (group(region.layouts, rest, intl = true, prefix = null) ?: rest)
    }

    private fun national(region: Region, nsn: String, typedPrefix: String): String {
        if (nsn.isEmpty()) return typedPrefix
        val layouts = region.layouts.ifEmpty { byCode[region.code]?.layouts.orEmpty() }
        return group(layouts, nsn, intl = false, prefix = typedPrefix.ifEmpty { null }) ?: (typedPrefix + nsn)
    }

    /**
     * The number laid out by the first format that fits it whole, or, while it is still being typed, by its groups.
     * A layout that would add digits that were not typed (Argentina's mobile "15") is passed over for the next one.
     */
    private fun group(layouts: List<Layout>, nsn: String, intl: Boolean, prefix: String?): String? {
        fun layoutOf(l: Layout) = if (intl && l.intl.isNotEmpty()) l.intl else l.format
        val typed = (prefix ?: "") + nsn
        fun keeps(text: String) = text.filter { it in '0'..'9' } == typed
        for (l in layouts) {
            if (intl && l.intl == "NA") continue
            if (!(l.leading.isEmpty() || l.leading.last().find(nsn)?.range?.first == 0) || !l.pattern.matches(nsn)) continue
            val text = withPrefix(l.pattern.replace(nsn, layoutOf(l)), l, prefix, firstGroup = l.pattern.find(nsn)!!.groupValues[1])
            if (keeps(text)) return text
        }
        for (l in layouts) {
            if ((intl && l.intl == "NA") || !startsLike(l, nsn)) continue
            // the groups of the format filled in order with what is typed so far; the layout cut after the last filled one
            val parts = ArrayList<String>()
            var at = 0
            for (size in l.groups) {
                if (at >= nsn.length) break
                parts += nsn.substring(at, minOf(nsn.length, at + size))
                at += size
            }
            if (parts.isEmpty()) continue
            var text = layoutOf(l)
            val cut = Regex("\\$(\\d)").findAll(text).firstOrNull { it.groupValues[1].toInt() > parts.size }?.range?.first
            if (cut != null) text = text.substring(0, cut).trimEnd(' ', '-', '/', '.', '(')
            parts.forEachIndexed { i, p -> text = text.replace("$${i + 1}", p) }
            if (at < nsn.length) text += nsn.substring(at)
            text = withPrefix(text, l, prefix, firstGroup = parts.first())
            if (keeps(text)) return text
        }
        return null
    }

    /** A nationally typed number shows its prefix as the country writes it ("0721", "(0)30", "8 (495)"). */
    private fun withPrefix(text: String, layout: Layout, prefix: String?, firstGroup: String): String {
        if (prefix == null) return text
        if (layout.rule.isEmpty()) return "$prefix $text"
        val joined = layout.rule.replace("\$NP", prefix).replace("\$FG", firstGroup)
        return if (text.startsWith(firstGroup)) joined + text.substring(firstGroup.length) else "$prefix $text"
    }

    /** Whether a number being typed can still become one of this format's: its leading digits allow it. */
    private fun startsLike(l: Layout, nsn: String): Boolean {
        if (l.leading.isEmpty()) return true
        val level = l.leading[minOf(l.leading.size - 1, maxOf(0, nsn.length - 3))]
        if (level.find(nsn)?.range?.first == 0) return true
        // too few digits for the pattern to decide: any continuation that it accepts will do
        return ('0'..'9').any { d -> level.find(nsn + d.toString().repeat(8))?.range?.first == 0 }
    }

    private fun parse(block: String): Region {
        val lines = block.split('\n').map { it.replace('@', '$') }
        val head = lines.first().split('\t')
        val layouts = lines.drop(1).map { line ->
            val (pattern, format, intl, rule, leading) = line.split('\t').let { it + List(5 - it.size) { "" } }
            Layout(
                Regex(pattern), format, intl, rule,
                if (leading.isEmpty()) emptyList() else leading.split('^').map { Regex("^(?:$it)") },
                groupSizes(pattern),
            )
        }
        return Region(head[0], head[1].toInt(), head[2], head[3].takeIf { it.isNotEmpty() }?.let { Regex("^(?:$it)") }, head[4] == "1", layouts)
    }

    /** The largest number of digits each capturing group of a pattern takes: "(\d{2,3})(\d{4})" gives 3 and 4. */
    private fun groupSizes(pattern: String): List<Int> {
        val sizes = ArrayList<Int>()
        var i = 0
        var depth = 0
        var count = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '(' -> {
                    if (depth == 0 && !pattern.startsWith("(?", i)) count = 0
                    depth++; i++
                }
                c == ')' -> {
                    depth--; i++
                    if (depth == 0) sizes += count
                }
                c == '\\' -> { i += 2; count += repeat(pattern, i).also { i = it.second }.first }
                c == '[' -> { i = pattern.indexOf(']', i) + 1; count += repeat(pattern, i).also { i = it.second }.first }
                c.isDigit() -> { i++; count += repeat(pattern, i).also { i = it.second }.first }
                else -> i++
            }
        }
        return sizes
    }

    /** The repeat after a token at [i]: {n} or {n,m} gives n or m digits, nothing gives one. */
    private fun repeat(pattern: String, i: Int): Pair<Int, Int> {
        if (i >= pattern.length || pattern[i] != '{') return 1 to i
        val end = pattern.indexOf('}', i)
        val n = pattern.substring(i + 1, end).split(',').last().toInt()
        return n to end + 1
    }
}
