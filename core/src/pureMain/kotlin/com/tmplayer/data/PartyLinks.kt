package com.tmplayer.data

/**
 * A Watch party link: the address of one video in one chat, and the place to start it at.
 *
 * The link carries only what names the video on Telegram itself: the chat's id and the post's
 * id, which are the same on every account, and a start time. Whoever opens it watches with
 * their own account and their own sign in: the link is a reference, never a copy of the video
 * and never anybody's session. An account that is not in the chat is refused when the link is
 * opened, by the same rules as a post link ([LinkRules]).
 *
 * The shape is this app's own (`jplayer://party`), not a t.me address: a private chat has no
 * t.me address to give, and the ids are exactly what opening the video here takes.
 */
data class PartyLink(
    val chatId: Long,
    val messageId: Long,
    /** Where to start, in seconds; 0 is the beginning. */
    val startSec: Int = 0,
)

/** Builds and reads Watch party links. Reading is garbage safe: anything else is null. */
object PartyLinks {

    private const val PREFIX = "jplayer://party"

    /** Signed, as chat ids are: a channel's is negative, a person's positive. */
    private val NUMBER = Regex("^-?[0-9]{1,19}$")

    /** A start time past a week names nothing real; it is read as no start time. */
    private const val MAX_START_SEC = 7 * 24 * 3600

    /** The link for [chatId]'s post [messageId], starting [startSec] in (left out at 0). */
    fun build(chatId: Long, messageId: Long, startSec: Int = 0): String = buildString {
        append(PREFIX)
        append("?c=").append(chatId)
        append("&m=").append(messageId)
        if (startSec > 0) append("&t=").append(startSec)
    }

    /** [text] as a party link, when the whole of it is one; null otherwise. */
    fun parse(text: String): PartyLink? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        // The address ends at the query: "jplayer://partyfoo?..." is another address entirely.
        if (t.length > PREFIX.length && t[PREFIX.length] != '?') return null
        val query = queryOf(t.substring(PREFIX.length))
        val chatId = query["c"]?.takeIf { NUMBER.matches(it) }?.toLongOrNull() ?: return null
        val messageId = query["m"]?.takeIf { NUMBER.matches(it) }?.toLongOrNull() ?: return null
        if (chatId == 0L || messageId <= 0L) return null
        val start = query["t"]?.let { TelegramLinks.startSeconds(it) } ?: 0
        return PartyLink(chatId, messageId, if (start in 1..MAX_START_SEC) start else 0)
    }

    /** The query of a link, read the way [TelegramLinks] reads one: lower cased keys, first wins. */
    private fun queryOf(text: String): Map<String, String> {
        val q = text.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            if (key.isEmpty()) null else key to value
        }.toMap()
    }
}
