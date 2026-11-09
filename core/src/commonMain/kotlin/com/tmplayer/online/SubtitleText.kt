package com.tmplayer.online

import com.tmplayer.data.MediaName
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * A downloaded subtitle turned into what both players read: text, as UTF-8, in a format they
 * understand. Online files arrive in whatever code page their uploader saved them in, and SubDL's
 * arrive zipped, sometimes with a whole season inside.
 */
object SubtitleText {

    /** The text formats both players take as they stand. */
    val FORMATS = setOf("srt", "ass", "ssa", "vtt")

    /** The largest subtitle worth keeping: a feature film's are tens of kilobytes. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * A subtitle read out of a download: its format and its words, and, where it
     * came out of an archive, the name of the file inside the archive it was
     * (v1.0.23), so the track can stand under its own file name.
     */
    data class Text(val extension: String, val text: String, val entryName: String? = null)

    /**
     * Subtitle bytes as text, whatever they were saved in.
     *
     * In order: a byte order mark (UTF-8, UTF-16 and UTF-32, either way round), which is what
     * Windows Notepad's "Unicode" writes; UTF-16 without one, told by the zero byte in every other
     * place that its digits, timestamps and line breaks carry; valid UTF-8; and last the Windows
     * code page of [language] (the subtitle's own language where it is known, else the viewer's),
     * or the Western one. An Arabic file saved in Windows-1256 and read as Windows-1252 came out
     * as a line of accented Latin letters, and a UTF-16 file as a NUL between every letter.
     */
    fun decode(bytes: ByteArray, language: String? = null): String {
        val text = withBom(bytes)
            ?: utf16WithoutBom(bytes)?.let { charset -> String(bytes, charset) }
            ?: strictUtf8(bytes)
            ?: String(bytes, codePageFor(language))
        return text.trimStart('﻿')
    }

