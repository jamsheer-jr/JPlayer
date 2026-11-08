package com.tmplayer.player

import android.content.Context
import com.tmplayer.data.ArchiveHeads
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The byte half of [ArchiveHeads]: reads a joined split set's first piece's first
 * bytes through the player's own byte window, and where they are an archive's
 * signature, keeps the verdict in the store the lists read. Asked from the places a
 * set is offered (a title's page, the player's copies), never in a list's own build:
 * a set is read once, in the background, and a set already proven, or already being
 * read, is not read again. A read that cannot be finished (no connection, a file
 * Telegram has lost) leaves no verdict either way.
 */
object ArchiveHeadCheck {

    private const val HEAD_BYTES = 8
    private const val PROBE_MS = 20_000L

    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun maybe(context: Context, item: MediaItem) {
        val key = ArchiveHeads.keyOf(item) ?: return
        if (key in ArchiveHeads.proven.value) return
        val first = item.pieces.firstOrNull() ?: return
        if (!running.add(key)) return
        val app = context.applicationContext
        com.tmplayer.App.backgroundScope.launch {
            try {
                val session = Td.awaitConnectedSessionOrNull() ?: return@launch
                val fileId = runCatching { Td.currentFileId(first.chatId, first.messageId, first.fileId) }
                    .getOrDefault(first.fileId)
                val archive = readHead(session.client, fileId) ?: return@launch
                if (archive) {
                    val settings = SettingsStore(app)
                    val now = ArchiveHeads.plus(settings.archiveHeadsNow(), key)
                    settings.setArchiveHeads(now)
                    ArchiveHeads.follow(now)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No verdict: the set stands as it did, and a later offer reads again.
            } finally {
                running.remove(key)
            }
        }
    }

    /** The first bytes of [fileId] read as an archive or not; null when they could not be read. */
    private suspend fun readHead(client: TdlClient, fileId: Int): Boolean? {
        val window = com.tmplayer.player.TdByteWindow(client)
        window.watch(fileId)
        try {
            return withTimeoutOrNull(PROBE_MS) {
                window.lookUp()
                window.awaitBytesAt(0)
                val buffer = ByteArray(HEAD_BYTES)
                val read = window.read(0, buffer, 0, HEAD_BYTES)
                if (read > 0) ArchiveHeads.isArchive(buffer.copyOf(read)) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            window.close()
        }
    }
}
