package com.tmplayer.data

import android.content.Context
import com.tmplayer.platform.Background
import kotlinx.coroutines.launch

/**
 * The Telegram accounts on this device, and which of them is in use.
 *
 * Kept in its own preferences file rather than in the settings store, for two reasons: the
 * slot in use has to be read before anything else starts, because it decides where TDLib's
 * database is ([AndroidPaths], [Td.start]); and signing out clears the settings store
 * ([com.tmplayer.ui.settings.forgetAccount]), which must not take the other accounts' list
 * with it. Nothing here is a credential: a sign in lives only in its slot's TDLib database.
 */
object Accounts {

    private const val PREFS = "accounts"
    private const val KEY_ACTIVE = "active_slot"
    private const val KEY_LIST = "list"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The slot in use: the account every start opens. Slot 0 until a switch says otherwise. */
    fun activeSlot(context: Context): Int =
        prefs(context).getInt(KEY_ACTIVE, AccountBook.FIRST_SLOT).coerceAtLeast(0)

    /** The accounts the list shows, in slot order. */
    fun entries(context: Context): List<AccountEntry> =
        AccountBook.decode(prefs(context).getString(KEY_LIST, null))

    /** Remembers [entry]: its slot's line is replaced, the rest are kept. Written at once. */
    fun record(context: Context, entry: AccountEntry) {
        val now = AccountBook.withIdentity(entries(context), entry)
        prefs(context).edit().putString(KEY_LIST, AccountBook.encode(now)).commit()
    }

    /**
     * Takes the next slot for a new account and lists it, unnamed until it signs in and is
     * remembered. The caller switches to it ([switchAccount]), which is what signs it in.
     */
    fun addSlot(context: Context): Int {
        val now = entries(context)
        val slot = AccountBook.nextSlot(now)
        val blank = AccountEntry(slot, userId = 0L, name = "", username = "")
        prefs(context).edit().putString(KEY_LIST, AccountBook.encode(AccountBook.withIdentity(now, blank))).commit()
        return slot
    }

    /** Makes [slot] the account in use from the next client on. Committed, not only applied. */
    fun setActive(context: Context, slot: Int) {
        prefs(context).edit().putInt(KEY_ACTIVE, slot).commit()
    }

    /**
     * Forgets [slot]'s line, when that account is signed out for good. The slot number is
     * retired with it ([AccountBook]): its directories are never handed to another account.
     */
    fun retire(context: Context, slot: Int) {
        val now = AccountBook.without(entries(context), slot)
        prefs(context).edit().putString(KEY_LIST, AccountBook.encode(now)).commit()
    }
}

/**
 * Switches the account in use to [slot].
 *
 * TDLib holds one client at a time and takes its directories when it starts, so a switch is
 * [Td.restart] over the other account's own database and files directories: the client that
 * is leaving closes first, and the one that starts reads the incoming account's sign in from
 * its own database, or asks for one through the usual sign in screens when it has none.
 * Nothing is logged out and nothing is forgotten: each account's session stays in its own
 * directories, and only one client ever runs, so the two accounts' Telegram data cannot mix.
 * Playback and downloads of the account being left stop with its client; their records stay
 * where they are and go on when the viewer switches back.
 *
 * The account being left is remembered first (its list line keeps its name), and the one
 * arrived at is remembered once it is signed in, which for a new account is whenever its
 * sign in finishes.
 */
suspend fun switchAccount(context: Context, slot: Int) {
    val app = context.applicationContext
    if (slot == Accounts.activeSlot(app)) return
    rememberWhoIsLeaving(app)
    Accounts.setActive(app, slot)
    Td.restart(AndroidPaths(app, slot))
    rememberWhoArrived(app, slot)
}

/** The leaving account's identity, read off its own client before that client closes. */
private fun rememberWhoIsLeaving(app: Context) {
    if (Td.auth.value !is AuthState.Ready) return
    val leaving = Accounts.activeSlot(app)
    Background.scope.launch {
        val session = runCatching { Td.awaitAuthorizedSession() }.getOrNull() ?: return@launch
        val me = runCatching { Td.me(session) }.getOrNull()
        val id = runCatching { Td.myId() }.getOrDefault(0L)
        if (me != null || id != 0L) {
            Accounts.record(app, AccountEntry(leaving, id, me?.name.orEmpty(), me?.username.orEmpty()))
        }
    }
}

/** The arriving account's identity, once its client is signed in (a new account: at sign in). */
private fun rememberWhoArrived(app: Context, slot: Int) {
    Background.scope.launch {
        val session = runCatching { Td.awaitAuthorizedSession() }.getOrNull() ?: return@launch
        if (Accounts.activeSlot(app) != slot || !session.isCurrent()) return@launch
        val me = runCatching { Td.me(session) }.getOrNull()
        val id = runCatching { Td.myId() }.getOrDefault(0L)
        if (me != null || id != 0L) {
            Accounts.record(app, AccountEntry(slot, id, me?.name.orEmpty(), me?.username.orEmpty()))
        }
    }
}
