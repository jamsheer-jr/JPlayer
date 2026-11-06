package com.tmplayer.ui.browse

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.L
import com.tmplayer.online.MetaResult
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Share poster" on the detail page (v1.0.10): the matched title's poster over its name,
 * drawn as one card and handed to the share sheet, the way the player's frame share hands
 * over its picture ([com.tmplayer.player.FrameSaver]). The poster is the provider's
 * picture, read from the metadata cache (fetched once, like any tile's art); a title with
 * no match or no poster says so instead of sharing a card with a hole in it. The card is
 * kept in Pictures/TMPlayer as it is shared, so it stays in the gallery like a saved frame.
 */
suspend fun sharePosterCard(context: Context, item: MediaItem) {
    val online = OnlineMetadata.current
    val info = online?.let { meta ->
        item.metaQuery?.let { query -> runCatching { meta.peek(query) }.getOrNull() }
            ?.let { it as? MetaResult.Found }?.info
    }
    val posterUrl = info?.posterUrl
    if (online == null || info == null || posterUrl == null) {
        showMessage(context, L.detailSharePosterNone)
        return
    }
    val file = runCatching { online.image(posterUrl) }.getOrNull()
    val poster = file?.let {
        withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    if (poster == null) {
        showMessage(context, L.detailSharePosterNone)
        return
    }
    val card = drawPosterCard(poster, info.title, info.year)
    val uri = PosterCard.save(context, card, info.title)
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

/**
 * The card itself: the poster full width, and under it a band in the app's own navy with
 * the title's name and year. Drawn at a size a share sheet shows well, never enormous.
 */
private fun drawPosterCard(poster: Bitmap, title: String, year: Int?): Bitmap {
    val width = 840
    val posterHeight = (width.toFloat() * poster.height / poster.width).toInt().coerceIn(200, 1_260)
    val band = if (year != null) 176 else 132
    val card = Bitmap.createBitmap(width, posterHeight + band, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(card)
    canvas.drawColor(Color.rgb(5, 12, 28))
    canvas.drawBitmap(poster, null, Rect(0, 0, width, posterHeight), Paint(Paint.FILTER_BITMAP_FLAG))
    val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 54f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val shown = TextUtils.ellipsize(title, namePaint, (width - 96).toFloat(), TextUtils.TruncateAt.END).toString()
    canvas.drawText(shown, 48f, posterHeight + 88f, namePaint)
    if (year != null) {
        val yearPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(176, 190, 214)
            textSize = 40f
        }
        canvas.drawText(year.toString(), 48f, posterHeight + 148f, yearPaint)
    }
    return card
}

/** A poster card into Pictures/TMPlayer through MediaStore, as [com.tmplayer.player.FrameSaver] writes its frames. */
private object PosterCard {

    /** Android 10 and later, where MediaStore needs no storage permission (as FrameSaver). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Writes [bitmap] as "<title> poster.png" and gives back its gallery address, or null. */
    suspend fun save(context: Context, bitmap: Bitmap, title: String): Uri? = withContext(Dispatchers.IO) {
        if (!supported) return@withContext null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(title))
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

    /** The title, made safe for a file name, with "poster" after it. */
    fun fileName(title: String): String {
        val name = title.replace(Regex("(?i)\\.(mkv|mp4|m4v|mov|webm|avi|ts|wmv|flv)$"), "")
            .map { c -> if (c < ' ' || c in "\\/:*?\"<>|") ' ' else c }.joinToString("")
            .replace(Regex("\\s+"), " ").take(80).trim().trimEnd('.', ' ')
            .ifBlank { "J Player" }
        return "$name poster.png"
    }
}
