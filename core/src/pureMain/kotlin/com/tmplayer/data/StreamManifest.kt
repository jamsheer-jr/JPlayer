package com.tmplayer.data

/**
 * What a fetched stream manifest actually is (owner, 2026-11-07). A pasted or posted
 * address can claim to be a stream and be something else entirely: a DRM protected
 * manifest no player here can open, or a web page wearing a stream's name. Telling those
 * apart before the player is handed the address turns a generic format failure into the
 * sentence that names the real problem.
 */
sealed class ManifestKind {

    /** A real DASH or HLS manifest, with no DRM marking found in it. */
    object Manifest : ManifestKind()

    /**
     * A manifest that declares DRM protection. [family] names the system where the
     * manifest names one (Widevine, PlayReady or FairPlay), and is null where the
     * protection is declared without naming a system this reading knows.
     */
    data class Drm(val family: String?) : ManifestKind()

    /** A web page, not a manifest at all. */
    object WebPage : ManifestKind()

    /**
     * Nothing decisive could be read: a truncated, empty or unrecognised answer. The
     * caller proceeds exactly as if no check had run, so a stream that would have played
     * is never blocked by a reading that failed.
     */
    object Unknown : ManifestKind()
}

/**
 * Reads a fetched manifest's text and says what it is ([ManifestKind]). Pure string work
 * over the answer's content type and its body, capped: only the head of the body is ever
 * scanned, no pattern can run away, and any input at all (empty, truncated, random) is
 * answered without throwing.
 */
object StreamManifest {

    /** How much of a body is ever scanned: a manifest declares itself well inside this. */
    private const val MAX_SCAN = 64 * 1024

    /** How many playlist lines are scanned for a key line: the head carries it. */
    private const val MAX_LINES = 400

    /** The Widevine and PlayReady system ids, as a DASH manifest spells them. */
    private const val WIDEVINE_ID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY_ID = "9a04f079-9840-4286-ab92-e65be0885f95"

    /**
     * Classifies one fetched answer: its [contentType] (as the server named it, may be
     * null) and its [body] text (may be null or cut short). A web page is known by its
     * content type or by the markup it opens with; DRM is known by a DASH
     * ContentProtection element or an HLS key line whose method or key format is a DRM
     * system (plain AES-128 HLS encryption is not DRM and reads as a plain manifest);
     * a real manifest is known by its own opening or by a manifest content type.
     */
    fun classify(contentType: String?, body: String?): ManifestKind {
        val type = contentType.orEmpty().lowercase()
        val text = body.orEmpty().take(MAX_SCAN)
        if ("text/html" in type) return ManifestKind.WebPage
        val head = text.trimStart { it == '\uFEFF' || it.isWhitespace() }
        val lowerHead = head.lowercase()
        if (lowerHead.startsWith("<!doctype") || lowerHead.startsWith("<html")) return ManifestKind.WebPage
        if (text.isBlank() && !isManifestType(type)) return ManifestKind.Unknown
        dashDrm(text)?.let { return it }
        hlsDrm(text)?.let { return it }
        if (isManifestType(type) || looksLikeManifest(head)) return ManifestKind.Manifest
        return ManifestKind.Unknown
    }

    /** Whether [type] is a content type only a manifest is served under. */
    private fun isManifestType(type: String): Boolean =
        "dash+xml" in type || "mpegurl" in type

    /** Whether the body's own opening is a DASH manifest's or an HLS playlist's. */
    private fun looksLikeManifest(head: String): Boolean {
        if (head.startsWith("#EXTM3U")) return true
        if (head.startsWith("<?xml") && "<MPD" in head) return true
        if (head.startsWith("<MPD")) return true
        return false
    }

    /**
     * The DRM reading of a DASH body: a ContentProtection element anywhere in the head
     * means the stream is encrypted under some system, and the element (or the text
     * around it) usually names which. Returns null where no protection is declared.
     */
    private fun dashDrm(text: String): ManifestKind.Drm? {
        val lower = text.lowercase()
        if ("contentprotection" !in lower) return null
        val family = when {
            WIDEVINE_ID in lower || "widevine" in lower -> "Widevine"
            PLAYREADY_ID in lower || "playready" in lower || "microsoft" in lower -> "PlayReady"
            else -> null
        }
        return ManifestKind.Drm(family)
    }

    /**
     * The DRM reading of an HLS body: a key line whose method is a DRM method
     * (SAMPLE-AES is Apple's, used by FairPlay) or whose key format names a DRM system.
     * A plain AES-128 key line is ordinary encryption the player handles, not DRM, and
     * a key line with METHOD=NONE declares no key at all. Returns null where no key
     * line declares DRM.
     */
    private fun hlsDrm(text: String): ManifestKind.Drm? {
        var scanned = 0
        for (line in text.lineSequence()) {
            if (scanned >= MAX_LINES) break
            scanned++
            val trimmed = line.trim()
            if (!trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true)) continue
            val lower = trimmed.lowercase()
            val method = keyAttribute(lower, "method") ?: continue
            if (method == "none") continue
            val keyFormat = keyAttribute(lower, "keyformat").orEmpty()
            when {
                "fairplay" in keyFormat || "com.apple" in keyFormat -> return ManifestKind.Drm("FairPlay")
                "widevine" in keyFormat -> return ManifestKind.Drm("Widevine")
                method.startsWith("sample-aes") -> return ManifestKind.Drm("FairPlay")
            }
            // Any other method (AES-128 above all) is plain encryption the player
            // handles, or a shape this reading does not know: not DRM either way, and
            // the scan goes on in case a later key line does declare a DRM system.
        }
        return null
    }

    /**
     * One attribute's value out of a key line, read by hand: the name, an equals sign,
     * then a quoted or bare value. Null where the attribute is absent or malformed.
     */
    private fun keyAttribute(line: String, name: String): String? {
        val at = line.indexOf(name)
        if (at < 0) return null
        var i = at + name.length
        while (i < line.length && line[i] == ' ') i++
        if (i >= line.length || line[i] != '=') return null
        i++
        if (i >= line.length) return null
        return if (line[i] == '"') {
            val end = line.indexOf('"', i + 1)
            if (end < 0) null else line.substring(i + 1, end)
        } else {
            var end = i
            while (end < line.length && line[end] != ',' && line[end] != ' ') end++
            line.substring(i, end).takeIf { it.isNotEmpty() }
        }
    }
}
