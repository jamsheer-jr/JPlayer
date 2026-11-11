package com.tmplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What TMPlayer is holding, split three ways, worked out in one place for both screens that show it.
 *
 * One split, and it is by ownership, because that is the question somebody looking at these panels
 * is actually asking: how much of this did I ask for, how much of it merely happened, and how much
 * is neither. [otherBytes] is the remainder rather than a measurement, so the three always add up
 * to [totalBytes] exactly.
 */
data class StorageSplit(
    /** Videos the viewer ticked, which nothing removes but the viewer. */
    val downloadBytes: Long,
    /** Videos left behind by playing something, strays on the disk included. */
    val cachedBytes: Long,
    /** How many of those there are, so a row can say "4 videos" rather than name one of them. */
    val cachedCount: Int,
    /** Thumbnails, previews, the database: everything that is not a video. */
    val otherBytes: Long,
    /** What TDLib says the whole of it comes to, which is the figure the device agrees with. */
    val totalBytes: Long,    /**
     * The video files on the disk that no record names, counted in [cachedBytes]: listed on their
     * own in the Cached tab, so space the device reports is never space nothing on screen owns.
     */
    val strays: List<WatchCacheRules.Stray> = emptyList(),
    /**
     * What the downloads that have not finished hold, paused, failed or still arriving: kept out
     * of [otherBytes], where it once sat as "pictures" nobody could find (user, 2026-10-18).
     */
    val unfinishedBytes: Long = 0,
    /** How many of those there are. */
    val unfinishedCount: Int = 0,
    /** The online posters and backdrops, beside TDLib's own pictures. Counted in [totalBytes]. */
    val posterBytes: Long = 0,
    /**
     * TDLib's pictures measured where they lie, in allocated bytes: its photo, thumbnail,
     * profile photo, sticker and audio folders under every account's files. What [otherBytes]
     * holds beyond them (the database, what no record names) is not a picture.
     */
    val allPictureBytes: Long = 0,
    /** The signed in account's share of [allPictureBytes]: the part a clearing can take. */
    val accountPictureBytes: Long = 0,
) {
    /**
     * The unmatched files' share of [cachedBytes], in allocated bytes: what deleting them
     * actually returns. Their apparent size (the length, [WatchCacheRules.Stray.bytes]) is the
     * full size of a file that may only be part streamed, and quoting it is how a free up once
     * promised gigabytes the disk never held.
     */
    val strayBytes: Long get() = strays.sumOf { it.allocatedBytes }

    /**
     * Every picture: TDLib's thumbnails and previews measured in their folders, with the
     * online posters. The remainder ([otherBytes]) is never quoted as pictures: it holds
     * the database and whatever no record names beside the pictures, and quoting it here
     * is how a free up once promised gigabytes for "pictures and posters" and freed
     * megabytes (owner's device, 2026-11-11).
     */
    val pictureBytes: Long get() = allPictureBytes + posterBytes

    /** What clearing pictures can actually free: this account's picture folders and the posters. */
    val clearablePictureBytes: Long get() = accountPictureBytes + posterBytes

    /** The chart's figures, against a disk of [disk]. */
    fun chart(disk: DiskInfo): StorageChart.Figures = StorageChart.Figures(
        downloads = downloadBytes,
        unfinished = unfinishedBytes,
        recent = (cachedBytes - strayBytes).coerceAtLeast(0),
        unmatched = strayBytes,
        pictures = pictureBytes,
        deviceTotal = disk.totalBytes,
        deviceFree = disk.freeBytes,
    )

    companion object {
        val EMPTY = StorageSplit(0, 0, 0, 0, 0)

        /**
         * Measures the disk. Slow enough to want a background thread: a TDLib round trip per
         * record, plus a walk of the files directory.
         */
        suspend fun measure(context: Context): StorageSplit = withContext(Dispatchers.IO) {
            val settings = SettingsStore(context)
            // TDLib's figure is its own files directory: the cache, the pictures, and the downloads
            // from before they had a folder. The Downloads folder is beside it and measured apart.
            // The fast statistics answer for the signed in account only, and answer 0 before they
            // are first computed, so the disk itself is walked too and the larger figure wins: a
            // header reading "uses 0 MB" over gigabytes on the disk is worse than either number
            // being a little stale (owner's device, 2026-11-06).
            val reported = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
            val total = maxOf(reported, tdFilesOnDisk(context))
            val history = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList())
            val indexedBytes = LocalDownloads.indexedBytes(settings)
            val kept = history.filter { it.localPath == null }.distinctBy { it.fileId }
            val keptIds = kept.map { it.fileId }.toSet()
            // Matched on the message as well as the file. TDLib does not promise the same file id
            // for the same video twice: watching it and then downloading it can produce two ids
            // for one file on disk, so matching only on the id counts the download a second time
            // as cache. Eviction matches on the message, so this must too.
            val keptMessages = history.map { it.chatId to it.messageId }.toSet()

            // A video can be both, if it was watched and then downloaded. It belongs to the viewer
            // then, and counting it in both halves would have the figures overrun the total.
            // The downloads that have not finished. Moving ones are whole and about to be in the
            // folder, so they are left to the next measurement.
            val unfinished = OfflineDownloads.active.value.values
                .filter { it.stage != OfflineDownloads.Stage.Moving && it.downloadedBytes > 0 }
            val unfinishedIds = unfinished.map { it.fileId }.toSet()
            val cachedRecords = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
                .filter { it.fileId !in keptIds && (it.chatId to it.messageId) !in keptMessages }
                .distinctBy { it.fileId }

            // Every stored id resolved against this session before anything is measured from it,
            // and all of them at once rather than one after another.
            val keptNow = kept
                .map { r -> async { r to Td.currentFileId(r.chatId, r.messageId, r.fileId) } }
                .awaitAll()
            val cachedNow = cachedRecords
                .map { r -> async { r to Td.currentFileId(r.chatId, r.messageId, r.fileId) } }
                .awaitAll()

            val keptBytes = keptNow.map { (_, id) ->
                async { runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L) }
            }
            val cachedBytesEach = cachedNow.map { (_, id) ->
                async { runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L) }
            }
            val legacyDownloads = keptBytes.awaitAll().sumOf { it.coerceAtLeast(0L) }
            val downloads = legacyDownloads + indexedBytes
            var cached = 0L
            var count = 0
            for ((index, held) in cachedBytesEach.awaitAll().withIndex()) {
                if (held <= 0) continue
                // A recently played video being saved to Downloads is counted there, once.
                if (cachedNow[index].second in unfinishedIds) continue
                cached += held
                count++
            }

            // And the videos on the disk that no record names, or the panels quote a couple of
            // hundred megabytes on a device Android reports as weighing two gigabytes.
            //
            // Anything the queue is fetching this moment counts as accounted for: a download is
            // only written to the history once it finishes, so without this a half-arrived video
            // is filed under "cached videos" and Clear offers to free the download being waited on.
            val knownIds = (
                cachedNow.map { it.second } + keptNow.map { it.second } +
                    OfflineDownloads.active.value.keys
                ).distinct()
            val accounted = knownIds
                .map { id -> async { runCatching { Td.localPathAnyway(id) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
            // An id TDLib will not resolve looks exactly like a video nobody owns. Skipping the
            // walk whenever one failed hid every stray, which is how a 2.5 GB video stayed on a
            // phone with nothing on the Cached tab. So the walk always runs, and while some known
            // file did not answer, a file the size of one of those videos is taken to be it and
            // left out, so the viewer's own download is never offered for clearing.
            val unanswered = if (accounted.size == knownIds.size) emptySet() else
                (cachedRecords + kept).map { it.sizeBytes }.filter { it > 0 }.toSet()
            val strays = runCatching { WatchCache.strays(context, accounted.toSet()) }
                .getOrDefault(emptyList())
                // The unanswered guard compares apparent sizes, since Telegram's figure for a
                // video is its full size: a stray the size of an unanswered video is taken to
                // be it, whatever blocks it holds.
                .filterNot { stray -> unanswered.any { size -> sameSize(stray.bytes, size) } }
            // Counted in allocated bytes, as [strayBytes] quotes them, so the chart's recent
            // remainder (cached minus strays) stays the named cached videos alone.
            cached += strays.sumOf { it.allocatedBytes }
            count += strays.size

            val unfinishedBytes = unfinished.sumOf { it.downloadedBytes }
            val posters = runCatching { com.tmplayer.online.OnlineMetadata.current?.imageBytes() ?: 0L }.getOrDefault(0L)
            // The pictures measured where they lie, not as what is left over: every
            // account's picture folders for the chart, the signed in account's own for
            // what a clearing can promise (see [pictureBytes]).
            val allPictures = pictureBytesOnDisk(tdRoots(context))
            val accountPictures = pictureBytesOnDisk(listOf(AndroidPaths(context).filesDir))

            StorageSplit(
                downloadBytes = downloads,
                cachedBytes = cached,
                cachedCount = count,
                // The remainder, floored: TDLib's total and a walk of the disk are taken a moment
                // apart, and a negative band is worse than a missing one.
                otherBytes = (total - legacyDownloads - cached - unfinishedBytes).coerceAtLeast(0L),
                totalBytes = maxOf(total, legacyDownloads + cached + unfinishedBytes) + indexedBytes + posters,
                strays = strays,
                unfinishedBytes = unfinishedBytes,
                unfinishedCount = unfinished.size,
                posterBytes = posters,
                allPictureBytes = allPictures,
                accountPictureBytes = accountPictures,
            )
        }

        /** Within half a megabyte: a whole file on the disk against the size Telegram gives for it. */
        private fun sameSize(onDisk: Long, told: Long): Boolean = kotlin.math.abs(onDisk - told) <= 512 * 1024

        /** TDLib's files folders: the signed in account's own and every other slot's. */
        private fun tdRoots(context: Context): List<java.io.File> {
            val app = context.applicationContext
            return buildList {
                add(AndroidPaths.cacheDir(app))
                app.filesDir.listFiles()?.forEach { child ->
                    if (child.isDirectory && (child.name.startsWith("cache-") || child.name == "tdlib-files")) add(child)
                }
            }
        }

        /**
         * Every byte inside TDLib's files folders, all account slots, the old folder name counted
         * once through its link. Listing only: lengths are read, nothing is opened.
         */
        private fun tdFilesOnDisk(context: Context): Long {
            val roots = tdRoots(context)
            val seen = mutableSetOf<String>()
            var bytes = 0L
            for (root in roots) {
                val key = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
                if (!seen.add(key)) continue
                runCatching {
                    root.walkTopDown().forEach { if (it.isFile) bytes += it.length() }
                }
            }
            return bytes
        }

        /** The folders TDLib keeps what is not a video in, the same names under every account's files. */
        private val PICTURE_DIRECTORIES = listOf("photos", "thumbnails", "profile_photos", "stickers", "audio")

        /**
         * What the picture folders under [roots] hold, in allocated bytes: what deleting
         * them returns, the only figure a clearing of pictures may be promised against.
         * A folder that is not there holds nothing, and a file that goes mid walk is
         * skipped by the same guard the stray walk uses.
         */
        private fun pictureBytesOnDisk(roots: List<java.io.File>): Long {
            val seen = mutableSetOf<String>()
            var bytes = 0L
            for (root in roots) {
                val key = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
                if (!seen.add(key)) continue
                for (name in PICTURE_DIRECTORIES) {
                    val dir = java.io.File(root, name)
                    if (!dir.isDirectory) continue
                    runCatching {
                        dir.walkTopDown().forEach { if (it.isFile) bytes += WatchCache.allocatedBytesOf(it) }
                    }
                }
            }
            return bytes
        }
    }
}
