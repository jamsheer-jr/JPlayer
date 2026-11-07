package com.tmplayer.ui.browse

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import com.tmplayer.data.WatchStats
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The My Stats share card (item 8f, v1.0.15): this month's watching from the same records
 * and sums the Profile page's My Stats card shows ([WatchStats]), drawn as one picture and
 * handed to the share sheet, the way the poster card is handed over in [sharePosterCard].
 * The card is kept in Pictures/J Player as it is shared, like a poster card. It counts
 * this month's records only (finished by their day, on the go by their last touch): the
 * month is named on the card, so what it says is exactly what was counted. A month with
 * nothing finished says so instead of sharing an empty card.
 */
suspend fun shareStatsCard(context: Context, stats: WatchStats, monthStartMs: Long) {
    if (stats.finishedCount == 0) {
        showMessage(context, L.profileActivityEmpty)
        return
    }
    val month = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(monthStartMs)
    val card = withContext(Dispatchers.Default) { drawStatsCard(stats, month) }
    val uri = StatsCard.save(context, card)
    if (uri == null) {
        showMessage(context, L.gridShareFailed)
        return
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, L.commonShare).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { showMessage(context, L.gridShareFailed) }
}

/** The first moment of the current month, local time: the card's counting line. */
fun monthStartMs(now: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * The card itself: the app's own navy, the My Stats title and the month, then the same
 * lines the on screen card shows (the summary, the fullest genres, the fullest chats),
 * each cut to fit rather than wrapped. Drawn at a size a share sheet shows well.
 */
private fun drawStatsCard(stats: WatchStats, month: String): Bitmap {
    val width = 840
    val lines = buildList {
        add(L.browseStatsSummary(stats.watchedMs / 3_600_000L, stats.finishedCount, stats.goingCount) to false)
        if (stats.topGenres.isNotEmpty()) {
            add(L.browseStatsGenres(stats.topGenres.joinToString(", ") { it.first }) to true)
        }
        if (stats.topChats.isNotEmpty()) {
            add(L.browseStatsChats(stats.topChats.joinToString(", ") { it.first }) to true)
        }
    }
    val height = 120 + 76 + 52 + lines.size * 58 + 56
    val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(card)
    canvas.drawColor(Color.rgb(5, 12, 28))
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 58f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText(L.browseStatsTitle, 48f, 108f, titlePaint)
    val monthPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 204, 113)
        textSize = 40f
    }
    canvas.drawText(month, 48f, 168f, monthPaint)
    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }
    val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(176, 190, 214)
        textSize = 38f
    }
    var y = 262f
    for ((line, muted) in lines) {
        val paint = if (muted) mutedPaint else bodyPaint
        val shown = TextUtils.ellipsize(line, paint, (width - 96).toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, 48f, y, paint)
        y += 58f
    }
    return card
}

/** A stats card into Pictures/J Player through MediaStore, as the poster card writes. */
private object StatsCard {

    /** Writes [bitmap] as "My stats.png" and gives back its gallery address, or null. */
    suspend fun save(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "My stats.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/J Player")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return@withContext null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } == true
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    }
}
