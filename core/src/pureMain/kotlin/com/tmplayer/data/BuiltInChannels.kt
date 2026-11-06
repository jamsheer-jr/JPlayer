package com.tmplayer.data

/**
 * A channel the app itself carries (v1.0.10): shown on Home as a card beside the viewer's own
 * rows, whether or not the signed-in account has joined it yet. The card resolves the invite
 * link: a member's tap opens the channel like any chat, and anyone else gets the channel's
 * name and a Join action that joins with their own account, in the app. Nothing about the
 * channel is shared between accounts: the invite link is there for anyone holding the app,
 * and joining is each account's own act, with its own sign in.
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

    /** The app's release page: every release, newest first. */
    const val RELEASES_URL = "https://github.com/jamsheer-jr/TMPlayer/releases"
}
