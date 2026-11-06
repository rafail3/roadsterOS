package ro.rdstr.gradle.display

/** A custom property declared in a style sheet: `selector { --name: value }`, one selector of a list each. */
data class Declaration(val file: String, val selector: String, val name: String, val value: String, val order: Int)

/** A failure in the emulator's styles that the Android side cannot follow; the build stops on it. */
class DisplayBuildException(message: String) : RuntimeException(message)

/** The small part of CSS syntax the emulator's sheets use: rules, at-rules, selector lists, declarations. */
object Css {
    private val ignoredAtRules = listOf("@font-face", "@property", "@keyframes", "@-webkit-keyframes", "@import", "@charset")

    fun stripComments(text: String): String = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, " ")

    /** The custom properties of [text], in source order; [orderBase] keeps the order across files. */
    fun declarations(file: String, text: String, orderBase: Int = 0): List<Declaration> {
        val out = mutableListOf<Declaration>()
        var order = orderBase
        fun walk(body: String, media: String?) {
            for ((prelude, block) in blocks(body)) {
                when {
                    prelude.startsWith("@media") || prelude.startsWith("@supports") || prelude.startsWith("@container") ->
                        walk(block, prelude)
                    prelude.startsWith("@") -> if (ignoredAtRules.none { prelude.startsWith(it) })
                        throw DisplayBuildException("$file: at-rule not handled: ${prelude.take(60)}")
                    else -> {
                        val props = properties(block).filter { it.first.startsWith("--") }
                        if (props.isEmpty()) continue
                        if (media != null) throw DisplayBuildException("$file: custom properties inside `$media` ($prelude) are not handled")
                        for (selector in splitTop(prelude, ',')) for ((name, value) in props)
                            out += Declaration(file, normalize(selector), name, normalize(value), order++)
                    }
                }
            }
        }
        walk(stripComments(text), null)
        return out
    }

    /** `@font-face` blocks: family and source of each face. */
    fun fontFaces(text: String): List<Map<String, String>> =
        blocks(stripComments(text)).filter { it.first.startsWith("@font-face") }.map { (_, body) -> properties(body).toMap() }

    /** The top-level `prelude { block }` pairs of [text]. */
    fun blocks(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        var start = 0
        while (i < text.length) {
            when (text[i]) {
                '"', '\'' -> i = skipString(text, i)
                ';' -> start = i + 1 // a statement at-rule such as @import
                '{' -> {
                    val prelude = normalize(text.substring(start, i))
                    var depth = 1
                    var j = i + 1
                    while (j < text.length && depth > 0) {
                        when (text[j]) {
                            '"', '\'' -> { j = skipString(text, j); continue }
                            '{' -> depth++
                            '}' -> depth--
                        }
                        j++
                    }
                    if (depth != 0) throw DisplayBuildException("unbalanced braces after `${prelude.take(60)}`")
                    out += prelude to text.substring(i + 1, j - 1)
                    i = j
                    start = j
                    continue
                }
            }
            i++
        }
        return out
    }

    /** `name: value` pairs of a declaration block, `!important` dropped. */
    fun properties(block: String): List<Pair<String, String>> = splitTop(block, ';').mapNotNull { part ->
        val colon = part.indexOf(':')
        if (colon <= 0) null
        else part.substring(0, colon).trim() to part.substring(colon + 1).replace("!important", "").trim()
    }

    /** [text] split on [sep] outside parentheses, brackets and strings; empty parts dropped. */
    fun splitTop(text: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var i = 0
        var start = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> { i = skipString(text, i); continue }
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                c == sep && depth == 0 -> { out += text.substring(start, i); start = i + 1 }
            }
            i++
        }
        out += text.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** [text] split on whitespace outside parentheses. */
    fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in text) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c.isWhitespace() && depth == 0 -> { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun normalize(text: String): String = text.trim().replace(Regex("""\s+"""), " ")

    private fun skipString(text: String, at: Int): Int {
        val quote = text[at]
        var i = at + 1
        while (i < text.length && text[i] != quote) i += if (text[i] == '\\') 2 else 1
        return i + 1
    }

    /**
     * Selector specificity as one number (ids, classes, types weighted 10000/100/1); `:is()`, `:not()` and `:has()` count
     * their most specific argument, `:where()` counts nothing, as in the browser.
     */
    fun specificity(selector: String): Int {
        var score = 0
        var i = 0
        val s = selector
        while (i < s.length) {
            val c = s[i]
            when {
                c == '#' -> { score += 10000; i = skipName(s, i + 1) }
                c == '.' -> { score += 100; i = skipName(s, i + 1) }
                c == '[' -> { score += 100; i = s.indexOf(']', i) + 1 }
                c == ':' && s.startsWith("::", i) -> { score += 1; i = skipName(s, i + 2) }
                c == ':' -> {
                    val end = skipName(s, i + 1)
                    val name = s.substring(i + 1, end)
                    if (end < s.length && s[end] == '(') {
                        val close = matching(s, end)
                        val args = s.substring(end + 1, close)
                        score += when (name) {
                            "is", "not", "has", "matches" -> splitTop(args, ',').maxOf { specificity(it) }
                            "where" -> 0
                            else -> 100
                        }
                        i = close + 1
                    } else { score += 100; i = end }
                }
                c.isLetter() || c == '*' -> { if (c != '*') score += 1; i = skipName(s, i + 1) }
                else -> i++
            }
        }
        return score
    }

    private fun skipName(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
        return i
    }

    fun matching(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            if (s[i] == '(') depth++
            if (s[i] == ')') { depth--; if (depth == 0) return i }
        }
        throw DisplayBuildException("unbalanced parentheses in `$s`")
    }
}
