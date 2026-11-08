package com.tmplayer.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.tmplayer.App
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchNext
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Continue Watching home screen widget (owner, v1.0.15, item 9c): the active profile's
 * Continue watching, read from the same store Home reads, as up to [ROWS] lines on the
 * launcher. A tap on a line opens the app at that video's resume point through the very
 * intent the TV home screen's Play next row uses ([WatchNextPublisher.intentFor], answered
 * by MainActivity's playFromHomeScreen), so the player resumes where the record says, the
 * same as a tap on the row inside the app. A tap on the title opens the app itself.
 *
 * Freshness, stated plainly: the widget redraws when Android asks (every half hour at the
 * fastest the system allows a widget) and whenever the app itself asks ([refresh], called
 * when the main screen starts and stops, which is when Continue watching changes). Between
 * those it can be a step behind the app; it never invents a place of its own.
 */
class ContinueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        App.backgroundScope.launch {
            try {
                val records = runCatching { SettingsStore(context).continueWatching.first() }.getOrDefault(emptyList())
                for (id in ids) manager.updateAppWidget(id, views(context, records))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private const val ROWS = 4

        private val ROW_IDS = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4)

        /** Asks every placed widget to redraw, for the moments Continue watching changes. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val name = ComponentName(context, ContinueWidget::class.java)
            val ids = runCatching { manager.getAppWidgetIds(name) }.getOrDefault(IntArray(0))
            if (ids.isEmpty()) return
            App.backgroundScope.launch {
                val records = runCatching { SettingsStore(context).continueWatching.first() }.getOrDefault(emptyList())
                for (id in ids) runCatching { manager.updateAppWidget(id, views(context, records)) }
            }
        }

        private fun views(context: Context, records: List<ResumeRecord>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_continue)
            views.setTextViewText(R.id.widget_title, L.continueTitle)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title, open)
            val shown = records.take(ROWS)
            views.setViewVisibility(R.id.widget_empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
            if (shown.isEmpty()) views.setTextViewText(R.id.widget_empty, L.continueEmpty)
            ROW_IDS.forEachIndexed { index, rowId ->
                val record = shown.getOrNull(index)
                if (record == null) {
                    views.setViewVisibility(rowId, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(rowId, View.VISIBLE)
                val left = Translator.messages.formatter.clock(record.remainingMs)
                val line = if (record.chatTitle.isNotBlank()) {
                    L.continueLeft(record.chatTitle, left)
                } else {
                    left
                }
                views.setTextViewText(rowId, "${record.title}\n$line")
                val intent = WatchNextPublisher.intentFor(
                    context,
                    WatchNext.Video(
                        chatId = record.chatId,
                        messageId = record.messageId,
                        fileId = record.fileId,
                        title = record.title,
                        chatTitle = record.chatTitle,
                        sizeBytes = record.sizeBytes,
                        durationSec = record.durationSec,
                    ),
                ).apply {
                    // One PendingIntent per video: without its own address, every line's
                    // intent compares equal to the first and all lines open that one.
                    data = Uri.parse("jplayer://widget/${record.chatId}_${record.messageId}")
                }
                val tap = PendingIntent.getActivity(
                    context,
                    index + 1,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(rowId, tap)
            }
            return views
        }
    }
}
