package com.tmplayer.online

import org.json.JSONArray
import java.net.URLEncoder

/**
 * A subtitle carried into another language, at the text level, before it is attached or cached.
 *
 * The cue timings are never touched: the file is taken apart into the lines that carry timings
 * (kept word for word) and the lines that carry words, only the words are sent for translation,
 * and the file is put back together in the same shape, in the same format, with the translated
 * words in place of the original ones. The original text is never changed: the caller writes the
 * result as a new file and keeps the original beside it.
 *
 * Translation is Google Translate's keyless endpoint (`translate_a/t` with `client=gtx`, the
 * address the translate site itself uses without an account; `translate_a/single` is the same
 * service in its other shape and is asked when the first answer cannot be read). No key, no
 * account. It is a free endpoint with no promises: it can be busy, refuse, or answer in a shape
 * that is not recognised, and every one of those is a null here, which the caller reads as "use
 * the subtitle as it came". A cue the service drops or mangles keeps its original words: a cue
 * is never lost, and a file is never left half translated in a way that loses lines.
 *
 * Cues are translated in batches to keep the request count sane on a feature length file, and
 * a batch is one continuous text, not a pile of fragments: each cue's words go up behind a
 * numbered marker (`<<1>>`, `<<2>>`, ...), so the engine sees the run of dialogue whole and
 * keeps the context across cues, the way the whole-file subtitle translator tools the viewer
 * already trusts do it. The answer is split back into cues on the markers, which the engine
 * carries through untouched in practice. A batch whose markers do not survive falls back to
 * one line per cue, and a batch that fails both ways is retried cue by cue; a cue that still
 * fails keeps its original words.
 *
 * Words are flattened before they are sent: line breaks inside a cue become spaces, and
 * SubRip and WebVTT markup (`<i>` and friends) is dropped, so a translated cue is one plain
 * line. SubStation Alpha dialogue carrying override tags (`{\an8}` and the like) is left
 * untranslated rather than risk the tags: most dialogue carries none.
 */
class SubtitleTranslator(private val http: HttpTransport = UrlConnectionTransport) {

    /**
     * [text] translated into [target] (an ISO code such as "ml"), or null when nothing could be
     * translated: the service unreachable, its answer unreadable, or a file with no words in it.
     */
    suspend fun translate(text: SubtitleText.Text, target: String): SubtitleText.Text? {
        val parsed = when (text.extension) {
            "ass", "ssa" -> parseSsa(text.text)
            else -> parseTimed(text.text)
        }
        if (parsed.cues.isEmpty()) return null
        val translated = translateCues(parsed.cues.map { it.words }, target)
        if (translated.none { it != null }) return null
        return SubtitleText.Text(text.extension, parsed.rebuild(translated))
    }

    /**
     * Loose lines of subtitle words translated into [target], one answer (or null) per line,
     * in the caller's order: [translate] with the file taken apart by somebody else, which is
     * what a player showing a track built into the video has, since that track never exists as
     * a file anywhere. The lines go up together, so the engine sees the run of them whole; a
     * line it drops or mangles answers null, and that line keeps its own words.
     *
     * The live path takes no per-line fallback (v1.0.36): when a joined batch's answer cannot
     * be read back line by line, a file can afford a call per line, but a line on screen
     * cannot wait for a call per line of its batch (the owner's missed lines: whole runs of
     * dialogue went by while one batch walked its lines one call at a time). The batch
     * answers nulls instead, and the caller's one retry of the batch is the second chance.
     */
    suspend fun translateLines(lines: List<String>, target: String): List<String?> {
        if (lines.isEmpty()) return emptyList()
        return translateCues(lines, target, perLineFallback = false)
    }

    /** One cue's place in the file: [words] is what is sent, [write] puts the answer back. */
    private class Cue(val words: String, val write: (String) -> Unit)

    /** A file taken apart: its cues, and how to rebuild it from one answer (or null) per cue. */
    private class Parsed(val cues: List<Cue>, val rebuild: (List<String?>) -> String)

