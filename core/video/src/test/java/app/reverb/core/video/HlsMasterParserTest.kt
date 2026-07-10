package app.reverb.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsMasterParserTest {

    @Test
    fun `parses master playlist with multiple variants`() {
        val masterUrl = "https://hls.example.com/master.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",FRAME-RATE=24.000
            1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2",FRAME-RATE=24.000
            720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480,CODECS="avc1.64001e,mp4a.40.2",FRAME-RATE=24.000
            480p.m3u8
        """.trimIndent()

        val master = HlsMasterParser.parse(masterUrl, playlist)
        assertEquals(3, master.variants.size)
        // Sorted by resolution descending — 1080p first.
        assertEquals(1080, master.variants[0].height)
        assertEquals("1920x1080", master.variants[0].resolution)
        assertEquals(5000000L, master.variants[0].bandwidth)
        assertTrue(master.variants[0].codecs!!.contains("avc1.640028"))
        // URLs should be resolved against the master URL.
        assertEquals("https://hls.example.com/1080p.m3u8", master.variants[0].url)
        assertEquals("https://hls.example.com/720p.m3u8", master.variants[1].url)
    }

    @Test
    fun `parses audio and subtitle media tracks`() {
        val masterUrl = "https://hls.example.com/master.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Japanese",LANGUAGE="ja",DEFAULT=NO,URI="ja.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="en.vtt.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO="aac",SUBTITLES="subs"
            720p.m3u8
        """.trimIndent()

        val master = HlsMasterParser.parse(masterUrl, playlist)
        assertEquals(2, master.audioTracks.size)
        assertEquals(1, master.subtitleTracks.size)
        assertEquals("English", master.audioTracks[0].name)
        assertEquals("en", master.audioTracks[0].language)
        assertTrue(master.audioTracks[0].default)
        assertEquals("https://hls.example.com/en.m3u8", master.audioTracks[0].url)
        assertEquals("Japanese", master.audioTracks[1].name)
        assertEquals("ja", master.audioTracks[1].language)
    }

    @Test
    fun `quality labels are human-readable`() {
        val master = HlsMasterParser.HlsMaster(
            variants = listOf(
                HlsMasterParser.Variant("u1", 5000000L, "1920x1080", 1920, 1080, "avc1", 24.0),
                HlsMasterParser.Variant("u2", 1000000L, "854x480", 854, 480, "avc1", 24.0),
            ),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
        )
        val labels = HlsMasterParser.toQualityLabels(master)
        assertEquals(listOf("1080p", "480p"), labels)
    }
}
