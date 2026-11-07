package com.tmplayer.data

/**
 * One recent post of a channel, as Home reads it: enough to count what is new and to find the
 * stream links in it, nothing more. The text is the message's own text or its caption's.
 */
data class ChannelPost(
    val messageId: Long,
    val text: String,
    /** True when the post carries a video (a video message, or a document that is one). */
    val isVideo: Boolean,
    /** The post's own photo, where it is a photo post: the largest size's file id, for the Live row's banner (owner, v1.0.14). 0 for any other post. */
    val photoFileId: Int = 0,
    /** The photo's inline preview bytes, where TDLib gave one. */
    val photoMiniThumbnail: ByteArray? = null,
)

/**
 * What is new in a channel since Home last looked (v1.0.10): pure counting over a bounded
 * slice of recent posts, newest first, against the newest message id Home remembered.
 */
object ChannelNews {

    /** How many of [posts] are videos posted after [seenMessageId]. */
    fun newVideos(posts: List<ChannelPost>, seenMessageId: Long): Int =
        posts.count { it.isVideo && it.messageId > seenMessageId }

    /**
     * The id to remember as seen once [posts] have been counted: the newest post's id, and
     * never backwards (a shorter slice on a later visit must not unsee what was seen).
     */
    fun seenAfter(posts: List<ChannelPost>, seenMessageId: Long): Long {
        var seen = seenMessageId
        for (post in posts) if (post.messageId > seen) seen = post.messageId
        return seen
    }
}
