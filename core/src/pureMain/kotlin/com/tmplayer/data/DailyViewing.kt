package com.tmplayer.data

/**
 * One profile's viewing time for one day (item 16, v1.0.16): the seconds actually spent
 * playing, kept per profile under that profile's key prefix in [SettingsStore], as one
 * small text value, `"<day>=<seconds>"`. Only the current day is kept: a stored value
 * whose day is not the day being asked about reads as nothing watched, and the next add
 * replaces it, so yesterday's watching can never hold today up and no history piles up.
 * The day itself is the reader's local calendar day, named by its owner (the player),
 * which keeps this object free of clocks and zones and testable as plain text.
 */
object DailyViewing {

    /** The seconds watched on [day] in the stored value [raw]; any other day reads 0. */
    fun secondsOn(raw: String?, day: String): Long {
        val stored = parse(raw) ?: return 0L
        return if (stored.first == day) stored.second else 0L
    }

    /** The stored value after [seconds] more of playing on [day]: added to the day's own total. */
    fun added(raw: String?, day: String, seconds: Long): String {
        if (seconds <= 0L) return raw ?: ""
        val total = secondsOn(raw, day) + seconds
        return "$day=$total"
    }

    /** Whether [secondsToday] of watching has reached a [limitMinutes] daily limit. */
    fun reached(secondsToday: Long, limitMinutes: Int): Boolean =
        limitMinutes > 0 && secondsToday >= limitMinutes * 60L

    private fun parse(raw: String?): Pair<String, Long>? {
        if (raw.isNullOrBlank()) return null
        val cut = raw.indexOf('=')
        if (cut <= 0) return null
        val seconds = raw.substring(cut + 1).toLongOrNull() ?: return null
        if (seconds < 0L) return null
        return raw.substring(0, cut) to seconds
    }
}
