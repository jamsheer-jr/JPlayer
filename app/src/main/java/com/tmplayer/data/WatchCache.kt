package com.tmplayer.data

import android.content.Context

/**
 * The videos playing left behind, on Android: [WatchCacheRules] with the one video rule, over
 * TDLib's cache directory as [AndroidPaths] has it, and the set of files a player has open.
 *
 * The rules themselves (who claims the disk, who gives it up, and what to do about the copies
 * nothing has a record of) are shared with the desktop and live in `:core`. What is Android's is
 * the context every caller already has, and [startedPlaying]/[stoppedPlaying], which the player
 * activity calls as it opens and closes a file.
 *
 * [claim] is called from the player rather than from any screen, so it runs once for every video
 * that is ever opened, however it was opened: stepping to the next episode starts the player
 * directly and never passes a browse screen.
 */
object WatchCache {

    /**
     * The one set of rules for the process, built on first use.
     *
     * One instance, because the claim lock and the claims in flight have to be shared by every
     * caller to mean anything. The phone and the TV keep one cached video ([CacheRule.OneVideo]),
     * and eviction does not look at [playing]: it never has here, and the claim of the video now
     * playing is what spares it.
     */
    @Volatile
    private var rules: WatchCacheRules? = null

    private fun rules(context: Context): WatchCacheRules = rules ?: synchronized(this) {
        rules ?: context.applicationContext.let { app ->
            WatchCacheRules(
                settings = SettingsStore(app),
                filesRoot = { AndroidPaths.cacheDir(app) },
                playing = ::playingNow,
                rule = { CacheRule.OneVideo },
                sparePlaying = false,
                allocatedOf = ::allocatedBytesOf,
            )
        }.also { rules = it }
    }

    /**
     * Marks a video as the one the cache is holding, and gives up the ones it was holding before.
     *
     * Called at the top of every playback. Idempotent: opening the same video twice claims it
     * twice and deletes nothing. Three kinds of file survive it: the video being played, anything
     * the viewer downloaded on purpose, and anything a download is currently fetching.
     */
    suspend fun claim(context: Context, item: MediaItem, chatTitle: String) {
        rules(context).claim(item, chatTitle)
    }

    /** [claim] for the byte pieces of one video cut into several files, kept together. */
    suspend fun claimAll(context: Context, pieces: List<MediaItem>, chatTitle: String) {
        rules(context).claimAll(pieces.map { it to it.fileId }, chatTitle)
    }

    /**
     * Runs [block] under the cache's claim lock: the same lock [claim] and [sweep] hold, exposed
     * for the Downloads page's manual Free up space, which deletes the same files a launch sweep
     * deletes and must not race one over them. See [WatchCacheRules.withClaimLock].
     */
    suspend fun <T> withClaimLock(context: Context, block: suspend () -> T): T =
        rules(context).withClaimLock(block)

    /**
     * What a file actually holds on disk, in bytes: its allocated blocks, not its length.
     *
     * TDLib writes streamed chunks at their offsets, so a part streamed file's length is its
     * full size while the blocks it holds can be a small fraction of that: the length is the
     * apparent size, and promising it as space a delete would return is how a review once
     * promised gigabytes on a phone whose whole app weighed a couple of hundred megabytes.
     *
     * `Os.stat` has been there since API 21 and the floor here is API 23, so it needs no version
     * guard of the LegacyApiCompat kind. Any failure (the file went between the walk and the
     * stat, a filesystem that reports no blocks) falls back to the length, the figure every
     * platform without this read uses.
     */
    internal fun allocatedBytesOf(file: java.io.File): Long {
        val blocks = runCatching { android.system.Os.stat(file.absolutePath).st_blocks }.getOrNull()
            ?: return file.length()
        return (blocks * 512L).coerceAtLeast(0L)
    }

    /**
     * The files open in a player right now, counted, because backing out of a video and opening it
     * again can briefly put two players on one file.
     *
     * The player's claim on the cache is written in the background, after its download has begun,
     * so for the first moments of a watch the file has no record at all. A sweep running then, at
     * launch or on the way out of the previous episode, took that for a stray and deleted the part
     * file underneath the download: the player sat on Loading until it was closed and opened again.
     */
    private val playing = mutableMapOf<Int, Int>()

    fun startedPlaying(fileId: Int) {
        if (fileId <= 0) return
        synchronized(playing) { playing[fileId] = (playing[fileId] ?: 0) + 1 }
    }

