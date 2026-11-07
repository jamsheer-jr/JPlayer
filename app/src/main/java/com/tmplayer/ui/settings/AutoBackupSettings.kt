package com.tmplayer.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.DownloadTree
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Auto backup row in Settings, About (owner, v1.0.14): off until a folder is
 * picked, then the weekly save (see AutoBackupRunner) writes there. Picking uses
 * Android's own folder picker and keeps its grant, the same way the downloads
 * folder's "Other folder" does; a television ships without that picker, so there the
 * row only reports the state and picking stays a phone job, as with downloads.
 * Turning it off clears the setting; the grant is left held, since the same folder
 * may be the downloads folder too, whose own housekeeping decides what is released.
 */
@Composable
internal fun AutoBackupRow() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val s = LocalStrings.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val folder by settings.autoBackupFolder.collectAsStateWithLifecycle(initialValue = "")
    val tree = remember(folder) { DownloadTree.parse(folder) }
    val label by produceState<String?>(null, tree) {
        value = tree?.let { withContext(Dispatchers.IO) { DownloadTree.label(context, it) } }
    }
    val touch = isTouch()
    val canPick = remember(touch) {
        touch && Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null
    }
    var sheet by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    DownloadTree.take(context, picked) && DownloadTree.usable(context, picked)
                }
                if (ok) settings.setAutoBackupFolder(picked.toString()) else toast(s.settingsAutoBackupFailed)
            }
        }
    }
    ActionRow(
        title = s.settingsAutoBackup,
        subtitle = if (tree == null) s.settingsAutoBackupOff else s.settingsAutoBackupOn(label ?: folder),
        icon = TmIcons.Folder,
        onClick = {
            if (canPick) {
                if (tree == null) runCatching { picker.launch(null) } else sheet = true
            }
        },
    )
    if (sheet) {
        ChoiceSheet(
            title = s.settingsAutoBackup,
            onDismiss = { sheet = false },
            onClose = { sheet = false },
        ) {
            ChoiceList {
                ChoiceLine(
                    title = s.settingsAutoBackupChange,
                    selected = false,
                    onClick = {
                        sheet = false
                        runCatching { picker.launch(tree) }
                    },
                )
                ChoiceLine(
                    title = s.settingsAutoBackupTurnOff,
                    selected = false,
                    onClick = {
                        sheet = false
                        scope.launch { settings.setAutoBackupFolder("") }
                    },
                )
            }
        }
    }
}
