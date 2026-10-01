package io.github.waph1.syncer.youtube

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.waph1.syncer.settings.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * yt-dlp running on the device (youtubedl-android: Python, yt-dlp, QuickJS for YouTube's
 * JavaScript challenges and FFmpeg to merge audio and video). yt-dlp can update itself, which
 * matters because YouTube changes often.
 */
class YtDlpBackend(private val context: Context) : VideoBackend {
    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    private suspend fun ensureInit() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                try {
                    // The first start unpacks Python and FFmpeg (a few seconds).
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                } catch (e: YoutubeDLException) {
                    throw VideoException("Impossibile avviare yt-dlp: ${e.message}", cause = e)
                }
            }
            initialized = true
        }
    }

    override suspend fun version(): String? {
        ensureInit()
        return withContext(Dispatchers.IO) { YoutubeDL.getInstance().version(context.applicationContext) }
    }

    override suspend fun update(): String? {
        ensureInit()
        val status = withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel._STABLE)
            } catch (e: YoutubeDLException) {
                throw VideoException("Aggiornamento di yt-dlp non riuscito: ${e.message}", cause = e)
            }
        }
        return if (status == YoutubeDL.UpdateStatus.DONE) version() else null
    }

    override suspend fun listPlaylist(playlistId: String, cookies: File?): PlaylistListing =
        YtDlp.parseListing(run(YtDlp.playlistUrl(playlistId), YtDlp.listArgs(cookies?.absolutePath)))

    override suspend fun listMyPlaylists(cookies: File): List<PlaylistInfo> =
        YtDlp.parsePlaylists(run(YtDlp.MY_PLAYLISTS_URL, YtDlp.listArgs(cookies.absolutePath)))

    override suspend fun download(videoId: String, quality: VideoQuality, dir: File, cookies: File?, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        run(YtDlp.videoUrl(videoId), YtDlp.downloadArgs(quality, dir.absolutePath, cookies?.absolutePath), onProgress)
        return dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") && it.extension !in setOf("part", "ytdl", "temp", "tmp") }
            .maxByOrNull { it.length() }
            ?: throw VideoException("yt-dlp non ha prodotto nessun file")
    }

    /** Runs yt-dlp and returns its standard output; cancelling the coroutine kills the process. */
    private suspend fun run(url: String, args: List<String>, onProgress: ((Float) -> Unit)? = null): String {
        ensureInit()
        val request = YoutubeDLRequest(url).addCommands(args)
        val processId = UUID.randomUUID().toString()
        var finished = false
        try {
            return runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                    if (progress >= 0) onProgress?.invoke(progress)
                }.out.also { finished = true }
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw CancellationException("yt-dlp interrotto")
        } catch (e: YoutubeDLException) {
            throw YtDlp.describeError(e.message.orEmpty())
        } finally {
            if (!finished) runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
    }
}
