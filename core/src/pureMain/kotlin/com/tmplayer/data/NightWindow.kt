package com.tmplayer.data

/**
 * The night window for downloads (item 7c, v1.0.15): the hours a viewer who turned night
 * downloads on wants their downloads to run in, so a big video asked for in the evening lands
 * overnight instead of against the day's data and battery.
 *
 * The window is two minutes of day, start and end, and it may run past midnight (23:00 to
 * 06:00 is the default). It governs only downloads the viewer starts: it never starts one by
 * itself, and it is not episode auto-download. Outside the window a running or queued download
 * is held, the way Wi-Fi only holds one, and it resumes by itself when the window opens.
 */
object NightWindow {

    /** Minutes in a day, the unit both ends of the window are stored in. */
    const val DAY_MINUTES = 24 * 60

    /** 23:00, the default opening of the window. */
    const val DEFAULT_START = 23 * 60

    /** 06:00, the default close of the window. */
    const val DEFAULT_END = 6 * 60

    /** A stored minute of day brought into the day: outside data can hold anything. */
    fun clean(minutes: Int): Int = ((minutes % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES

    /** An hour and minute as a minute of day, for the settings pickers. */
    fun of(hour: Int, minute: Int): Int = clean(hour * 60 + minute)

    /**
     * Whether [minuteOfDay] falls in the window from [start] to [end]. A window whose ends
     * meet is no window at all and holds nothing, rather than holding everything all day.
     */
    fun inside(minuteOfDay: Int, start: Int, end: Int): Boolean {
        val now = clean(minuteOfDay)
        val from = clean(start)
        val to = clean(end)
        if (from == to) return false
        return if (from < to) now in from until to else now >= from || now < to
    }

    /** Whether a download may run at [minuteOfDay] under a window that may be off entirely. */
    fun allowed(enabled: Boolean, minuteOfDay: Int, start: Int, end: Int): Boolean =
        !enabled || inside(minuteOfDay, start, end)
}
