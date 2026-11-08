package com.tmplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Joined split sets that turned out to be archives wearing a video's name (owner,
 * 2026-11-08): a set's name can deny being an archive in every spelling, so the last
 * word belongs to the file's own first bytes. When a set's first piece is opened and
 * its head carries a zip, 7z or RAR signature, the set is not a video at all: its key
 * is kept here, and every copies list leaves it out ([VideoCopies] asks), the way a
 * name read as an archive volume is never listed. A set is only ever condemned by its
 * own bytes, never by its name, and the verdict is kept on the device so it is read
 * once, not once per listing.
 */
object ArchiveHeads {

    const val MAX_KEPT = 500

    private val state = MutableStateFlow<Set<String>>(emptySet())

    /** The proven archive sets' keys, as the store last read them: what the lists read. */
    val proven: StateFlow<Set<String>> = state.asStateFlow()

    /** Keeps [proven] in step with the store, once per process (the Android App). */
    fun follow(keys: Set<String>) {
        state.value = keys
    }

    /**
     * The key a joined split set is condemned under: its chat and the name its pieces
     * share. Null for anything that is not a joined set: a whole file is never
     * condemned by its head, only a set whose pieces were folded into one standing
     * video, which is the shape that can hide an archive.
     */
    fun keyOf(item: MediaItem): String? {
        if (item.pieces.isEmpty()) return null
        return "${item.chatId}|${SplitPieces.keyOf(SplitPieces.baseOf(item.fileName))}"
    }

    /** Whether [item] is a joined set already proven an archive by its own bytes. */
    fun hides(item: MediaItem, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return false
        val key = keyOf(item) ?: return false
        return key in keys
    }

    /** The stored set with [key] in it, oldest dropped past the cap. */
    fun plus(keys: Set<String>, key: String): Set<String> =
        (keys + key).let { if (it.size > MAX_KEPT) it.drop(it.size - MAX_KEPT).toSet() else it }

    fun decode(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_KEPT) break
            val key = line.trim()
            if (key.contains('|') && key.substringBefore('|').toLongOrNull() != null) out += key
        }
        return out
    }

    fun encode(keys: Collection<String>): String =
        keys.distinct().take(MAX_KEPT).joinToString("\n")

    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_SPANNED = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    private val SEVEN_Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val RAR = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

    /**
     * Whether [bytes], the first bytes of a file, are an archive's signature: a zip
     * (an ordinary, empty or spanned one), a 7z, or a RAR of either format. Anything
     * shorter than a signature, and every video container's own head (an MKV's EBML,
     * an MP4's ftyp), is not one.
     */
    fun isArchive(bytes: ByteArray): Boolean =
        startsWith(bytes, ZIP) || startsWith(bytes, ZIP_EMPTY) || startsWith(bytes, ZIP_SPANNED) ||
            startsWith(bytes, SEVEN_Z) || startsWith(bytes, RAR)

    private fun startsWith(bytes: ByteArray, signature: ByteArray): Boolean {
        if (bytes.size < signature.size) return false
        for (i in signature.indices) if (bytes[i] != signature[i]) return false
        return true
    }
}
