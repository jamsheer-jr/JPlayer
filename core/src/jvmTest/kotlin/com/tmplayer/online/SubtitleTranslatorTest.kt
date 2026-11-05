package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Subtitle translation against a fake service: no test here touches the network. The fake
 * answers the way the real keyless endpoint does, in both of its shapes, and translates by
 * marking each cue's words with "T:", so what is checked is the plumbing: the timings and the
 * file's shape survive, the words are replaced cue for cue, and every failure falls back
 * instead of breaking the subtitle.
 */
class SubtitleTranslatorTest {

    private enum class Mode { Marked, Lines, Blob }

    /**
     * A translation service that reads the marked batch it was sent. [Mode] picks how faithful
     * its answer is: markers kept, markers lost but lines kept, or one blob for the whole batch.
     * A request for one cue (no markers) is answered with that cue translated.
     */
    private inner class FakeTranslate(var mode: Mode = Mode.Marked) : HttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun send(request: HttpRequest): HttpResponse {
            urls += request.url
            val asked = URLDecoder.decode(request.url.substringAfter("q="), "UTF-8")
            val translated = when {
                "<<" in asked -> when (mode) {
                    Mode.Marked -> asked.lines().joinToString("\n") { line ->
                        val n = line.substringAfter("<<").substringBefore(">>")
                        "<<$n>> T: ${line.substringAfter(">> ")}"
                    }
                    Mode.Lines -> asked.lines().joinToString("\n") { "T: ${it.substringAfter(">> ")}" }
                    Mode.Blob -> "T: everything at once"
                }
                else -> "T: $asked"
            }
            val body = if ("translate_a/t" in request.url) pairsJson(translated) else segmentsJson(translated)
            return HttpResponse(200, body = body.toByteArray())
        }
    }

    private fun esc(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    /** The segments shape: [[["t","s"],["t","s"]],...], the split falling mid text. */
    private fun segmentsJson(text: String): String {
        val half = text.length / 2
        return "[[[\"${esc(text.take(half))}\",\"a\"],[\"${esc(text.drop(half))}\",\"b\"]],null,\"en\"]"
    }

    /** The pairs shape: [["t","s"],["t","s"]], the split falling mid text. */
    private fun pairsJson(text: String): String {
        val half = text.length / 2
        return "[[\"${esc(text.take(half))}\",\"a\"],[\"${esc(text.drop(half))}\",\"b\"]]"
    }

    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello world.

        2
        00:00:03,000 --> 00:00:04,500
        <i>How are</i>
        you today?

        3
        00:00:05,000 --> 00:00:06,000
        Goodbye.
    """.trimIndent()

    @Test
    fun `srt keeps its timings and takes the translated words`() = runBlocking {
        val http = FakeTranslate()
        val out = SubtitleTranslator(http).translate(SubtitleText.Text("srt", srt), "ml")
        assertNotNull(out)
        out!!
        assertEquals("srt", out.extension)
        assertTrue(out.text.contains("00:00:01,000 --> 00:00:02,500"))
        assertTrue(out.text.contains("00:00:03,000 --> 00:00:04,500"))
        assertTrue(out.text.contains("00:00:05,000 --> 00:00:06,000"))
        assertTrue(out.text.contains("T: Hello world."))
        // A cue of two lines with markup flattens to one plain translated line.
        assertTrue(out.text.contains("T: How are you today?"))
        assertFalse(out.text.contains("<i>"))
        assertTrue(out.text.contains("T: Goodbye."))
        // Asked for Malayalam, at the endpoint the brief names first.
        assertTrue(http.urls.isNotEmpty())
        assertTrue(http.urls.first().contains("translate_a/t"))
        assertTrue(http.urls.first().contains("tl=ml"))
    }

    @Test
    fun `the pairs shape is read as well as the segments shape`() = runBlocking {
        // FakeTranslate answers translate_a/t with pairs and translate_a/single with segments;
        // the first test exercised whichever answered. Here the /t route is the only one that
        // can answer, so pairs must parse on their own.
        val onlyPairs = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                if ("translate_a/single" in request.url) return HttpResponse(500)
                val asked = URLDecoder.decode(request.url.substringAfter("q="), "UTF-8")
                val translated = asked.lines().joinToString("\n") { line ->
                    val n = line.substringAfter("<<").substringBefore(">>")
                    "<<$n>> T: ${line.substringAfter(">> ")}"
                }
                return HttpResponse(200, body = pairsJson(translated).toByteArray())
            }
        }
        val out = SubtitleTranslator(onlyPairs).translate(SubtitleText.Text("srt", srt), "ml")
        assertNotNull(out)
        assertTrue(out!!.text.contains("T: Hello world."))
    }

    @Test
    fun `markers lost but lines kept still maps cue for cue`() = runBlocking {
        val out = SubtitleTranslator(FakeTranslate(Mode.Lines)).translate(SubtitleText.Text("srt", srt), "ml")
        assertNotNull(out)
        assertTrue(out!!.text.contains("T: Hello world."))
        assertTrue(out.text.contains("T: How are you today?"))
        assertTrue(out.text.contains("T: Goodbye."))
    }

    @Test
    fun `a blob answer falls back to asking cue by cue`() = runBlocking {
        val out = SubtitleTranslator(FakeTranslate(Mode.Blob)).translate(SubtitleText.Text("srt", srt), "ml")
        assertNotNull(out)
        assertTrue(out!!.text.contains("T: Hello world."))
        assertTrue(out.text.contains("T: Goodbye."))
    }

    @Test
    fun `a service that cannot be reached gives null, never a broken file`() = runBlocking {
        val down = HttpTransport { throw java.io.IOException("offline") }
        assertNull(SubtitleTranslator(down).translate(SubtitleText.Text("srt", srt), "ml"))
        val refused = HttpTransport { HttpResponse(429) }
        assertNull(SubtitleTranslator(refused).translate(SubtitleText.Text("srt", srt), "ml"))
        val nonsense = HttpTransport { HttpResponse(200, body = "not json".toByteArray()) }
        assertNull(SubtitleTranslator(nonsense).translate(SubtitleText.Text("srt", srt), "ml"))
    }

    @Test
    fun `webvtt keeps its header and cue settings`() = runBlocking {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.500 align:center
            Hello world.
        """.trimIndent()
        val out = SubtitleTranslator(FakeTranslate()).translate(SubtitleText.Text("vtt", vtt), "ml")
        assertNotNull(out)
        assertTrue(out!!.text.startsWith("WEBVTT"))
        assertTrue(out.text.contains("00:00:01.000 --> 00:00:02.500 align:center"))
        assertTrue(out.text.contains("T: Hello world."))
    }

    @Test
    fun `ssa dialogue is translated in place and tagged lines are kept`() = runBlocking {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,Hello world.
            Dialogue: 0,0:00:03.00,0:00:04.50,Default,,0,0,0,,{\an8}Keep me as I am.
        """.trimIndent()
        val out = SubtitleTranslator(FakeTranslate()).translate(SubtitleText.Text("ass", ass), "ml")
        assertNotNull(out)
        assertTrue(out!!.text.contains("0:00:01.00,0:00:02.50"))
        assertTrue(out.text.contains("T: Hello world."))
        assertTrue(out.text.contains("{\\an8}Keep me as I am."))
    }

    @Test
    fun `a file with no words gives null`() = runBlocking {
        val timingsOnly = "1\n00:00:01,000 --> 00:00:02,000\n\n"
        assertNull(SubtitleTranslator(FakeTranslate()).translate(SubtitleText.Text("srt", timingsOnly), "ml"))
    }

    @Test
    fun `splitMarked reads wrapped cues and leaves missing ones null`() {
        val answer = "<<1>> one\n<<2>> two\nwrapped\n<<4>> four"
        assertEquals(listOf("one", "two wrapped", null, "four"), SubtitleTranslator.splitMarked(answer, 4))
        assertEquals(listOf(null, null), SubtitleTranslator.splitMarked("no markers at all", 2))
        assertEquals(emptyList<String?>(), SubtitleTranslator.splitMarked("<<1>> x", 0))
    }

    @Test
    fun `parseAnswer refuses what is not an answer`() {
        assertNull(SubtitleTranslator.parseAnswer("hello"))
        assertNull(SubtitleTranslator.parseAnswer("[]"))
        assertNull(SubtitleTranslator.parseAnswer("[[]]"))
        assertEquals("Hola", SubtitleTranslator.parseAnswer("""[[["Hola","Hello"]],null,"en"]"""))
        assertEquals("Hola", SubtitleTranslator.parseAnswer("""[["Hola","Hello"]]"""))
    }

    @Test
    fun `malayalam is offered first and languages compare by base code`() {
        assertEquals("ml", SubtitleTranslator.LANGUAGES.first())
        assertTrue(SubtitleTranslator.sameLanguage("pt-BR", "pt"))
        assertTrue(SubtitleTranslator.sameLanguage("ml", "ml"))
        assertFalse(SubtitleTranslator.sameLanguage("ml", "ta"))
        assertFalse(SubtitleTranslator.sameLanguage(null, "ml"))
        assertFalse(SubtitleTranslator.sameLanguage("", ""))
    }
}