    fun stoppedPlaying(fileId: Int) {
        if (fileId <= 0) return
        synchronized(playing) {
            val left = (playing[fileId] ?: 0) - 1
            if (left > 0) playing[fileId] = left else playing.remove(fileId)
        }
    }

    fun isPlaying(fileId: Int): Boolean = synchronized(playing) { fileId in playing }

    /**
     * Whether any video is open in a player: the shell's own background work (Home's refetches,
     * the TV home screen's channels) waits while it is, so it never competes with playback.
     */
    fun anyPlaying(): Boolean = synchronized(playing) { playing.isNotEmpty() }

    private fun playingNow(): Set<Int> = synchronized(playing) { playing.keys.toSet() }

    /** Deletes every cached video except [keepFileId]. See [WatchCacheRules.evictAllBut]. */
    suspend fun evictAllBut(context: Context, keepFileId: Int) {
        rules(context).evictAllBut(keepFileId)
    }

    /**
     * Every video file on this device that is neither a download nor a cached video we can name.
     *
     * Found by walking TDLib's own files directory rather than by asking TDLib, because TDLib
     * reports the total and the breakdown but not the individual files, and a total on its own is
     * something the viewer can do nothing with.
     *
     * @param knownPaths the local paths of everything already on the Downloads screen, which are
     *   asked of TDLib by the caller: only it knows which records are worth resolving.
     */
    fun strays(context: Context, knownPaths: Set<String>): List<WatchCacheRules.Stray> {
        // Every account slot keeps its own cache folder (cache, cache-1, cache-2 and the old
        // tdlib-files name, now a link to cache). Walking only the active account's folder made
        // the other accounts' leftover videos invisible: counted by the device, listed nowhere,
        // and impossible to free, which is how a free up could promise gigabytes and free
        // megabytes (owner's device, 2026-11-06). The sweep stays on the active folder alone;
        // this listing, which exists so the viewer can free space, covers them all.
        val app = context.applicationContext
        val roots = buildList {
            add(AndroidPaths.cacheDir(app))
            app.filesDir.listFiles()?.forEach { child ->
                if (child.isDirectory && (child.name.startsWith("cache-") || child.name == "tdlib-files")) add(child)
            }
        }
        val seen = mutableSetOf<String>()
        val found = mutableListOf<WatchCacheRules.Stray>()
        for (root in roots) {
            val key = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            if (!seen.add(key)) continue
            // The Android walk: each stray also carries what it actually holds on disk, read
            // from its allocated blocks, beside the length the shared walk records.
            found += WatchCacheRules.straysIn(root, knownPaths, allocatedOf = ::allocatedBytesOf)
        }
        return found.sortedByDescending { it.allocatedBytes }
    }

    /** The same, against a directory rather than a device, so the rule can be tested. */
    internal fun straysIn(root: java.io.File, knownPaths: Set<String>): List<WatchCacheRules.Stray> =
        WatchCacheRules.straysIn(root, knownPaths)

    /** Removes one stray from the disk. TDLib refetches it if the video is ever played again. */
    fun forget(context: Context, stray: WatchCacheRules.Stray): Boolean = rules(context).forget(stray)

    /**
     * Empties the cache: every video that is not a download, and every stray beside them.
     *
     * The one implementation of "clear the cached videos", used by both Settings rows that mean it.
     *
     * @return how many bytes went.
     */
    suspend fun clearAll(context: Context): Long = rules(context).clearAll()

    /**
     * Takes the videos nothing owns, without being asked.
     *
     * Runs where the ceiling trim runs, on launch and on the way out of a video, so a device does
     * not need the viewer to know about Settings and press Clear to get its space back.
     *
     * @return how many bytes went, which is worth logging and nothing else.
     */
    suspend fun sweep(context: Context): Long = rules(context).sweep()

    /**
     * When the device is nearly full (owner, 2026-11-06), the cache gives up everything it
     * can, without being asked: every cached video that is not playing, being fetched or
     * claimed. Downloads are never the cache's to take, and the rule skips any copy a
     * download owns, so a kept video cannot be lost to this.
     */
    suspend fun criticalSweep(context: Context) {
        val free = runCatching { DiskSpace.read(context).freeBytes }.getOrDefault(Long.MAX_VALUE)
        if (free >= CRITICAL_FREE_BYTES) return
        runCatching { rules(context).evictAllBut(playingNow()) }
    }

    /** Under this much free space the device is critical, and the cache empties itself. */
    private const val CRITICAL_FREE_BYTES = 500L * 1024 * 1024
}