    /**
     * SubRip and WebVTT: a cue is the lines after a timing line (one holding " --> ") up to the
     * blank line. Everything else, the WebVTT header, cue numbers, identifiers, NOTE blocks, is
     * carried through word for word.
     */
    private fun parseTimed(text: String): Parsed {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val bodies = mutableMapOf<Int, IntRange>()
        var at = 0
        while (at < lines.size) {
            if (lines[at].contains(TIMING)) {
                var end = at + 1
                while (end < lines.size && lines[end].isNotBlank()) end++
                if (end > at + 1) bodies[at] = (at + 1) until end
                at = end
            } else {
                at++
            }
        }
        val replaced = HashMap<Int, String>()
        val cues = bodies.entries.sortedBy { it.key }.mapNotNull { (timing, range) ->
            val words = flatten(range.map { lines[it] })
            if (words.isEmpty()) {
                null
            } else {
                Cue(words) { answer -> replaced[timing] = answer }
            }
        }
        return Parsed(cues) { answers ->
            cues.forEachIndexed { index, cue -> answers.getOrNull(index)?.let(cue.write) }
            val out = StringBuilder()
            var i = 0
            while (i < lines.size) {
                out.append(lines[i])
                val range = bodies[i]
                val answer = replaced[i]
                if (range != null && answer != null) {
                    // The cue's own lines are replaced by the one translated line; the timing
                    // line above them has already been written.
                    out.append('\n').append(answer)
                    i = range.last + 1
                } else {
                    i++
                }
                if (i <= lines.size) out.append('\n')
            }
            // The split leaves a last empty piece for a file ending in a line break; writing one
            // break per line rebuilds it, and one break too many at the end harms nothing.
            out.toString()
        }
    }

