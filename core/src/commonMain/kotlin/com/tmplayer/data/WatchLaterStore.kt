package com.tmplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

/**
 * Watch later: single videos the viewer saved for another time, newest first.
 *
 * Starring a chat puts all of it on Home, which is too much for one film somebody posted in a busy
 * group. This keeps the one video instead. Nothing is downloaded, so it works in a chat that
 * restricts saving content as well as anywhere else.
 *
 * It lives in the Watched list's file ([WatchedStore.watchLater]) under a prefix of its own, which
 * is what lets finishing a video, or marking it watched, cross it off here in the same write that
 * ticks it. "Clear watched" leaves it alone; signing out clears both.
 *
 * A whole show can be saved too (HX20, [WatchLaterRecord.isShow]): one line by its chat and key,
 * which finishing an episode leaves alone; marking the rest of the show watched takes it off.
 */
class WatchLaterStore internal constructor(private val store: DataStore<Preferences>) {

    /**
     * One read of the file for the active viewer profile ([ProfileScope]), as in [WatchedStore]:
     * each profile has its own Watch later lines under its own prefix, the default profile's
     * under the names they have always had.
     */
    private fun <T> read(transform: (Preferences, String) -> T): Flow<T> = store.data
        .combine(ProfileScope.flow) { prefs, id -> transform(prefs, ViewerProfiles.prefixFor(id)) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * The list, most recently added first, without the chats the kids lock leaves out
     * ([ChatGate], CP57): the Home row and the History tab both read this.
     */
    val items: Flow<List<WatchLaterRecord>> = read { prefs, prefix ->
        decodeAll(prefs, prefix).sortedByDescending { it.addedAt }
    }.combine(ChatGate.allowed) { records, allowed -> KidsLock.filter(records, allowed) { it.chatId } }

    /** Every saved video's [SettingsStore.progressKey], for a menu deciding between add and remove. Shows are not in it. */
    val keys: Flow<Set<String>> = read { prefs, prefix ->
        val head = prefix + PREFIX
        prefs.asMap().keys.mapNotNullTo(HashSet()) { key ->
            key.name.takeIf { it.startsWith(head) }?.removePrefix(head)?.takeIf { WatchLaterRecord.SHOW_SEP !in it }
        }
    }

    /** Every saved show's [WatchLaterRecord.showId], for a show's page or menu deciding between add and remove. */
    val showIds: Flow<Set<String>> = read { prefs, prefix ->
        val head = prefix + PREFIX
        prefs.asMap().keys.mapNotNullTo(HashSet()) { key ->
            key.name.takeIf { it.startsWith(head) }?.removePrefix(head)?.takeIf { WatchLaterRecord.SHOW_SEP in it }
        }
    }

    suspend fun contains(chatId: Long, messageId: Long): Boolean =
        store.data.first()[keyOf(ProfileScope.prefix, chatId, messageId)] != null

    /** Adds [record], or moves it to the top with the new time when it is there already. */
    suspend fun add(record: WatchLaterRecord) {
        store.edit { prefs ->
            val prefix = ProfileScope.prefix
            prefs[keyOf(prefix, record)] = record.encode()
            evictOldest(prefs, prefix)
        }
    }

    suspend fun remove(chatId: Long, messageId: Long) {
        store.edit { it.remove(keyOf(ProfileScope.prefix, chatId, messageId)) }
    }

    /** Takes [record]'s line off the list, a video's or a show's alike. */
    suspend fun remove(record: WatchLaterRecord) {
        store.edit { it.remove(keyOf(ProfileScope.prefix, record)) }
    }

    /** Takes the show [seriesKey] of [chatId] off the list; its episodes saved one by one stay. */
    suspend fun removeShow(chatId: Long, seriesKey: String) {
        store.edit { it.remove(stringPreferencesKey(ProfileScope.prefix + PREFIX + WatchLaterRecord.showId(chatId, seriesKey))) }
    }

    /** Takes [ids] ([SettingsStore.progressKey]s) off the list in one edit, handed back for Undo ([putBack]). */
    suspend fun take(ids: Collection<String>): List<WatchLaterRecord> {
        if (ids.isEmpty()) return emptyList()
        val taken = ArrayList<WatchLaterRecord>()
        store.edit { prefs ->
            val prefix = ProfileScope.prefix
            for (id in ids.toSet()) {
                val key = stringPreferencesKey(prefix + PREFIX + id)
                val record = WatchLaterRecord.decode(id, prefs[key])
                if (prefs[key] != null) prefs.remove(key)
                if (record != null) taken += record
            }
        }
        return taken
    }

    /** Undo for [take]: each video back where it stood, by the time it was first added. */
    suspend fun putBack(records: List<WatchLaterRecord>) {
        if (records.isEmpty()) return
        store.edit { prefs ->
            val prefix = ProfileScope.prefix
            for (record in records) {
                val key = keyOf(prefix, record)
                if (prefs[key] == null) prefs[key] = record.encode()
            }
            evictOldest(prefs, prefix)
        }
    }

    /**
     * Empties the list, every profile's lines alike: the account level wipe signing out calls,
     * as in [WatchedStore.clear]. Keys are collected first: the map cannot be walked while it
     * is written.
     */
    suspend fun clear() {
        store.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { ViewerProfiles.stripPrefix(it.name).startsWith(PREFIX) }
            for (key in doomed) prefs.remove(key)
        }
    }

    /** One profile's lines: [prefix] then [PREFIX] then the video's or show's id. */
    private fun decodeAll(prefs: Preferences, prefix: String): List<WatchLaterRecord> = buildList {
        val head = prefix + PREFIX
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            if (!name.startsWith(head)) continue
            WatchLaterRecord.decode(name.removePrefix(head), value as? String)?.let(::add)
        }
    }

    /**
     * Keeps one profile's list to [MAX_WATCH_LATER]. A list longer than that has stopped being
     * a plan and become an archive, and the whole file is read on every change.
     */
    internal fun evictOldest(prefs: MutablePreferences, prefix: String) {
        val all = decodeAll(prefs, prefix)
        if (all.size <= MAX_WATCH_LATER) return
        all.sortedBy { it.addedAt }
            .take(all.size - MAX_WATCH_LATER)
            .forEach { prefs.remove(keyOf(prefix, it)) }
    }

    companion object {
        /** How many videos the list holds before the oldest start dropping off. */
        const val MAX_WATCH_LATER = 1_000

        /** Not the Watched list's "w_": the two share a file and must never read each other's lines. */
        private const val PREFIX = "l_"

        internal fun keyOf(prefix: String, chatId: Long, messageId: Long) =
            stringPreferencesKey(prefix + PREFIX + SettingsStore.progressKey(chatId, messageId))

        /** A video's line by its message, a show's by its chat and key ([WatchLaterRecord.id]). */
        private fun keyOf(prefix: String, record: WatchLaterRecord) = stringPreferencesKey(prefix + PREFIX + record.id)
    }
}