    /** The text after a byte order mark, decoded in the encoding the mark names; null without one. */
    private fun withBom(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        val (name, skip) = when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> "UTF-8" to 3
            at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00 -> "UTF-32LE" to 4
            at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF -> "UTF-32BE" to 4
            at(0) == 0xFF && at(1) == 0xFE -> "UTF-16LE" to 2
            at(0) == 0xFE && at(1) == 0xFF -> "UTF-16BE" to 2
            else -> return null
        }
        val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return null
        return String(bytes, skip, bytes.size - skip, charset)
    }

    /**
     * UTF-16 with no byte order mark. A subtitle is mostly digits, colons, spaces and line breaks
     * whatever its language, and in UTF-16 each of those is a zero byte beside an ASCII one, on
     * the same side throughout. Neither UTF-8 nor a code page puts zero bytes in text at all.
     */
    private fun utf16WithoutBom(bytes: ByteArray): Charset? {
        val pairs = minOf(bytes.size, UTF16_SNIFF_BYTES) / 2
        if (pairs < 2) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until pairs) {
            if (bytes[2 * i].toInt() == 0) evenZeros++
            if (bytes[2 * i + 1].toInt() == 0) oddZeros++
        }
        val name = when {
            oddZeros * 5 >= pairs && evenZeros * 20 <= oddZeros -> "UTF-16LE"
            evenZeros * 5 >= pairs && oddZeros * 20 <= evenZeros -> "UTF-16BE"
            else -> return null
        }
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * The code page a subtitle in [language] was most likely saved in when it is not Unicode:
     * the Windows one for its script, which is what older subtitle files and most editors on
     * Windows wrote. A two or three letter code, with or without a region or script ("zh-TW",
     * "zh-Hant", "ara"); Windows-1252 for anything else, and wherever the runtime lacks the one
     * wanted.
     */
    fun codePageFor(language: String?): Charset {
        val tag = language.orEmpty().trim().lowercase(Locale.ROOT).replace('_', '-')
        val base = tag.substringBefore('-')
        val name = when (base) {
            "zh", "zho", "chi" -> if (TRADITIONAL.any { tag.split('-').contains(it) }) "Big5" else "GBK"
            else -> CODE_PAGES.entries.firstOrNull { (_, codes) -> base in codes }?.key ?: WESTERN
        }
        return runCatching { Charset.forName(name) }.getOrNull()
            ?: runCatching { Charset.forName(WESTERN) }.getOrDefault(Charsets.ISO_8859_1)
    }

    private const val WESTERN = "windows-1252"

    /** How far into a file the UTF-16 test looks: plenty for a few dozen cues. */
    private const val UTF16_SNIFF_BYTES = 4096

    /** What marks a Chinese tag as the traditional script: the script itself, or where it is written. */
    private val TRADITIONAL = setOf("hant", "tw", "hk", "mo")

    /** Windows code pages and the languages (ISO 639-1 and 639-2) that were written in each. */
    private val CODE_PAGES = mapOf(
        "windows-1256" to setOf("ar", "ara", "fa", "fas", "per", "ur", "urd", "ku", "kur", "ps", "pus"),
        "windows-1251" to setOf(
            "ru", "rus", "uk", "ukr", "be", "bel", "bg", "bul", "sr", "srp", "mk", "mkd", "mac",
            "kk", "kaz", "ky", "kir", "tg", "tgk", "mn", "mon",
        ),
        "windows-1250" to setOf(
            "pl", "pol", "cs", "ces", "cze", "sk", "slk", "slo", "hu", "hun", "ro", "ron", "rum",
            "hr", "hrv", "sl", "slv", "bs", "bos", "sq", "sqi", "alb",
        ),
        "windows-1253" to setOf("el", "ell", "gre"),
        "windows-1254" to setOf("tr", "tur", "az", "aze"),
        "windows-1255" to setOf("he", "heb", "iw", "yi", "yid"),
        "windows-1257" to setOf("lt", "lit", "lv", "lav", "et", "est"),
        "windows-1258" to setOf("vi", "vie"),
        "x-windows-874" to setOf("th", "tha"),
        "Shift_JIS" to setOf("ja", "jpn"),
        "EUC-KR" to setOf("ko", "kor"),
    )

    /** The extension of [fileName], in lower case, or srt when it names no format we take. */
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in FORMATS } ?: "srt"

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** Every subtitle entry in a zip, by its file name inside the archive, in the archive's order. */
    fun archiveEntries(bytes: ByteArray): List<String> = readEntries(bytes).map { it.first }

    /** The one named entry of a zip as a subtitle, or null when the archive does not hold it. */
    fun fromArchiveEntry(bytes: ByteArray, entryName: String, language: String? = null): Text? {
        val pick = readEntries(bytes).firstOrNull { it.first == entryName } ?: return null
        return Text(extensionOf(pick.first), decode(pick.second, language), entryName = pick.first)
    }

    /** A zip's subtitle entries, names and bytes, each capped at [MAX_BYTES] like every read here. */
    private fun readEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val found = mutableListOf<Pair<String, ByteArray>>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in FORMATS) continue
                    // Read by hand: InputStream.readNBytes is newer than the oldest Android we run on.
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (out.size() <= MAX_BYTES) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                    }
                    if (out.size() <= MAX_BYTES) found += name to out.toByteArray()
                }
            }
        }
        return found
    }

    /**
     * The subtitle inside a zip: the one whose name has the wanted [season] and [episode] when
     * those are known, else the first. Null when there is no subtitle in a format we take.
     * [strict] (a season pack) never falls back to another episode: when the names number their
     * episodes and none is this one, there is nothing to take.
     */
    fun fromArchive(bytes: ByteArray, season: Int? = null, episode: Int? = null, language: String? = null, strict: Boolean = false): Text? {
        val found = readEntries(bytes)
        if (found.isEmpty()) return null
        val pick = if (episode != null) {
            found.firstOrNull { (name, _) ->
                val parsed = MediaName.parse(name)
                parsed.episode == episode && (season == null || parsed.season == null || parsed.season == season)
            } ?: found.takeUnless { strict && found.size > 1 && it.any { (name, _) -> MediaName.parse(name).episode != null } }?.first()
                ?: return null
        } else {
            found.first()
        }
        return Text(extensionOf(pick.first), decode(pick.second, language), entryName = pick.first)
    }

    /**
     * A downloaded body, zipped or not, as subtitle text; [language] is the subtitle's,
     * for [decode]. [strict] is passed to the archive read: a season pack asked for one
     * episode never answers with another episode's file.
     */
    fun from(bytes: ByteArray, fileName: String, season: Int? = null, episode: Int? = null, language: String? = null, strict: Boolean = false): Text? = when {
        bytes.size > MAX_BYTES -> null
        isZip(bytes) -> runCatching { fromArchive(bytes, season, episode, language, strict) }.getOrNull()
        else -> decode(bytes, language).takeIf { it.isNotBlank() }?.let { Text(extensionOf(fileName), it) }
    }
}
