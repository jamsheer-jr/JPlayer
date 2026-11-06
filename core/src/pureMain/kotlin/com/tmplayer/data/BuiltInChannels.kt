package com.tmplayer.data

import kotlin.concurrent.Volatile

/**
 * A channel the app itself carries (v1.0.10): its invite link is built in, and Home reads it
 * for the Live streams row and the Share J Player text (the Home cards the channels launched
 * with were removed in v1.0.11; the channels themselves stayed). Nothing about the channel is
 * shared between accounts: the invite link is there for anyone holding the app, and joining
 * is each account's own act, with its own sign in.
 */
data class BuiltInChannel(
    /** The name the card shows before Telegram answers, and the name a nameless post falls back to. */
    val name: String,
    /** The invite link's hash, as [TgLink.Invite] carries it: the part after `t.me/+`. */
    val inviteHash: String,
) {
    /** The invite as a link, for the resolver the cards share with the link flow. */
    val invite: TgLink.Invite get() = TgLink.Invite(inviteHash)

    /** The full invite address, for sharing and for opening in Telegram. */
    val inviteUrl: String get() = invite.url
}

/** The channels built into the app, and the page a Share J Player message points at. */
object BuiltInChannels {

    /** The owner's channel: where new videos and the live stream posts land. */
    val J_MOVIES = BuiltInChannel(name = "J MOVIES", inviteHash = "4eZHRqpCKHE5MmE1")

    /** The owner's bot network's updates channel (@ProSearchFather). */
    val PRO_SEARCH = BuiltInChannel(name = "Pro Search Updates", inviteHash = "pwky7--tQxxkNmI1")

    /** Every built-in channel, in the order the Home cards show them. */
    val ALL: List<BuiltInChannel> = listOf(J_MOVIES, PRO_SEARCH)

    /**
     * The J MOVIES chat's id, once its invite has been resolved this session (Home resolves
     * it for the Live row), or null until then. Folding reads it so that channel's own post
     * of a file is the one a fold leads with, never the one folded away (owner, 2026-11-06):
     * see [VideoCopies.builtInCopy].
     */
    @Volatile
    var jMoviesChatId: Long? = null

    /**
     * Whether this run of the app has already tried its one automatic join of J MOVIES
     * (owner, 2026-11-06, directed against advice): the join is attempted once, after
     * sign-in on the first Home load, only where the account is not a member, its failure
     * is silent, and it is never retried in a loop. A fresh run of the app may try again.
     */
    @Volatile
    var jMoviesJoinAttempted: Boolean = false

    /** The app's release page: every release, newest first. */
    const val RELEASES_URL = "https://github.com/jamsheer-jr/JPlayer/releases"
}
