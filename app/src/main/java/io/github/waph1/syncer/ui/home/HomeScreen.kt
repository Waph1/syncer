package io.github.waph1.syncer.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.waph1.syncer.R
import io.github.waph1.syncer.format.DurationText
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.NotesSource
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.source.GoogleAuth
import io.github.waph1.syncer.storage.SafFolder
import io.github.waph1.syncer.sync.Problem
import io.github.waph1.syncer.sync.StatusSnapshot
import io.github.waph1.syncer.sync.SyncScheduler
import io.github.waph1.syncer.sync.TypeStatus
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.ui.components.PasswordImportActions
import io.github.waph1.syncer.ui.components.describeEvery
import io.github.waph1.syncer.ui.components.formatInterval
import io.github.waph1.syncer.ui.components.icon
import io.github.waph1.syncer.ui.components.label
import io.github.waph1.syncer.ui.components.rememberGoogleAuthorizer
import io.github.waph1.syncer.ui.components.rememberPermissionRequester
import io.github.waph1.syncer.util.Permissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val requestPermissions = rememberPermissionRequester { vm.syncNow() }
    val authorize = rememberGoogleAuthorizer(vm)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            val label = stringResource(if (running.isEmpty()) R.string.action_sync_now else R.string.sync_running)
            ExtendedFloatingActionButton(
                onClick = { if (running.isEmpty()) vm.syncNow() },
                // The animated label is hidden from accessibility services: name the button explicitly.
                modifier = Modifier.semantics { contentDescription = label },
                icon = {
                    if (running.isEmpty()) Icon(Icons.Filled.Refresh, null)
                    else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                },
                text = { Text(label) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(settings.accountName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
            Text(scheduleSummary(settings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            for (type in SyncType.entries.filter { settings.target(it).enabled }) {
                TypeCard(
                    type = type,
                    settings = settings,
                    status = status.types[type],
                    running = type in running,
                    onFix = { problem ->
                        when (problem) {
                            Problem.PERMISSION -> requestPermissions(listOfNotNull(Permissions.forType(type)))
                            Problem.AUTHORIZATION -> settings.accountName?.let { account ->
                                val scope = if (type == SyncType.NOTES) GoogleAuth.SCOPE_KEEP else GoogleAuth.SCOPE_TASKS
                                authorize(account, scope)
                            }
                            else -> onOpenSettings()
                        }
                    },
                    onSync = { vm.syncNow(setOf(type)) },
                )
            }
            if (settings.passwords.enabled) PasswordsCard(vm, settings, status.passwords)
            if (settings.youtube.enabled) YouTubeCard(vm, settings, status, onOpenSettings)
            if (settings.enabledTypes().isEmpty() && !settings.passwords.enabled && !settings.youtube.enabled) {
                Text(stringResource(R.string.home_nothing_enabled), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(72.dp))
        }
    }
}

@Composable
private fun scheduleSummary(settings: AppSettings): String {
    val parts = mutableListOf<String>()
    val observed = listOf(SyncType.CALENDAR, SyncType.CONTACTS).filter { settings.target(it).enabled }
    if (settings.syncOnChange && observed.isNotEmpty()) {
        parts += stringResource(R.string.home_schedule_on_change, observed.map { it.label().lowercase() }.joinToString(", "))
    }
    if (settings.periodicSyncEnabled) {
        parts += stringResource(R.string.home_schedule_periodic, formatInterval(settings.syncIntervalMinutes.coerceAtLeast(AppSettings.MIN_INTERVAL_MINUTES)))
    }
    return if (parts.isEmpty()) stringResource(R.string.home_schedule_manual) else parts.joinToString(" · ")
}

@Composable
private fun TypeCard(
    type: SyncType,
    settings: AppSettings,
    status: TypeStatus?,
    running: Boolean,
    onFix: (Problem) -> Unit,
    onSync: () -> Unit,
) {
    val failed = status != null && !status.ok
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(type.icon(), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(type.label(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        SafFolder.describe(settings.target(type).folderUri) ?: stringResource(R.string.folder_not_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (type == SyncType.NOTES) {
                        Text(
                            stringResource(if (settings.notesSource == NotesSource.TAKEOUT) R.string.notes_source_takeout else R.string.notes_source_api),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (running) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSync) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_sync_type, type.label())) }
                }
            }
            Spacer(Modifier.size(8.dp))
            when {
                status?.lastRunAt == null -> Text(stringResource(R.string.home_never_synced), style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text(
                        stringResource(
                            if (status.ok) R.string.home_last_sync_ok else R.string.home_last_sync_failed,
                            relative(status.lastRunAt),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                    if (!status.ok && status.lastSuccessAt != null) {
                        Text(
                            stringResource(R.string.home_last_success, relative(status.lastSuccessAt)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val problem = status.problem
                    if (!status.ok && problem != null && problem != Problem.TRANSIENT) {
                        TextButton(onClick = { onFix(problem) }) {
                            Text(
                                stringResource(
                                    when (problem) {
                                        Problem.PERMISSION -> R.string.action_grant
                                        Problem.AUTHORIZATION -> R.string.action_authorize
                                        else -> R.string.action_open_settings
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordsCard(vm: MainViewModel, settings: AppSettings, status: TypeStatus?) {
    val busy by vm.passwordBusy.collectAsStateWithLifecycle()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (status?.ok == false) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.type_passwords), style = MaterialTheme.typography.titleMedium)
                    Text(
                        SafFolder.describe(settings.passwords.folderUri) ?: stringResource(R.string.folder_not_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.size(8.dp))
            if (status?.lastRunAt == null) {
                Text(stringResource(R.string.passwords_never_imported), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(if (status.ok) R.string.passwords_last_import else R.string.passwords_last_import_failed, relative(status.lastRunAt)),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(status.message, style = MaterialTheme.typography.bodySmall)
            }
            PasswordImportActions(vm)
        }
    }
}

@Composable
private fun YouTubeCard(vm: MainViewModel, settings: AppSettings, status: StatusSnapshot, onOpenSettings: () -> Unit) {
    val running by vm.playlistRunning.collectAsStateWithLifecycle()
    val progress by vm.playlistProgress.collectAsStateWithLifecycle()
    val youtube = settings.youtube
    val failed = youtube.playlists.any { status.playlists[it.id]?.ok == false }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.type_youtube), style = MaterialTheme.typography.titleMedium)
                    val every = describeEvery(youtube.every, DurationText.FIFTEEN_MINUTES, DurationText.ONE_YEAR, SyncScheduler.DEFAULT_PLAYLIST_INTERVAL)
                    Text(
                        if (youtube.wifiOnly) stringResource(R.string.youtube_schedule_wifi, every) else every,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (youtube.playlists.isNotEmpty()) {
                    IconButton(onClick = vm::syncPlaylistsNow) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.action_sync_type, stringResource(R.string.type_youtube)))
                    }
                }
            }
            if (youtube.playlists.isEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.youtube_no_playlists), style = MaterialTheme.typography.bodySmall)
            }
            for (playlist in youtube.playlists) {
                val playlistStatus = status.playlists[playlist.id]
                Spacer(Modifier.size(8.dp))
                Text(playlistStatus?.title ?: playlist.title, style = MaterialTheme.typography.labelLarge)
                val current = progress?.takeIf { it.playlistId == playlist.id }
                when {
                    current != null -> {
                        Text(
                            stringResource(R.string.youtube_downloading, current.index, current.total, current.videoTitle),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val percent = current.percent
                        if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    playlist.folderUri == null -> Text(
                        stringResource(R.string.folder_not_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    playlistStatus?.lastRunAt == null -> Text(stringResource(R.string.home_never_synced), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(
                            stringResource(
                                if (playlistStatus.ok) R.string.youtube_last_check else R.string.youtube_last_check_failed,
                                relative(playlistStatus.lastRunAt),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(playlistStatus.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
                val problem = playlistStatus?.problem
                if (current == null && (playlist.folderUri == null || (playlistStatus?.ok == false && problem != null && problem != Problem.TRANSIENT))) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
                }
            }
        }
    }
}

private fun relative(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
