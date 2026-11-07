package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.LinkPreviewOptions
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageSendOptions
import dev.g000sha256.tdl.dto.MessageText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Resume across devices, opt-in (Settings, [SettingsStore.resumeSync]): every device signed in to
 * the same account and with the switch on keeps its Continue watching places in one message in
 * the account's own Saved Messages ([ResumeSyncDoc]), so the TV picks up where the phone stopped.
 * No server: the message is the viewer's own, in their own Telegram.
 *
 * The message is sent once and edited in place after that, never sent again while it can be
 * found, and only edited when what it says would change. Edits to Saved Messages notify nobody.
 * A sync reads the message, takes in the places that are newer than this device's, and writes
 * back the merge: at start ([syncSoon]), when a video opens ([pullFor]), and a little after the
 * player saves a place ([pushSoon], gathered so a run of saves is one edit).
 *
 * Viewer profiles (v1.0.15, item 1): the message is the account's ONE sync document, so it
 * carries the DEFAULT profile's places only. While any other profile is active a sync is a
 * no-op ([Outcome.Off]): a family profile's places never enter the shared message, and the
 * message's places are never imported into a family profile, where they would land as that
 * viewer's own Continue watching. The default profile's keys are the unprefixed ones, so what
 * syncs is exactly what synced before profiles existed.
 */
object ResumeSync {

    private val lock = Mutex()

    /** When this process last sent the message, for the guard in [write]. */
    @Volatile private var lastSentAt = 0L
    private var pending: Job? = null

    /** What the last sync did, for the Settings row. */
    sealed interface Outcome {
        data object Off : Outcome
        data object NoConnection : Outcome
        data class Done(val received: Int, val wrote: Boolean) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** A sync in the background, soon: after sign in and at start. */
    fun syncSoon(settings: SettingsStore, afterMs: Long = START_DELAY_MS) {
        pending?.cancel()
        pending = Background.scope.launch {
            delay(afterMs)
            runCatching { sync(settings) }
        }
    }

    /** After the player saved a place: written once the saves have settled. */
    fun pushSoon(settings: SettingsStore) = syncSoon(settings, PUSH_SETTLE_MS)

    /**
     * Before a video opens: the other devices' places taken in, so a video half watched on the
     * phone opens at that point on the TV. Waits at most [PULL_TIMEOUT_MS]; never blocks playback.
     */
    suspend fun pullFor(settings: SettingsStore) {
        if (!runCatching { settings.resumeSyncNow() }.getOrDefault(false)) return
        kotlinx.coroutines.withTimeoutOrNull(PULL_TIMEOUT_MS) { runCatching { sync(settings, push = false) } }
    }

