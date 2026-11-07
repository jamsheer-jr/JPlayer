package com.tmplayer.data

import android.content.Context
import com.tmplayer.BuildConfig
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * The weekly auto backup (owner, v1.0.14): the same file the manual Back up writes
 * (see [BackupService]), saved by itself once a week into the folder the viewer picked
 * in Settings, under the service's dated name, so the weeks stand side by side rather
 * than overwriting each other.
 *
 * Silent by design: it runs once per launch at most, says nothing when there is
 * nothing to do, and a folder that cannot be reached (its card is out, its grant was
 * taken back) is skipped without a word and without clearing the setting, since the
 * folder may well be back next week. Only a written file moves the clock.
 */
object AutoBackupRunner {

    suspend fun runIfDue(context: Context) {
        val settings = SettingsStore(context)
        val folder = runCatching { settings.autoBackupFolder.first() }.getOrDefault("")
        val tree = DownloadTree.parse(folder) ?: return
        val last = runCatching { settings.autoBackupLast.first() }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        if (!AutoBackup.due(last, now)) return
        val written = withContext(Dispatchers.IO) {
            runCatching {
                if (!DownloadTree.usable(context, tree)) return@runCatching false
                val service = BackupService(
                    settings = settings,
                    watched = WatchedStore(context),
                    metadata = OnlineMetadata.current?.store,
                    appVersion = BuildConfig.VERSION_NAME,
                    bookmarks = BookmarkStore(context),
                )
                val bytes = service.export().toByteArray()
                DownloadTree.put(context, tree, service.fileName(), bytes.size.toLong()) {
                    ByteArrayInputStream(bytes)
                }
                true
            }.onFailure { Logger.w(TAG, "Auto backup not written", it) }.getOrDefault(false)
        }
        if (written) runCatching { settings.setAutoBackupLast(now) }
    }

    private const val TAG = "AutoBackup"
}
