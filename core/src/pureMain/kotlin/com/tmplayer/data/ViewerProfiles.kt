package com.tmplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One viewer profile inside ONE Telegram account (owner, v1.0.15, item 1): the Netflix shape,
 * a name on the Profile page that a family member picks, behind which their own Continue
 * watching, Watched history, Watch later, Collections and My Stats live. This is not an
 * account: the Telegram sign in, the chats and the downloads are the account's and stay shared.
 *
 * [id] is the key segment every per profile record carries (see [ViewerProfiles.prefixFor]).
 * The default profile, the one every install has had all along, has the empty id and keeps the
 * unprefixed keys it has always used, so an upgrade moves nothing on disk: the records a viewer
 * already has are the default profile's records, read exactly where they were written.
 */
data class ViewerProfile(
    val id: String,
    val name: String,
    /** A Simple profile (item 7g): Settings is not reachable and Home shows its clean form. */
    val simple: Boolean = false,
    /**
     * The daily time limit in minutes (item 16, v1.0.16): how much watching this profile
     * gets in a day before playback stops and the lock notice stands. 0 is no limit. It is
     * a profile fact, so it rides this list's stored line (and its backup) the way the
     * Simple mark does, as a fourth field written only while it is set.
     */
    val dailyLimitMinutes: Int = 0,
) {
    val isDefault: Boolean get() = id == ViewerProfiles.DEFAULT_ID
}

/**
 * The stored shape of the viewer profiles list, and the key prefixing the split runs on.
 *
 * The list itself is one text value in [SettingsStore] (the key `viewer_profiles`): one line per
 * profile after the default, fields split by the unit separator the other records use
 * ([WatchedRecord]), so a name may hold anything but that. The default profile is only in the
 * list once it has been renamed; an absent default is the untouched install. A profile's PIN is
 * NOT here: it is a salted hash under its own key in [SettingsStore], the way the app lock's PIN
 * is kept ([PinHash]), and it never travels in a backup for the same reason the app lock's does
 * not (see [Backups]).
 */
object ViewerProfiles {

    /** The default profile's id: empty, so its key prefix is empty and its keys never change. */
    const val DEFAULT_ID = ""

    /** How many profiles one account may hold, the default included. */
    const val MAX_PROFILES = 5

    /** A profile name's cap, as the collections cap theirs. */
    const val MAX_NAME = 24

    /** The largest daily limit the chooser offers: twelve hours (item 16, v1.0.16). */
    const val MAX_DAILY_LIMIT_MINUTES = 12 * 60

    private const val SEP = '\u001F'
    private const val SIMPLE = "s"
    private const val FULL = "f"

    /**
     * The segment a profile's records carry ahead of the key they have always had: nothing for
     * the default profile (its keys are the legacy ones, which is the whole migration), and
     * `pf_<id>_` for any other, so `resume_1_2` and `pf_p2_resume_1_2` never collide and a sweep
     * over one profile's lines can never take another's by prefix alone.
     */
    fun prefixFor(id: String): String = if (id == DEFAULT_ID) "" else "pf_" + id + "_"

    /** Whether [name] as stored in a key belongs to the profile whose prefix is [prefix]. */
    fun owns(prefix: String, name: String): Boolean =
        if (prefix.isEmpty()) !name.startsWith("pf_") else name.startsWith(prefix)

    /**
     * The profile prefix [name] carries, or the empty string: the segment `pf_<id>_` at its
     * head, where the id holds no underscore of its own. A name that merely starts with the
     * letters (a legacy key could, in principle) is read as the default profile's unless the
     * segment is a plausible id followed by an underscore.
     */
    fun prefixOf(name: String): String {
        if (!name.startsWith("pf_")) return ""
        val end = name.indexOf('_', 3)
        if (end < 0) return ""
        val id = name.substring(3, end)
        return if (validId(id)) name.substring(0, end + 1) else ""
    }

    /** [name] with any profile prefix taken off: the key the default profile would have used. */
    fun stripPrefix(name: String): String = name.removePrefix(prefixOf(name))

