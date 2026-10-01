package io.github.waph1.syncer.youtube

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.PlaylistTarget
import io.github.waph1.syncer.settings.VideoQuality
import io.github.waph1.syncer.settings.YouTubeSettings
import io.github.waph1.syncer.sync.PlaylistStatus
import io.github.waph1.syncer.sync.Problem
import io.github.waph1.syncer.testing.FakeDocumentsProvider
import io.github.waph1.syncer.testing.FakeVideoBackend
import io.github.waph1.syncer.testing.TestSyncerApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

/** One-way playlist mirror through SafFolder (directory-backed provider) with a fake yt-dlp. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncerApp::class)
class PlaylistSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val container get() = app.appContainer
    private val backend get() = FakeVideoBackend.current

    @Before
    fun setUp() {
        FakeDocumentsProvider.root = tmp.newFolder("storage")
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
    }

    private fun configure(vararg playlists: PlaylistTarget, wifiOnly: Boolean = false) {
        container.settings.replace(
            AppSettings(
                setupCompleted = true,
                accountName = "me@gmail.com",
                periodicSyncEnabled = false,
                syncOnChange = false,
                youtube = YouTubeSettings(enabled = true, playlists = playlists.toList(), wifiOnly = wifiOnly),
            ),
        )
    }

    private fun playlist(id: String, folder: String, quality: VideoQuality = VideoQuality.P720) =
        PlaylistTarget(id, "Playlist $id", FakeDocumentsProvider.treeUri(folder).toString(), quality)

    private fun listing(id: String, vararg videos: VideoEntry) {
        backend.playlists[id] = PlaylistListing(id, "Titolo $id", videos.toList())
    }

    private fun sync(engine: PlaylistSync = container.playlists) = runBlocking { engine.run() }

    private fun status(id: String): PlaylistStatus = container.status.current.playlists.getValue(id)

    private fun files(folder: String): Set<String> =
        File(FakeDocumentsProvider.root, folder).list().orEmpty().toSet()

    @Test
    fun mirrorsAdditionsAndRemovals() {
        configure(playlist("PLa", "musica"))
        File(FakeDocumentsProvider.root, "musica/mio.txt").writeText("mio")
        File(FakeDocumentsProvider.root, "musica/Vecchio nome [v5aaaaaaaaa].mp4").writeText("già qui")
        listing(
            "PLa",
            VideoEntry("v1aaaaaaaaa", "Primo"),
            VideoEntry("v2aaaaaaaaa", "Secondo: parte/2"),
            VideoEntry("v3aaaaaaaaa", "[Private video]", available = false),
            VideoEntry("v5aaaaaaaaa", "Nuovo nome"),
        )

        sync()
        assertEquals(
            setOf("mio.txt", "Primo [v1aaaaaaaaa].mp4", "Secondo parte 2 [v2aaaaaaaaa].mp4", "Vecchio nome [v5aaaaaaaaa].mp4"),
            files("musica"),
        )
        assertEquals("v1aaaaaaaaa P720", File(FakeDocumentsProvider.root, "musica/Primo [v1aaaaaaaaa].mp4").readText())
        assertEquals(listOf("v1aaaaaaaaa", "v2aaaaaaaaa"), backend.downloads.map { it.first })
        status("PLa").let {
            assertTrue(it.ok)
            assertEquals("Titolo PLa", it.title)
            assertEquals(3, it.videos)
            assertEquals(1, it.missing)
            assertEquals("3 video su 4 nella cartella · 2 scaricati · 1 non disponibili", it.message)
        }

        // Nothing new: nothing downloaded.
        sync()
        assertEquals(2, backend.downloads.size)

        // Removed from the playlist (including the adopted file) → deleted; added → downloaded;
        // deleted by hand → downloaded again. Other files are never touched.
        File(FakeDocumentsProvider.root, "musica/Secondo parte 2 [v2aaaaaaaaa].mp4").delete()
        listing("PLa", VideoEntry("v2aaaaaaaaa", "Secondo: parte/2"), VideoEntry("v4aaaaaaaaa", "Quarto"))
        sync()
        assertEquals(setOf("mio.txt", "Secondo parte 2 [v2aaaaaaaaa].mp4", "Quarto [v4aaaaaaaaa].mp4"), files("musica"))
        assertEquals("2 video su 2 nella cartella · 2 scaricati · 2 eliminati", status("PLa").message)
    }

    @Test
    fun anEmptyPlaylistMustBeConfirmedBeforeDeleting() {
        configure(playlist("PLa", "musica"))
        listing("PLa", VideoEntry("v1aaaaaaaaa", "Primo"))
        sync()
        assertEquals(setOf("Primo [v1aaaaaaaaa].mp4"), files("musica"))

        listing("PLa")
        sync()
        assertEquals(setOf("Primo [v1aaaaaaaaa].mp4"), files("musica"))
        assertTrue(status("PLa").message.contains("playlist vuota"))

        sync()
        assertEquals(emptySet<String>(), files("musica"))
    }

    @Test
    fun filesSharedWithAnotherPlaylistInTheSameFolderAreKept() {
        configure(playlist("PLa", "video"), playlist("PLb", "video"))
        listing("PLa", VideoEntry("v1aaaaaaaaa", "Comune"), VideoEntry("v2aaaaaaaaa", "Solo A"))
        listing("PLb", VideoEntry("v1aaaaaaaaa", "Comune"))
        sync()
        assertEquals(setOf("Comune [v1aaaaaaaaa].mp4", "Solo A [v2aaaaaaaaa].mp4"), files("video"))
        assertEquals(2, backend.downloads.size) // the shared video is downloaded once

        listing("PLa")
        sync()
        sync()
        assertEquals(setOf("Comune [v1aaaaaaaaa].mp4"), files("video"))
    }

    @Test
    fun watchLaterNeedsTheYouTubeAccount() {
        configure(playlist(PlaylistTarget.WATCH_LATER, "dopo", VideoQuality.AUDIO_MP3))
        listing(PlaylistTarget.WATCH_LATER, VideoEntry("v1aaaaaaaaa", "Da vedere"), VideoEntry("v9aaaaaaaaa", "Vietato ai minori"))
        backend.errors["v9aaaaaaaaa"] = VideoException("Video con limiti di età: serve l'accesso a YouTube", needsSignIn = true)

        sync()
        status(PlaylistTarget.WATCH_LATER).let {
            assertFalse(it.ok)
            assertEquals(Problem.AUTHORIZATION, it.problem)
        }
        assertTrue(backend.listingCookies.isEmpty())

        container.youtubeAccount.saveCookies(NetscapeCookies.fromCookieHeader("SAPISID=abc; PREF=x", 0))
        sync()
        assertEquals(setOf("Da vedere [v1aaaaaaaaa].mp3", "Vietato ai minori [v9aaaaaaaaa].mp3"), files("dopo"))
        assertTrue(backend.listingCookies.single()!!.contains("\tSAPISID\tabc"))
        status(PlaylistTarget.WATCH_LATER).let {
            assertTrue(it.ok)
            assertEquals("Guarda più tardi", it.title)
        }
        // The temporary cookie files are gone.
        assertEquals(emptyList<String>(), File(app.noBackupFilesDir, "youtube").list().orEmpty().toList())
    }

    @Test
    fun meteredNetworkDefersDownloadsButMirrorsRemovals() {
        configure(playlist("PLa", "musica"), wifiOnly = true)
        listing("PLa", VideoEntry("v1aaaaaaaaa", "Primo"))
        val wifi = PlaylistSync(app, container.settings, container.status, container.notifier, container.youtubeAccount, backend) { false }
        val mobile = PlaylistSync(app, container.settings, container.status, container.notifier, container.youtubeAccount, backend) { true }
        sync(wifi)
        assertEquals(setOf("Primo [v1aaaaaaaaa].mp4"), files("musica"))

        listing("PLa", VideoEntry("v2aaaaaaaaa", "Secondo"))
        sync(mobile)
        assertEquals(emptySet<String>(), files("musica"))
        assertEquals("0 video su 1 nella cartella · 1 eliminati · 1 in attesa del Wi-Fi", status("PLa").message)
        assertTrue(status("PLa").ok)
    }

    @Test
    fun repeatedFailuresStopTheRunAndFolderProblemsAreReported() {
        configure(playlist("PLa", "musica"), PlaylistTarget("PLb", "Senza cartella"))
        listing("PLa", *(1..5).map { VideoEntry("v${it}aaaaaaaaa", "Video $it") }.toTypedArray())
        (1..5).forEach { backend.errors["v${it}aaaaaaaaa"] = VideoException("Video non disponibile") }
        listing("PLb", VideoEntry("v1aaaaaaaaa", "Video 1"))

        sync()
        status("PLa").let {
            assertFalse(it.ok)
            assertEquals(Problem.TRANSIENT, it.problem)
            assertTrue(it.message, it.message.endsWith("3 non scaricati (Video 1: Video non disponibile)"))
        }
        status("PLb").let {
            assertFalse(it.ok)
            assertEquals(Problem.FOLDER, it.problem)
        }
        assertEquals(emptySet<String>(), files("musica"))
        assertTrue(File(app.cacheDir, "youtube").list().orEmpty().isEmpty())
    }
}
