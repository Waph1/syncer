package io.github.waph1.syncer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.waph1.syncer.R
import io.github.waph1.syncer.settings.AppSettings

@Composable
fun formatInterval(minutes: Int): String = when {
    minutes >= 2 * 24 * 60 && minutes % (24 * 60) == 0 -> (minutes / (24 * 60)).let { pluralStringResource(R.plurals.interval_days, it, it) }
    minutes % 60 == 0 -> stringResource(R.string.interval_hours, minutes / 60)
    else -> stringResource(R.string.interval_minutes, minutes)
}

/** Sync triggers: on change, periodic interval, deletion of removed items. */
@Composable
fun SyncOptionsEditor(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    Column {
        SwitchRow(
            title = stringResource(R.string.sync_on_change),
            subtitle = stringResource(R.string.sync_on_change_hint),
            checked = settings.syncOnChange,
            onCheckedChange = { on -> onChange { it.copy(syncOnChange = on) } },
        )
        SwitchRow(
            title = stringResource(R.string.sync_periodic),
            subtitle = stringResource(R.string.sync_periodic_hint),
            checked = settings.periodicSyncEnabled,
            onCheckedChange = { on -> onChange { it.copy(periodicSyncEnabled = on) } },
        )
        if (settings.periodicSyncEnabled) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showIntervalDialog = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_interval), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.sync_interval_value, formatInterval(settings.syncIntervalMinutes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showIntervalDialog = true }) { Text(stringResource(R.string.action_change)) }
            }
        }
        SwitchRow(
            title = stringResource(R.string.delete_removed),
            subtitle = stringResource(R.string.delete_removed_hint),
            checked = settings.deleteRemovedFiles,
            onCheckedChange = { on -> onChange { it.copy(deleteRemovedFiles = on) } },
        )
    }
    if (showIntervalDialog) {
        IntervalDialog(
            current = settings.syncIntervalMinutes,
            onDismiss = { showIntervalDialog = false },
            onConfirm = { minutes ->
                showIntervalDialog = false
                onChange { it.copy(syncIntervalMinutes = minutes) }
            },
        )
    }
}

private val PRESETS = listOf(15, 30, 60, 120, 360, 720, 1440)

@Composable
private fun IntervalDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(if (current in PRESETS) current else -1) }
    var custom by rememberSaveable { mutableStateOf(if (current in PRESETS) "" else current.toString()) }
    val customValue = custom.toIntOrNull()
    val customValid = customValue != null && customValue in AppSettings.MIN_INTERVAL_MINUTES..AppSettings.MAX_INTERVAL_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_interval)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (minutes in PRESETS) {
                    RadioRow(title = formatInterval(minutes), selected = selected == minutes, onClick = { selected = minutes })
                }
                RadioRow(title = stringResource(R.string.interval_custom), selected = selected == -1, onClick = { selected = -1 })
                if (selected == -1) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { value -> custom = value.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.interval_custom_label)) },
                        isError = custom.isNotEmpty() && !customValid,
                        supportingText = { Text(stringResource(R.string.interval_custom_hint, AppSettings.MIN_INTERVAL_MINUTES)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != -1 || customValid,
                onClick = { onConfirm(if (selected == -1) customValue!! else selected) },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Settings backup: switch + destination folder. */
@Composable
fun BackupEditor(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    pickFolder: (target: String, current: String?) -> Unit,
) {
    Column {
        SwitchRow(
            title = stringResource(R.string.backup_enabled),
            subtitle = stringResource(R.string.backup_enabled_hint),
            checked = settings.settingsBackupEnabled,
            onCheckedChange = { on -> onChange { it.copy(settingsBackupEnabled = on) } },
        )
        if (settings.settingsBackupEnabled) {
            FolderRow(stringResource(R.string.backup_folder), settings.settingsBackupFolderUri) {
                pickFolder(TARGET_BACKUP, settings.settingsBackupFolderUri)
            }
        }
    }
}
