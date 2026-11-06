package com.tmplayer.data

/**
 * One Telegram account this device can be signed in to, as the account list keeps it.
 *
 * An account is a [slot]: a number that names its own TDLib database and files directories
 * (slot 0 is the first account's, the directories the app has always used). The name and
 * username are only what the list shows; the sign in itself lives in the slot's database,
 * never here. [userId] is Telegram's own id for the account, 0 while it is not known.
 */
data class AccountEntry(
    val slot: Int,
    val userId: Long,
    val name: String,
    val username: String,
)

/**
 * The account list as text, and the rules over it. Kept apart from where it is stored so it
 * is tested alone, and read garbage safely: a damaged list loses its bad lines, never the app.
 *
 * Slots are never reused. An account that is signed out leaves its slot behind, so a later
 * account can never inherit the earlier one's directories and find its data there.
 */
object AccountBook {

    /** How many accounts the list holds at most: past this, adding another is refused. */
    const val MAX_ACCOUNTS = 5

    /** The slot of the first account, whose directories are the ones the app has always used. */
    const val FIRST_SLOT = 0

    /** [entries] as text: one line per account, its fields escaped and tab separated. */
    fun encode(entries: List<AccountEntry>): String =
        entries.sortedBy { it.slot }.joinToString("\n") { entry ->
            listOf(
                entry.slot.toString(),
                entry.userId.toString(),
                escape(entry.name),
                escape(entry.username),
            ).joinToString("\t")
        }

    /** The accounts in [text]; a line that does not read is skipped, and no slot appears twice. */
    fun decode(text: String?): List<AccountEntry> {
        if (text.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<Int, AccountEntry>()
        for (line in text.split('\n')) {
            if (out.size >= MAX_ACCOUNTS) break
            val fields = split(line)
            val slot = fields.getOrNull(0)?.toIntOrNull() ?: continue
            if (slot < 0 || slot > 999 || out.containsKey(slot)) continue
            val userId = fields.getOrNull(1)?.toLongOrNull() ?: continue
            out[slot] = AccountEntry(
                slot = slot,
                userId = userId,
                name = fields.getOrNull(2).orEmpty(),
                username = fields.getOrNull(3).orEmpty(),
            )
        }
        return out.values.sortedBy { it.slot }
    }

    /** The slot a new account takes: one past the highest in use, and never slot 0 again. */
    fun nextSlot(entries: List<AccountEntry>): Int =
        maxOf(entries.maxOfOrNull { it.slot } ?: FIRST_SLOT, FIRST_SLOT) + 1

    /** [entries] with [entry] in it: the same slot's old line is replaced, the rest keep order. */
    fun withIdentity(entries: List<AccountEntry>, entry: AccountEntry): List<AccountEntry> =
        (entries.filter { it.slot != entry.slot } + entry).sortedBy { it.slot }.take(MAX_ACCOUNTS)

    /** [entries] without [slot]: what is left when that account is signed out for good. */
    fun without(entries: List<AccountEntry>, slot: Int): List<AccountEntry> =
        entries.filter { it.slot != slot }

    /** A field made single line: the separators themselves are written as two characters. */
    private fun escape(field: String): String = buildString {
        for (c in field) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    /** One line back into its fields, honouring [escape]; a trailing escape is kept as it is. */
    private fun split(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> {
                    field.append(
                        when (line[i + 1]) {
                            't' -> '\t'
                            'n' -> '\n'
                            'r' -> '\r'
                            '\\' -> '\\'
                            else -> line[i + 1]
                        },
                    )
                    i += 2
                }
                c == '\t' -> {
                    fields.add(field.toString())
                    field.clear()
                    i++
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        fields.add(field.toString())
        return fields
    }
}
