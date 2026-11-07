package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DataUsage
import com.tmplayer.data.MediaItem
import com.tmplayer.data.WatchedRecord
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Viewing activity (owner, v1.0.15, item 2): the Netflix shape over this device's own
 * records. The list is the ACTIVE profile's watched history (the same records the Profile
 * page's Recently watched row folds), each title with the day and time it was finished.
 *
 * Over it, this device's data usage for the app, from Android's own counters ([DataUsage]):
 * this app only, this device only, since it last restarted, streaming and downloads counted
 * together, with the mobile and Wi-Fi split where the platform gives one. Other devices'
 * watching and usage are not here and cannot be: Telegram does not share them, which the
 * card's own line says rather than leaving the page looking complete.
 */
@Composable
internal fun ViewingActivity(
    watchedHistory: List<WatchedRecord>,
    onBack: () -> Unit,
    onOpenTitle: (MediaItem, String) -> Unit,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var usage by remember { mutableStateOf<DataUsage.Usage?>(null) }
    LaunchedEffect(context) {
        usage = withContext(Dispatchers.Default) { runCatching { DataUsage.read(context) }.getOrNull() }
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(Tone.background)) {
        Row(
            Modifier.fillMaxWidth().padding(top = statusTop, start = 4.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                M3Icon(TmIcons.ArrowBack, contentDescription = s.commonBack, tint = Tone.text, modifier = Modifier.size(24.dp))
            }
            M3Text(
                s.profileActivity,
                style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "usage") {
                DataUsageCard(usage)
            }
            item(key = "activity-heading") {
                M3Text(
                    s.profileActivityDetail,
                    style = M3MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            if (watchedHistory.isEmpty()) {
                item(key = "activity-empty") {
                    M3Text(
                        s.profileActivityEmpty,
                        style = M3MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            } else {
                items(watchedHistory, key = { "a-${it.key}" }) { record ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTitle(record.toMediaItem(), record.chatTitle) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            M3Text(
                                record.title,
                                style = M3MaterialTheme.typography.bodyLarge,
                                color = Tone.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            M3Text(
                                record.chatTitle,
                                style = M3MaterialTheme.typography.bodySmall,
                                color = Tone.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        M3Text(
                            s.formatter.dateTime(record.watchedAt),
                            style = M3MaterialTheme.typography.bodySmall,
                            color = Tone.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The data usage card: the app's total on this device since it booted, the mobile and Wi-Fi
 * parts where the platform splits them, and the line that says exactly what was counted.
 * Nothing is drawn while the counters have not answered, or where they never do.
 */
@Composable
private fun DataUsageCard(usage: DataUsage.Usage?) {
    val s = LocalStrings.current
    val shown = usage ?: return
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surfaceHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        M3Text(
            s.profileDataUsage,
            style = M3MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Tone.text,
        )
        M3Text(
            s.profileDataUsageTotal(s.formatter.bytes(shown.totalBytes)),
            style = M3MaterialTheme.typography.bodyLarge,
            color = Tone.text,
        )
        if (shown.wifiBytes != null || shown.mobileBytes != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                shown.wifiBytes?.let {
                    M3Text(
                        s.profileDataUsageWifi(s.formatter.bytes(it)),
                        style = M3MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                }
                shown.mobileBytes?.let {
                    M3Text(
                        s.profileDataUsageMobile(s.formatter.bytes(it)),
                        style = M3MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        M3Text(
            s.profileDataUsageDetail,
            style = M3MaterialTheme.typography.bodySmall,
            color = Tone.muted,
        )
    }
}