    /**
     * SubStation Alpha: a cue is a `Dialogue:` line, whose words are the field after the ninth
     * comma. Timings live inside the line, ahead of the words, so replacing the words leaves
     * them untouched by construction.
     */
    private fun parseSsa(text: String): Parsed {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val replaced = HashMap<Int, String>()
        val cues = mutableListOf<Cue>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("Dialogue:")) return@forEachIndexed
            val parts = line.split(",", limit = SSA_FIELDS + 1)
            if (parts.size <= SSA_FIELDS) return@forEachIndexed
            val words = parts[SSA_FIELDS]
            // Override tags position or style the line; a translated answer cannot carry them
            // safely, so a line that has them keeps its own words.
            if (words.contains('{')) return@forEachIndexed
            val flat = words.replace("\\N", " ").replace("\\n", " ").trim()
            if (flat.isEmpty()) return@forEachIndexed
            cues += Cue(flat) { answer -> replaced[index] = answer }
        }
        return Parsed(cues) { answers ->
            cues.forEachIndexed { index, cue -> answers.getOrNull(index)?.let(cue.write) }
            lines.mapIndexed { index, line ->
                val answer = replaced[index] ?: return@mapIndexed line
                val parts = line.split(",", limit = SSA_FIELDS + 1)
                (parts.take(SSA_FIELDS) + answer).joinToString(",")
            }.joinToString("\n")
        }
    }

    /** A cue's words as one line worth sending: markup dropped, line breaks as spaces. */
    private fun flatten(lines: List<String>): String =
        lines.joinToString(" ") { it.replace(TAG, "") }.replace(WHITESPACE, " ").trim()

    /**
     * One answer (or null) per text in [texts]: batches with numbered markers first (see the
     * class comment), then one line per cue when the markers were lost, then cue by cue. When
     * the first batch yields nothing at all, the service is taken to be unreachable and the rest
     * are not asked: a feature length file would otherwise be a thousand refused requests.
     */
    private suspend fun translateCues(texts: List<String>, target: String, perLineFallback: Boolean = true): List<String?> {
        val out = arrayOfNulls<String>(texts.size)
        var at = 0
        var batches = 0
        while (at < texts.size) {
            var end = at
            var size = 0
            while (end < texts.size && end - at < MAX_BATCH_CUES) {
                val length = texts[end].length + MARKER_ROOM
                if (end > at && size + length > MAX_BATCH_CHARS) break
                size += length
                end++
            }
            val batch = texts.subList(at, end)
            val marked = batch.mapIndexed { i, words -> "<<${i + 1}>> $words" }.joinToString("\n")
            val answer = translateJoined(marked, target)
            val byMarker = answer?.let { splitMarked(it, batch.size) }
            val lines = answer?.split("\n")
            when {
                byMarker != null && byMarker.any { it != null } ->
                    batch.indices.forEach { i -> out[at + i] = byMarker[i] }
                lines != null && lines.size == batch.size ->
                    batch.indices.forEach { i -> out[at + i] = lines[i].trim().takeIf { it.isNotEmpty() } }
                perLineFallback -> batch.indices.forEach { i ->
                    out[at + i] = translateJoined(batch[i], target)
                        ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
                }
                else -> Unit
            }
            batches++
            if (batches == 1 && out.all { it == null }) return out.toList()
            at = end
        }
        return out.toList()
    }

    /** One joined text to the service, in whichever of its two shapes answers readably. */
    private suspend fun translateJoined(joined: String, target: String): String? {
        for (endpoint in ENDPOINTS) {
            val answer = runCatching {
                http.send(
                    HttpRequest(
                        method = "GET",
                        url = url(endpoint, joined, target),
                        headers = mapOf("User-Agent" to USER_AGENT),
                    ),
                )
            }.getOrNull() ?: continue
            if (answer.code !in 200..299) continue
            val body = runCatching { answer.text }.getOrNull() ?: continue
            parseAnswer(body)?.let { return it }
        }
        return null
    }

    private fun url(endpoint: String, text: String, target: String): String =
        endpoint +
            "?client=gtx&sl=auto&tl=" + URLEncoder.encode(target, "UTF-8") +
            "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")

    companion object {

        /** The languages the picker offers, by ISO code: the viewer's own first. */
        val LANGUAGES = listOf(
            "ml", "en", "hi", "ta", "te", "kn", "bn", "ar", "ur",
            "es", "fr", "de", "ru", "pt", "id", "tr",
        )

        /** Whether two language tags name the same language, by their base code ("pt-BR" is "pt"). */
        fun sameLanguage(a: String?, b: String?): Boolean {
            val x = a?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
            val y = b?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
            return x.isNotEmpty() && x == y
        }

        /**
         * A marked batch's answer back into one entry per cue, or nulls where the cue's marker
         * never came back. Marker lines open a cue; any other line continues the cue above it,
         * joined with a space, so an answer that wrapped a cue over two lines still lands in
         * one cue. Words before the first marker are the engine's own and are dropped. A
         * repeated marker for the cue being read adds only its words.
         */
        internal fun splitMarked(answer: String, count: Int): List<String?> {
            val out = arrayOfNulls<String>(count)
            var current = -1
            val words = StringBuilder()
            fun flush() {
                if (current >= 0) out[current] = words.toString().trim().takeIf { it.isNotEmpty() }
                words.clear()
            }
            for (raw in answer.split("\n")) {
                val line = raw.trim()
                val marker = MARKER.matchEntire(line)
                val n = marker?.groupValues?.get(1)?.toIntOrNull()
                when {
                    n != null && n in 1..count && n - 1 == current -> {
                        if (words.isNotEmpty()) words.append(' ')
                        words.append(marker.groupValues[2])
                    }
                    n != null && n in 1..count && out[n - 1] == null -> {
                        flush()
                        current = n - 1
                        words.append(marker.groupValues[2])
                    }
                    current >= 0 && line.isNotEmpty() -> {
                        if (words.isNotEmpty()) words.append(' ')
                        words.append(line)
                    }
                }
            }
            flush()
            return out.toList()
        }

        /**
         * The translated text out of a `translate_a` answer, or null when the answer is not one.
         *
         * The service answers in two shapes: segments, `[[["translated","source",...],...],...]`,
         * whose first element is a list of segment lists, and pairs, `[["translated","source"],
         * ...]`, whose first element is one pair. Both are read; anything else is refused
         * rather than guessed at (HX10).
         */
        internal fun parseAnswer(body: String): String? = runCatching {
            val root = JSONArray(body)
            val head = root.optJSONArray(0) ?: return null
            val out = StringBuilder()
            if (head.optJSONArray(0) != null) {
                for (i in 0 until head.length()) {
                    val segment = head.optJSONArray(i) ?: continue
                    out.append(segment.optString(0))
                }
            } else {
                for (i in 0 until root.length()) {
                    val pair = root.optJSONArray(i) ?: continue
                    out.append(pair.optString(0))
                }
            }
            out.toString().takeIf { it.isNotBlank() }
        }.getOrNull()

        private const val TIMING = " --> "

        /** The fields ahead of a SubStation Alpha dialogue's words. */
        private const val SSA_FIELDS = 9

        private const val MAX_BATCH_CUES = 40
        private const val MAX_BATCH_CHARS = 3000

        /** Room allowed per cue for its marker (`<<12>> `) when a batch is measured. */
        private const val MARKER_ROOM = 8

        /** A batch marker at the start of a line, its number and the cue's words after it. */
        private val MARKER = Regex("""^<<\s*(\d+)\s*>>\s*(.*)$""")

        /** The service in its two keyless shapes, the one the brief names first. */
        private val ENDPOINTS = listOf(
            "https://translate.googleapis.com/translate_a/t",
            "https://translate.googleapis.com/translate_a/single",
        )

        /**
         * The service turns away callers that name themselves as a library or a runtime, so the
         * request carries the user agent of an ordinary phone browser.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private val TAG = Regex("""<[^>]*>""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
