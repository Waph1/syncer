package io.github.waph1.syncer.youtube

import io.github.waph1.syncer.settings.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpTest {

    @Test
    fun playlistIdFromLinksAndIds() {
        assertEquals("PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0", YtDlp.playlistIdFrom("https://www.youtube.com/playlist?list=PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0"))
        assertEquals("PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0", YtDlp.playlistIdFrom(" https://youtube.com/playlist?list=PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0&si=Ab12_x "))
        assertEquals("PLx", YtDlp.playlistIdFrom("https://www.youtube.com/watch?v=RuLkBVIvEUs&list=PLx&index=2"))
        assertEquals("OLAK5uy_abc", YtDlp.playlistIdFrom("https://music.youtube.com/playlist?list=OLAK5uy_abc"))
        assertEquals("WL", YtDlp.playlistIdFrom("https://www.youtube.com/playlist?list=WL"))
        assertEquals("PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0", YtDlp.playlistIdFrom("PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0"))
        assertNull(YtDlp.playlistIdFrom("https://www.youtube.com/watch?v=RuLkBVIvEUs"))
        assertNull(YtDlp.playlistIdFrom("ciao"))
        assertNull(YtDlp.playlistIdFrom(""))
    }

    @Test
    fun formatSelectionPerQuality() {
        assertEquals(
            listOf("-f", "bv*+ba/b", "-S", "res:1080,+codec:avc:m4a", "--merge-output-format", "mp4"),
            YtDlp.formatArgs(VideoQuality.P1080),
        )
        assertEquals("res,+codec:avc:m4a", YtDlp.formatArgs(VideoQuality.BEST)[3])
        assertEquals("res:2160,+codec:avc:m4a", YtDlp.formatArgs(VideoQuality.P2160)[3])
        assertEquals(listOf("-f", "ba[ext=m4a]/ba", "-x", "--audio-format", "m4a"), YtDlp.formatArgs(VideoQuality.AUDIO_M4A))
        assertEquals(listOf("-f", "ba", "-x", "--audio-format", "mp3", "--audio-quality", "0"), YtDlp.formatArgs(VideoQuality.AUDIO_MP3))
    }

    @Test
    fun downloadAndListArguments() {
        val args = YtDlp.downloadArgs(VideoQuality.P720, "/cache/youtube/abc", "/files/c.txt")
        assertTrue(args.containsAll(listOf("--no-playlist", "-o", "/cache/youtube/abc/%(id)s.%(ext)s", "--cookies", "/files/c.txt")))
        assertEquals("res:720,+codec:avc:m4a", args[args.indexOf("-S") + 1])
        assertFalse("--cookies" in YtDlp.downloadArgs(VideoQuality.P720, "/d", null))
        assertEquals(listOf("--flat-playlist", "--dump-single-json"), YtDlp.listArgs(null))
    }

    @Test
    fun parsesFlatPlaylist() {
        // Trimmed real output of `yt-dlp --flat-playlist -J`, plus a private video.
        val output = """
            [youtube:tab] Extracting URL
            {"id": "PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0", "title": "Blender Studio Logs", "_type": "playlist", "playlist_count": 31, "availability": "public", "entries": [{"_type": "url", "ie_key": "Youtube", "id": "RuLkBVIvEUs", "url": "https://www.youtube.com/watch?v=RuLkBVIvEUs", "title": "Overgrown 🤝 Cowboi - Blender Studio Log 31", "duration": 870, "channel": "Blender Studio", "availability": null, "live_status": null}, {"_type": "url", "ie_key": "Youtube", "id": "_KjTlL5HBdc", "url": "https://www.youtube.com/watch?v=_KjTlL5HBdc", "title": "Enter Pablicoverse - Blender Studio Log 30", "duration": 632, "channel": "Blender Studio", "availability": null, "live_status": null}, {"_type": "url", "id": "xxxxxxxxxxx", "title": "[Private video]", "duration": null}, {"_type": "url", "id": "RuLkBVIvEUs", "title": "duplicate"}]}
        """.trimIndent()
        val listing = YtDlp.parseListing(output)
        assertEquals("PLav47HAVZMjkxzzLDqYpTCosbsWjosUN0", listing.id)
        assertEquals("Blender Studio Logs", listing.title)
        assertEquals(listOf("RuLkBVIvEUs", "_KjTlL5HBdc", "xxxxxxxxxxx"), listing.entries.map { it.id })
        assertEquals("Overgrown 🤝 Cowboi - Blender Studio Log 31", listing.entries[0].title)
        assertEquals(listOf(true, true, false), listing.entries.map { it.available })
    }

    @Test
    fun parsesAccountPlaylists() {
        val output = """{"id": "playlists", "title": "Playlists", "entries": [
            {"_type": "url", "ie_key": "YoutubeTab", "id": "PLaaa", "url": "https://www.youtube.com/playlist?list=PLaaa", "title": "Musica"},
            {"_type": "url", "ie_key": "YoutubeTab", "id": "LL", "url": "https://www.youtube.com/playlist?list=LL", "title": "Video piaciuti"},
            {"_type": "url", "ie_key": "Youtube", "id": "RuLkBVIvEUs", "url": "https://www.youtube.com/watch?v=RuLkBVIvEUs", "title": "Not a playlist"}
        ]}""".replace("\n", " ")
        assertEquals(listOf(PlaylistInfo("PLaaa", "Musica"), PlaylistInfo("LL", "Video piaciuti")), YtDlp.parsePlaylists(output))
    }

    @Test
    fun describesCommonErrors() {
        val bot = YtDlp.describeError("WARNING: x\nERROR: [youtube] RuLkBVIvEUs: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.")
        assertTrue(bot.needsSignIn)
        assertTrue(bot.message!!.contains("bot"))
        assertTrue(YtDlp.describeError("ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users.").needsSignIn)
        assertTrue(YtDlp.describeError("ERROR: unable to download webpage: HTTP Error 429: Too Many Requests").rateLimited)
        assertTrue(YtDlp.describeError("ERROR: [youtube:tab] WL: The playlist does not exist.").needsSignIn)
        assertEquals("Video riservato agli abbonati del canale", YtDlp.describeError("ERROR: [youtube] abc: Join this channel to get access to members-only content").message)
        assertEquals("yt-dlp: qualcosa di strano", YtDlp.describeError("ERROR: qualcosa di strano").message)
    }

    @Test(expected = VideoException::class)
    fun rejectsNonJsonOutput() {
        YtDlp.parseListing("ERROR: nope")
    }
}
