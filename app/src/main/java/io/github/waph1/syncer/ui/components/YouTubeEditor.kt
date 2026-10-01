package io.github.waph1.syncer.ui.components

import android.app.Activity
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.waph1.syncer.R
import io.github.waph1.syncer.format.DurationText
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.PlaylistTarget
import io.github.waph1.syncer.settings.VideoQuality
import io.github.waph1.syncer.sync.SyncScheduler
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.ui.MyPlaylists
import io.github.waph1.syncer.youtube.YouTubeLoginActivity
import io.github.waph1.syncer.youtube.YtDlp

@Composable
fun VideoQuality.label(): String = stringResource(
    when (this) {
        VideoQuality.BEST -> R.string.quality_best
        VideoQuality.P2160 -> R.string.quality_2160
        VideoQuality.P1440 -> R.string.quality_1440
        VideoQuality.P1080 -> R.string.quality_1080
        VideoQuality.P720 -> R.string.quality_720
        VideoQuality.P480 -> R.string.quality_480
        VideoQuality.P360 -> R.string.quality_360
        VideoQuality.AUDIO_M4A -> R.string.quality_audio_m4a
        VideoQuality.AUDIO_MP3 -> R.string.quality_audio_mp3
    },
)

fun playlistFolderTarget(id: String) = "$TARGET_PLAYLIST_PREFIX$id"

/** YouTube playlists: account, playlists with folder and quality, frequency, Wi-Fi, yt-dlp. */
@Composable
fun YouTubeEditor(
    vm: MainViewModel,
    settings: AppSettings,
    pickFolder: (target: String, current: String?) -> Unit,
) {
    val youtube = settings.youtube
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.youtubeBusy.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PlaylistTarget?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SwitchRow(
                        title = stringResource(R.string.type_youtube),
                        subtitle = stringResource(R.string.type_youtube_format),
                        checked = youtube.enabled,
                        onCheckedChange = { on -> vm.update { it.copy(youtube = it.youtube.copy(enabled = on)) } },
                    )
                }
            }
            if (!youtube.enabled) return@Column

            YouTubeAccountRow(vm)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.youtube_playlists), style = MaterialTheme.typography.labelLarge)
            if (youtube.playlists.isEmpty()) HintText(stringResource(R.string.youtube_no_playlists))
            for (playlist in youtube.playlists) {
                PlaylistRow(
                    playlist = playlist,
                    title = status.playlists[playlist.id]?.title ?: playlist.title,
                    onPickFolder = { pickFolder(playlistFolderTarget(playlist.id), playlist.folderUri) },
                    onQuality = { vm.setPlaylistQuality(playlist.id, it) },
                    onRemove = { removing = playlist },
                )
            }
            OutlinedButton(onClick = { adding = true }, enabled = !busy) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_add_playlist))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            DurationRow(
                title = stringResource(R.string.youtube_every),
                value = youtube.every,
                min = DurationText.FIFTEEN_MINUTES,
                max = DurationText.ONE_YEAR,
                fallback = SyncScheduler.DEFAULT_PLAYLIST_INTERVAL,
                examples = stringResource(R.string.youtube_every_examples),
                onChange = { every -> vm.update { it.copy(youtube = it.youtube.copy(every = every)) } },
            )
            SwitchRow(
                title = stringResource(R.string.youtube_wifi_only),
                subtitle = stringResource(R.string.youtube_wifi_only_hint),
                checked = youtube.wifiOnly,
                onCheckedChange = { on -> vm.update { it.copy(youtube = it.youtube.copy(wifiOnly = on)) } },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.youtube_ytdlp), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        status.ytDlpVersion?.let { version ->
                            val checked = status.ytDlpUpdateCheckedAt?.let {
                                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                            }
                            stringResource(R.string.youtube_ytdlp_version, version, checked ?: "—")
                        } ?: stringResource(R.string.youtube_ytdlp_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = vm::updateYtDlp, enabled = !busy) { Text(stringResource(R.string.action_update)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            HintText(stringResource(R.string.youtube_hint))
        }
    }
    if (adding) {
        AddPlaylistDialog(
            vm = vm,
            existing = youtube.playlists.mapTo(HashSet()) { it.id },
            onDismiss = { adding = false },
            onAdded = { target ->
                adding = false
                pickFolder(playlistFolderTarget(target.id), target.folderUri)
            },
        )
    }
    removing?.let { playlist ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.youtube_remove_title, playlist.title)) },
            text = { Text(stringResource(R.string.youtube_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    vm.removePlaylist(playlist.id)
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun YouTubeAccountRow(vm: MainViewModel) {
    val context = LocalContext.current
    val signedIn by vm.youtubeSignedIn.collectAsStateWithLifecycle()
    val busy by vm.youtubeBusy.collectAsStateWithLifecycle()
    val cookiesToDelete by vm.cookiesToDelete.collectAsStateWithLifecycle()
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.youtubeSignedIn()
    }
    val pickCookies = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importYouTubeCookies(uri)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.youtube_account), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (signedIn) R.string.youtube_signed_in else R.string.youtube_signed_out),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (signedIn) {
            OutlinedButton(onClick = vm::signOutYouTube) { Text(stringResource(R.string.action_sign_out)) }
        } else {
            Button(onClick = { login.launch(Intent(context, YouTubeLoginActivity::class.java)) }) {
                Text(stringResource(R.string.action_sign_in))
            }
        }
    }
    TextButton(
        onClick = { pickCookies.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
        enabled = !busy,
    ) { Text(stringResource(R.string.action_import_cookies)) }
    HintText(stringResource(R.string.youtube_account_hint))
    if (cookiesToDelete != null) {
        AlertDialog(
            onDismissRequest = { vm.deleteImportedCookies(false) },
            title = { Text(stringResource(R.string.youtube_delete_cookies_title)) },
            text = { Text(stringResource(R.string.youtube_delete_cookies_text)) },
            confirmButton = { TextButton(onClick = { vm.deleteImportedCookies(true) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { vm.deleteImportedCookies(false) }) { Text(stringResource(R.string.action_keep)) } },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistTarget,
    title: String,
    onPickFolder: () -> Unit,
    onQuality: (VideoQuality) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, stringResource(R.string.youtube_remove_title, title))
            }
        }
        FolderRow(stringResource(R.string.folder_destination), playlist.folderUri, onPickFolder)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.youtube_quality), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            QualityMenu(playlist.quality, onQuality)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun QualityMenu(current: VideoQuality, onSelect: (VideoQuality) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(current.label()) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (quality in VideoQuality.entries) {
                DropdownMenuItem(
                    text = { Text(quality.label()) },
                    onClick = {
                        open = false
                        onSelect(quality)
                    },
                )
            }
        }
    }
}

