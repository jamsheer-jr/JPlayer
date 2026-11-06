package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream honesty reading (owner, 2026-11-07): a fetched manifest is told apart as
 * DRM, a web page, a real manifest, or nothing decisive, from its content type and text
 * alone, and garbage is answered without throwing.
 */
class StreamManifestTest {

    private val dashWidevine = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD type="static" mediaPresentationDuration="PT1H">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" value="Widevine"/>
              <Representation id="v1" bandwidth="800000"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private val dashPlayReady = """
        <?xml version="1.0"?>
        <MPD type="static">
          <Period><AdaptationSet>
            <ContentProtection schemeIdUri="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" value="MSPR 2.0"/>
          </AdaptationSet></Period>
        </MPD>
    """.trimIndent()

    private val dashPlain = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD type="static" mediaPresentationDuration="PT30M">
          <Period><AdaptationSet mimeType="video/mp4">
            <Representation id="v1" bandwidth="800000"/>
          </AdaptationSet></Period>
        </MPD>
    """.trimIndent()

    private val hlsFairPlay = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
        #EXT-X-STREAM-INF:BANDWIDTH=800000
        variant.m3u8
    """.trimIndent()

    private val hlsSampleAesNoFormat = """
        #EXTM3U
        #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key"
        #EXTINF:10,
        seg1.ts
    """.trimIndent()

    private val hlsPlain = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXTINF:10,
        seg1.ts
    """.trimIndent()

    private val hlsAes128 = """
        #EXTM3U
        #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example.com/key.bin"
        #EXTINF:10,
        seg1.ts
    """.trimIndent()

    @Test
    fun aDashManifestWithWidevineProtectionIsDrm() {
        assertEquals(ManifestKind.Drm("Widevine"), StreamManifest.classify("application/dash+xml", dashWidevine))
    }

    @Test
    fun aDashManifestWithPlayReadyProtectionIsDrm() {
        assertEquals(ManifestKind.Drm("PlayReady"), StreamManifest.classify("application/dash+xml", dashPlayReady))
    }

    @Test
    fun aDashManifestWithUnnamedProtectionIsDrmWithoutAFamily() {
        val body = dashPlain.replace("<Representation", "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/><Representation")
        val kind = StreamManifest.classify("application/dash+xml", body)
        assertTrue(kind is ManifestKind.Drm)
        assertNull((kind as ManifestKind.Drm).family)
    }

    @Test
    fun anHlsPlaylistWithAFairPlayKeyLineIsDrm() {
        assertEquals(ManifestKind.Drm("FairPlay"), StreamManifest.classify("application/vnd.apple.mpegurl", hlsFairPlay))
    }

    @Test
    fun anHlsPlaylistWithASampleAesKeyLineIsDrm() {
        val kind = StreamManifest.classify("application/x-mpegURL", hlsSampleAesNoFormat)
        assertTrue(kind is ManifestKind.Drm)
    }

    @Test
    fun aPlainPlaylistAndAPlainMpdAreManifests() {
        assertEquals(ManifestKind.Manifest, StreamManifest.classify("application/vnd.apple.mpegurl", hlsPlain))
        assertEquals(ManifestKind.Manifest, StreamManifest.classify("application/dash+xml", dashPlain))
        assertEquals(ManifestKind.Manifest, StreamManifest.classify(null, hlsPlain))
        assertEquals(ManifestKind.Manifest, StreamManifest.classify(null, dashPlain))
    }

    @Test
    fun plainAes128EncryptionIsNotDrm() {
        assertEquals(ManifestKind.Manifest, StreamManifest.classify("application/vnd.apple.mpegurl", hlsAes128))
    }

    @Test
    fun anHtmlAnswerIsAWebPage() {
        assertEquals(ManifestKind.WebPage, StreamManifest.classify("text/html; charset=utf-8", dashPlain))
        assertEquals(ManifestKind.WebPage, StreamManifest.classify("application/octet-stream", "<!DOCTYPE html><html><body>Watch here</body></html>"))
        assertEquals(ManifestKind.WebPage, StreamManifest.classify(null, "  <html lang=\"en\"><head><title>x</title>"))
    }

    @Test
    fun truncatedAndRandomTextAreUnknownWithoutThrowing() {
        assertEquals(ManifestKind.Unknown, StreamManifest.classify(null, null))
        assertEquals(ManifestKind.Unknown, StreamManifest.classify("", ""))
        assertEquals(ManifestKind.Unknown, StreamManifest.classify("application/octet-stream", ""))
        assertEquals(ManifestKind.Unknown, StreamManifest.classify(null, "just some prose, no stream here"))
        assertEquals(ManifestKind.Unknown, StreamManifest.classify(null, "<?xml version=\"1.0\"?><MP"))
        assertEquals(ManifestKind.Unknown, StreamManifest.classify(null, "#EXT-X-KEY:METHOD="))
        assertEquals(ManifestKind.Unknown, StreamManifest.classify(null, "xk39!@# zzz ".repeat(200)))
    }

    @Test
    fun aManifestContentTypeAloneStillReadsAsAManifest() {
        assertEquals(ManifestKind.Manifest, StreamManifest.classify("application/dash+xml", ""))
        assertEquals(ManifestKind.Manifest, StreamManifest.classify("application/vnd.apple.mpegurl", "cut off before the first line fin"))
    }
}
