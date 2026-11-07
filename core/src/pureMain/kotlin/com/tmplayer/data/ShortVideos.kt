package com.tmplayer.data

/**
 * The under 5 minute rule (owner, v1.0.15): a video whose stated length is under
 * [LIMIT_SEC] is a sample sized clip, and the Playback switch "Hide videos under
 * 5 minutes" leaves it out of the same lists the cam switch screens (chat lists,
 * Home shelves, search, copies and versions), each place counting what it hid.
 *
 * A length of 0 is Telegram stating none, and a negative one is nonsense from
 * outside data: neither is "under 5 minutes", so an unstated length is never
 * hidden by this rule. Exactly [LIMIT_SEC] is not under it either.
 */
object ShortVideos {

    /** The stated length, in seconds, under which a video counts as short. */
    const val LIMIT_SEC = 5 * 60

    /** Whether a stated length in seconds is a short video's: see [ShortVideos]. */
    fun isShort(durationSec: Int): Boolean = durationSec in 1 until LIMIT_SEC

    /** Whether [item]'s stated length makes it a short video: see [ShortVideos]. */
    fun isShort(item: MediaItem): Boolean = isShort(item.durationSec)

    /** [items] without their short videos, for the surfaces that fold copies before listing. */
    fun kept(items: List<MediaItem>): List<MediaItem> = items.filterNot(::isShort)
}