    /** Read, merge, take in, write back. */
    suspend fun sync(settings: SettingsStore, push: Boolean = true): Outcome = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!settings.resumeSyncNow()) return@withContext Outcome.Off
            // The default profile's document only: see the note at the head of this file.
            if (!settings.defaultProfileActiveNow()) return@withContext Outcome.Off
            val session = Td.awaitConnectedSessionOrNull() ?: return@withContext Outcome.NoConnection
            val td = session.client
            try {
                val myId = Td.myId().takeIf { it != 0L } ?: return@withContext Outcome.NoConnection
                val saved = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
                val message = find(td, saved, settings)
                val remoteText = (message?.content as? MessageText)?.text?.text
                val remote = ResumeSyncDoc.decode(remoteText) ?: ResumeSyncDoc.Doc()

                val now = System.currentTimeMillis()
                val records = settings.continueWatching.first()
                val local = records.map(ResumeSyncDoc::entryOf)
                val localKeys = local.mapTo(HashSet()) { it.key }
                // What this device wrote last time and no longer has: finished or removed here.
                val tombs = settings.resumeSyncKeysNow().filter { it !in localKeys }.mapNotNull { key ->
                    val (chat, msg) = key.split('_').takeIf { it.size == 2 } ?: return@mapNotNull null
                    ResumeSyncDoc.Tomb(chat.toLongOrNull() ?: return@mapNotNull null, msg.toLongOrNull() ?: return@mapNotNull null, now)
                }
                val merged = ResumeSyncDoc.merge(remote, ResumeSyncDoc.Doc(local, tombs), now)

                // The other devices' places that are newer than this one's, read here so the
                // file id is this device's own; a message that is gone is left out.
                val byKey = records.associateBy { "${it.chatId}_${it.messageId}" }
                var received = 0
                val repository = ChatRepository(td)
                for (entry in merged.entries) {
                    val mine = byKey[entry.key]
                    if (mine != null && mine.updatedAt >= entry.updatedAt) continue
                    val item = runCatching { repository.mediaItem(entry.chatId, entry.messageId) }.getOrNull() ?: continue
                    if (settings.importResume(entry, item.fileId)) received++
                }
                for (tomb in merged.tombs) settings.dropSyncedResume(tomb)

                var wrote = false
                if (push) {
                    val text = ResumeSyncDoc.encode(merged, L.settingsResumeSyncMessage)
                    if (text != remoteText) {
                        wrote = write(td, saved, message, text, settings)
                    }
                    settings.setResumeSyncKeys(merged.entries.mapTo(HashSet()) { it.key })
                }
                Outcome.Done(received, wrote)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Resume sync failed", e)
                Outcome.Failed(Failures.humanise(e))
            }
        }
    }

    /** The message, by the id this device remembers, else by its tag; null before the first one. */
    private suspend fun find(td: dev.g000sha256.tdl.TdlClient, chatId: Long, settings: SettingsStore): Message? {
        val known = settings.resumeSyncMessageNow()
        if (known != 0L) {
            val message = td.getMessage(chatId, known).valueOrNull
            if (message != null && isOurs(message)) return message
        }
        val found = td.searchChatMessages(chatId, null, ResumeSyncDoc.TAG, null, 0, 0, SEARCH_LIMIT, null)
            .valueOrNull?.messages?.firstOrNull(::isOurs)
            // Not indexed yet, or search not answering: the newest messages, from TDLib's own copy.
            ?: td.getChatHistory(chatId, 0, 0, HISTORY_LIMIT, false).valueOrNull?.messages?.filterNotNull()?.firstOrNull(::isOurs)
            ?: return null
        settings.setResumeSyncMessage(found.id)
        return found
    }

    private fun isOurs(message: Message): Boolean =
        (message.content as? MessageText)?.text?.text?.trimStart()?.startsWith(ResumeSyncDoc.TAG) == true

    /** Edits the message in place, or sends it the first time. True once Telegram took it. */
    private suspend fun write(
        td: dev.g000sha256.tdl.TdlClient,
        chatId: Long,
        message: Message?,
        text: String,
        settings: SettingsStore,
    ): Boolean {
        val content = InputMessageText(
            FormattedText(text, emptyArray()),
            LinkPreviewOptions(true, "", false, false, false),
            false,
        )
        // Saved Messages can always be edited; an edit that fails is tried at the next sync,
        // never answered with a second message.
        if (message != null) {
            return td.editMessageText(chatId, message.id, null, content).valueOrNull != null
        }
        // Silent, and the id it is given is only a stand in until Telegram confirms it, so the
        // next sync finds it again by its tag rather than by that id.
        // Telegram's search can take a while to index a message just sent, so a second sync soon
        // after the first could miss it and send another: never twice in [RESEND_GUARD_MS].
        val now = System.currentTimeMillis()
        if (now - lastSentAt < RESEND_GUARD_MS) return false
        lastSentAt = now
        val options = MessageSendOptions(null, true, false, false, false, 0L, false, null, 0L, 0, false)
        val sent = td.sendMessage(chatId, null, null, options, null, content).valueOrNull ?: return false
        settings.setResumeSyncMessage(0L)
        return sent.id != 0L
    }

    private const val TAG = "ResumeSync"
    private const val START_DELAY_MS = 8_000L
    private const val PUSH_SETTLE_MS = 20_000L
    private const val PULL_TIMEOUT_MS = 2_500L
    private const val SEARCH_LIMIT = 5
    private const val HISTORY_LIMIT = 30
    private const val RESEND_GUARD_MS = 15 * 60 * 1000L
}
