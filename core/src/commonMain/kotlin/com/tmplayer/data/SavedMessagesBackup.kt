package com.tmplayer.data

import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocumentLocal
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.MessageSendOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backup to Saved Messages (owner, v1.0.15, item 9b): one tap in Settings sends the backup
 * file to the viewer's own Saved Messages, so a phone change still restores easily: sign in
 * on the new phone, open Saved Messages, save the file, Restore. The viewer's own backup,
 * to their own account, sent only when they tap the row: nothing here runs by itself.
 *
 * The send follows the codebase's one existing sendMessage path ([ResumeSync]): the
 * connected session's client, the account's own id, its private chat with itself, silent
 * options. The file itself is written by the caller (the platform's backup actions, into
 * its cache folder) and only its path travels here, since where a file may be written is
 * each platform's business.
 */
object SavedMessagesBackup {

    /** Sends [file] as a document named [name]. True once Telegram took it. */
    suspend fun send(file: File, name: String): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L) return@withContext false
        val session = Td.awaitConnectedSessionOrNull() ?: return@withContext false
        val td = session.client
        try {
            val myId = Td.myId().takeIf { it != 0L } ?: return@withContext false
            val saved = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
            val content = InputMessageDocument(
                InputDocumentLocal(file.absolutePath),
                FormattedText(name, emptyArray()),
            )
            val options = MessageSendOptions(null, true, false, false, false, 0L, false, null, 0L, 0, false)
            val sent = td.sendMessage(saved, null, null, options, null, content).valueOrNull
            sent != null && sent.id != 0L
        } catch (e: Exception) {
            Logger.w(TAG, "Backup to Saved Messages failed", e)
            false
        }
    }

    private const val TAG = "SavedMessagesBackup"
}