private enum class AddMode { WATCH_LATER, MINE, LINK }

@Composable
private fun AddPlaylistDialog(
    vm: MainViewModel,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onAdded: (PlaylistTarget) -> Unit,
) {
    val signedIn by vm.youtubeSignedIn.collectAsStateWithLifecycle()
    val busy by vm.youtubeBusy.collectAsStateWithLifecycle()
    val mine by vm.myPlaylists.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(if (signedIn) AddMode.WATCH_LATER else AddMode.LINK) }
    var selectedMine by rememberSaveable { mutableStateOf<String?>(null) }
    var link by rememberSaveable { mutableStateOf("") }
    val linkId = YtDlp.playlistIdFrom(link)
    val watchLaterAdded = PlaylistTarget.WATCH_LATER in existing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RadioRow(
                    title = stringResource(R.string.youtube_add_watch_later),
                    subtitle = stringResource(
                        when {
                            watchLaterAdded -> R.string.youtube_add_already
                            signedIn -> R.string.youtube_add_watch_later_hint
                            else -> R.string.youtube_add_needs_sign_in
                        },
                    ),
                    selected = mode == AddMode.WATCH_LATER,
                    onClick = { mode = AddMode.WATCH_LATER },
                )
                RadioRow(
                    title = stringResource(R.string.youtube_add_mine),
                    subtitle = stringResource(if (signedIn) R.string.youtube_add_mine_hint else R.string.youtube_add_needs_sign_in),
                    selected = mode == AddMode.MINE,
                    onClick = {
                        mode = AddMode.MINE
                        if (signedIn && mine !is MyPlaylists.Loaded) vm.loadMyPlaylists()
                    },
                )
                if (mode == AddMode.MINE && signedIn) {
                    when (val state = mine) {
                        MyPlaylists.Idle, MyPlaylists.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp))
                        is MyPlaylists.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        is MyPlaylists.Loaded -> {
                            val available = state.playlists.filter { it.id !in existing && it.id != PlaylistTarget.WATCH_LATER }
                            if (available.isEmpty()) HintText(stringResource(R.string.youtube_add_mine_empty))
                            for (playlist in available) {
                                RadioRow(
                                    title = playlist.title,
                                    selected = selectedMine == playlist.id,
                                    onClick = { selectedMine = playlist.id },
                                )
                            }
                        }
                    }
                }
                RadioRow(
                    title = stringResource(R.string.youtube_add_link),
                    subtitle = stringResource(R.string.youtube_add_link_hint),
                    selected = mode == AddMode.LINK,
                    onClick = { mode = AddMode.LINK },
                )
                if (mode == AddMode.LINK) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.youtube_add_link_label)) },
                        isError = link.isNotBlank() && linkId == null,
                        supportingText = if (link.isNotBlank() && linkId == null) {
                            { Text(stringResource(R.string.youtube_add_link_invalid)) }
                        } else {
                            null
                        },
                    )
                    if (linkId != null && linkId in existing) HintText(stringResource(R.string.youtube_add_already))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        },
        confirmButton = {
            val mineSelection = (mine as? MyPlaylists.Loaded)?.playlists?.firstOrNull { it.id == selectedMine }
            val enabled = !busy && when (mode) {
                AddMode.WATCH_LATER -> signedIn && !watchLaterAdded
                AddMode.MINE -> signedIn && mineSelection != null
                AddMode.LINK -> linkId != null && linkId !in existing
            }
            TextButton(
                enabled = enabled,
                onClick = {
                    when (mode) {
                        AddMode.WATCH_LATER -> onAdded(vm.addPlaylist(PlaylistTarget.WATCH_LATER, ""))
                        AddMode.MINE -> mineSelection?.let { onAdded(vm.addPlaylist(it.id, it.title)) }
                        AddMode.LINK -> vm.addPlaylistFromLink(link, onAdded)
                    }
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
