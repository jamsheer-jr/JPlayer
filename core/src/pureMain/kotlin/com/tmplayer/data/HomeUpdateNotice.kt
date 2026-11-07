package com.tmplayer.data

/**
 * The update notice on Home itself (owner, v1.0.15, item 13): when the update feed
 * reports a newer version, Home shows its card with the version, its What's New
 * lines and an Update button, until the viewer updates or puts the card away for
 * the day. The popup and the rail item keep their own rules ([UpdateScheduler]);
 * this is only the card's memory: which version was put away, and until when.
 *
 * The memory is one stored line, the version, a bar, and the epoch millisecond the
 * putting away ends: "1.0.15|1735689600000". It is written by Settings and read back
 * from a backup or an older run, so it is outside data and read the careful way:
 * a line that does not parse says nothing was put away, and the card shows.
 */
object HomeUpdateNotice {

    /** How long a putting away lasts: the rest of the day, by the clock. */
    const val DAY_MS = 24 * 60 * 60_000L

    /** The stored line for [version] put away at [nowMs]: shows again after a day. */
    fun stamp(version: String, nowMs: Long): String = "$version|${nowMs + DAY_MS}"

    /**
     * Whether the card for [version] stands at [nowMs]: it does unless the stored
     * line names this very version and its day has not run out. A newer version than
     * the one put away stands at once, as a skipped version's successor does in the
     * popup's rules.
     */
    fun visible(stored: String, version: String, nowMs: Long): Boolean {
        if (version.isBlank()) return false
        val bar = stored.indexOf('|')
        if (bar <= 0) return true
        val putAway = stored.substring(0, bar)
        val until = stored.substring(bar + 1).toLongOrNull() ?: return true
        return putAway != version || nowMs >= until
    }
}
