package ro.rdstr.ui.phone

import android.annotation.SuppressLint
import android.content.Context
import ro.rdstr.car.Contact
import ro.rdstr.car.PhoneBook
import ro.rdstr.ui.tokens.Shadow

/**
 * PhoneApp.tsx `Contacts`: the phone book by letter with an index down the edge that jumps to a letter; a contact with
 * one number calls it, one with more opens them. With [search], a field stays at the top of the list and the typed
 * text narrows it to matches. [onDialed] runs after a call is placed (the call screen closes its picker).
 */
@SuppressLint("ViewConstructor")
class ContactsList(
    context: Context,
    private val contacts: List<Contact>,
    device: String,
    private val format: (String) -> String,
    private val immersive: Boolean,
    private val search: Search?,
    private val onDialed: (() -> Unit)?,
    private val dial: (String) -> Unit,
) : Box(context, "phone-contacts") {
    /** ContactSearch: the text typed, whether the keyboard is up, and the field's two taps. */
    class Search(val query: String, val typing: Boolean, val onFocus: () -> Unit, val onClear: () -> Unit)

    private var open: String? = null
    private val list = Box(context, "phone-list")
    private val heads = HashMap<String, Box>()
    private val rows = HashMap<String, Box>()

    init {
        val sections = PhoneBook.sections(contacts)
        val query = search?.query?.trim().orEmpty()
        val found = if (query.isNotEmpty()) PhoneBook.search(contacts, query) else null
        if (sections.isEmpty()) {
            dir = Dir.STACK; justify = Align.START; align = Align.START
            add(phoneEmpty(context, "Contacts from $device show here once the phone shares them.", immersive))
        } else {
            dir = Dir.GRID
            tracks = if (found != null) floatArrayOf(-1f) else floatArrayOf(-1f, 56f)
            add(list)
            list.dir = Dir.COLUMN; list.padB = 24f; list.scrolls = true
            list.contentDescription = if (found != null) "Matching contacts" else "Contacts"
            list.onTheme = { thumbColor = Ink(immersive).on(0.24f) }
            search?.let { list.add(searchBar(it)) { sticky = true } }
            if (found != null) {
                if (found.isEmpty()) list.add(phoneEmpty(context, "No contacts match “$query”.", immersive))
                else list.add(section(null, found))
            } else sections.forEachIndexed { i, s -> list.add(section(s.letter, s.contacts, i > 0)) }
            if (found == null) add(index(sections.map { it.letter }))
        }
    }

    private fun onTheme(t: Txt, block: Txt.(Ink) -> Unit) = ro.rdstr.ui.kit.UiTheme.link(t) { t.block(Ink(immersive)) }

    private fun searchBar(s: Search) = Box(context, "phone-search").apply {
        dir = Dir.ROW; align = Align.CENTER; gap = 12f; padB = 12f
        contentDescription = "Search"
        val field = add(Box(context, "phone-search-field")) { grow = 1f; shrink = true; h = 52f }.apply {
            dir = Dir.ROW; align = Align.CENTER; gap = 10f; padL = 16f; padR = 16f; css.radius = 14f
            contentDescription = if (s.query.isNotEmpty()) "Search, ${s.query}" else "Search contacts"
            tappable { s.onFocus() }
        }
        val glass = field.add(icon(context, "i-magnifying-glass", 22f)) { w = 22f; h = 22f }
        val text = field.add(txt(context, 18f, 400, if (s.query.isNotEmpty()) "phone-search-text" else "phone-search-placeholder", s.query.ifEmpty { "Search" })) { shrink = true }
        if (s.typing) field.add(Caret(context)) { w = 2f; h = 24f; ml = -8f }
        val cancel = if (s.query.isNotEmpty() || s.typing) add(Box(context, "phone-search-cancel")) { minH = 52f }.apply {
            dir = Dir.STACK; align = Align.CENTER; padL = 6f; padR = 6f
            contentDescription = "Cancel"
            tappable { s.onClear() }
        } else null
        val cancelTxt = cancel?.add(txt(context, 18f, 600, null, "Cancel"))
        onTheme = {
            val ink = Ink(immersive)
            ink.solid(this, ink.ground)
            if (ink.glassy) ink.glass(field, ink.ground) else ink.solid(field, Ink.FIELD)
            if (s.typing) field.css.shadows = field.css.shadows + Shadow(true, 0f, 0f, 0f, 2f, Ink.BLUE)
            glass.color = ink.ink2
            text.color = if (s.query.isNotEmpty()) ink.ink else ink.ink2
            cancelTxt?.color = Ink.BLUE
        }
    }

    private fun section(letter: String?, people: List<Contact>, divided: Boolean = false) = Box(context, "section").apply {
        dir = Dir.COLUMN
        contentDescription = letter ?: "Matches"
        if (divided) onTheme = { topLine = Ink(immersive).on(0.07f) }
        if (letter != null) {
            val head = add(Box(context, "phone-letter")) { sticky = true; stickTop = if (search != null) 64f else 0f }.apply {
                dir = Dir.STACK; justify = Align.START; padL = 16f; padR = 16f; padT = 10f; padB = 6f
            }
            val t = head.add(txt(context, 15f, 700, null, letter))
            head.onTheme = { val ink = Ink(immersive); ink.solid(this, ink.ground); t.color = ink.ink2 }
            heads[letter] = this
        }
        for (c in people) add(contact(c).also { rows[c.name] = it })
    }

    private fun contact(c: Contact): Box = Box(context, "phone-contact").apply {
        dir = Dir.COLUMN
        val isOpen = open == c.name
        val row = add(Box(context, "phone-row")) { minH = 76f }.apply {
            dir = Dir.ROW; align = Align.CENTER; gap = 16f; padL = 16f; padR = 16f; css.radius = 16f
            contentDescription = if (c.numbers.size == 1) "Call ${c.name}" else "${c.name}, ${c.numbers.size} numbers"
            tappable { pick(c) }
        }
        row.add(Avatar(context, "phone-avatar", 48f, 18f, 26f, 0.02f, immersive).apply { set(PhoneBook.initials(c.name), false) })
        val text = row.add(Box(context, "phone-row-text")) { grow = 1f; shrink = true }.apply { dir = Dir.COLUMN; gap = 2f }
        val name = text.add(txt(context, 20f, 600, "phone-row-name", c.name)) { shrink = true }
        val count = if (c.numbers.size > 1) row.add(txt(context, 16f, 400, "phone-row-when", "${c.numbers.size} numbers")) else null
        val numbers = if (isOpen) c.numbers.map { n ->
            add(Box(context, "phone-number")) { minH = 64f }.apply {
                dir = Dir.ROW; align = Align.CENTER; gap = 14f; padL = 80f; padR = 16f; css.radius = 16f
                contentDescription = "Call ${c.name}, ${n.label.ifEmpty { n.number }}"
                tappable { call(n.number) }
                add(icon(context, "i-phone-fill", 22f)) { w = 22f; h = 22f }
                add(txt(context, 15f, 400, "phone-row-sub", n.label)) { minW = 64f }
                add(txt(context, 18f, 400, "phone-number-digits", format(n.number)))
            }
        } else emptyList()
        onTheme = {
            val ink = Ink(immersive)
            name.color = ink.ink; count?.color = ink.ink2
            row.fillWith(if (isOpen) ink.on(0.06f) else 0, ink.on(0.08f))
            for (b in numbers) {
                b.fillWith(0, ink.on(0.08f))
                (b.getChildAt(0) as ro.rdstr.ui.kit.IconView).color = ink.green
                (b.getChildAt(1) as Txt).color = ink.ink2
                (b.getChildAt(2) as Txt).color = ink.ink
            }
        }
    }

    private fun call(number: String) { dial(number); onDialed?.invoke() }

    private fun pick(c: Contact) {
        if (c.numbers.size == 1) { call(c.numbers[0].number); return }
        val was = open
        open = if (open == c.name) null else c.name
        // the contact opened and the one closed again, with or without their numbers
        for (n in listOfNotNull(was, open).distinct()) {
            val old = rows[n] ?: continue
            val sec = old.parent as Box
            val i = sec.indexOfChild(old)
            sec.removeViewAt(i)
            sec.addView(contact(contacts.first { it.name == n }).also { rows[n] = it }, i, Lp())
        }
    }

    private fun index(letters: List<String>) = Box(context, "phone-index").apply {
        dir = Dir.COLUMN; justify = Align.CENTER; padT = 8f; padB = 8f
        contentDescription = "Jump to letter"
        val labels = letters.map { l ->
            val b = add(Box(context, "button")) { grow = 1f; maxH = 44f; minH = 28f }.apply {
                dir = Dir.STACK; align = Align.CENTER; justify = Align.CENTER
                contentDescription = l
                tappable { heads[l]?.let { list.scrollToChild(it) } }
            }
            b.add(txt(context, 14f, 700, null, l))
        }
        onTheme = { for (t in labels) t.color = Ink.BLUE }
    }
}