    /** Every distinct profile prefix present among [names], the default's empty one included. */
    fun prefixesIn(names: Iterable<String>): Set<String> = names.mapTo(HashSet()) { prefixOf(it) }

    /** The next free profile id (`p2`, `p3`, ...), skipping any already taken. */
    fun newId(existing: Collection<String>): String {
        var n = 2
        while ("p$n" in existing) n++
        return "p$n"
    }

    /** A name as it may be stored: trimmed, single spaced, capped. Blank stays blank. */
    fun cleanName(raw: String): String =
        raw.replace(SEP, ' ').trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

    fun encode(profiles: List<ViewerProfile>): String = profiles
        // A profile carrying only a fact (the unnamed default with a daily limit set)
        // still earns its line; a wholly untouched default does not.
        .filter { !it.isDefault || it.name.isNotBlank() || it.dailyLimitMinutes > 0 }
        .take(MAX_PROFILES)
        .joinToString("\n") { profile ->
            val fields = ArrayList<String>()
            fields += profile.id
            fields += cleanName(profile.name)
            fields += if (profile.simple) SIMPLE else FULL
            if (profile.dailyLimitMinutes > 0) fields += profile.dailyLimitMinutes.toString()
            fields.joinToString(SEP.toString())
        }

    /**
     * The profiles in [raw], the stored text. Anything malformed (a line of the wrong shape, a
     * repeated or overlong id, a name that cleans to nothing) loses only its own line; the rest
     * still read. The default profile, when the text names it, stands first whatever order the
     * lines are in.
     */
    fun decode(raw: String?): List<ViewerProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<ViewerProfile>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_PROFILES) break
            val parts = line.split(SEP)
            // Three fields, or four while a daily limit rides the line (item 16, v1.0.16);
            // a line written before the limit existed reads with no limit.
            if (parts.size !in 3..4) continue
            val id = parts[0]
            if (id != DEFAULT_ID && !validId(id)) continue
            if (!seen.add(id)) continue
            val name = cleanName(parts[1])
            val limit = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(0, MAX_DAILY_LIMIT_MINUTES) ?: 0
            // A nameless line survives only while it carries a fact worth keeping.
            if (name.isEmpty() && limit <= 0) continue
            // The default profile is never Simple: it is the way back to Settings (item 7g).
            out += ViewerProfile(
                id = id,
                name = name,
                simple = id != DEFAULT_ID && parts[2] == SIMPLE,
                dailyLimitMinutes = limit,
            )
        }
        return out.sortedBy { if (it.isDefault) 0 else 1 }
    }

    /** The profile [id] in [profiles], or null. The default is there only once renamed. */
    fun find(profiles: List<ViewerProfile>, id: String): ViewerProfile? =
        profiles.firstOrNull { it.id == id }

    /** Whether [id] may still be added: a valid shape and room under the cap, itself included. */
    fun canAdd(profiles: List<ViewerProfile>): Boolean =
        profiles.count { !it.isDefault } + 1 < MAX_PROFILES

    private fun validId(id: String): Boolean =
        id.length in 1..16 && id.all { it in 'a'..'z' || it in '0'..'9' }
}

/**
 * Which viewer profile is active in this process, for the stores that keep their records in a
 * file of their own ([WatchedStore], [WatchLaterStore]) and so cannot read the choice out of
 * [SettingsStore]'s file as they go.
 *
 * The one writer is [SettingsStore.setActiveProfile], which stores the choice and sets this in
 * the same call, so the two never disagree in a running app; the app also mirrors the stored
 * flow into this at start (App.kt), which is what a cold process reads its first lists by.
 * Mirrors [ChatGate]'s shape for the same reason: a process wide fact the stores combine over.
 */
object ProfileScope {
    private val state = MutableStateFlow(ViewerProfiles.DEFAULT_ID)

    /** The active profile's id, as a flow the stores' reads combine over. */
    val flow: StateFlow<String> = state.asStateFlow()

    /** The active profile's id right now, for a write being built. */
    val current: String get() = state.value

    /** The key prefix of the active profile right now. */
    val prefix: String get() = ViewerProfiles.prefixFor(state.value)

    fun set(id: String) {
        state.value = id
    }
}
