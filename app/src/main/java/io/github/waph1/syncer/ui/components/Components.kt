package io.github.waph1.syncer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.waph1.syncer.R
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.settings.NotesSource
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.storage.SafFolder
import io.github.waph1.syncer.sync.Notifier

fun SyncType.icon(): ImageVector = when (this) {
    SyncType.CALENDAR -> Icons.Filled.DateRange
    SyncType.TASKS -> Icons.AutoMirrored.Filled.List
    SyncType.NOTES -> Icons.Filled.Edit
    SyncType.CONTACTS -> Icons.Filled.Person
}

@Composable
fun SyncType.label(): String = stringResource(Notifier.labelOf(this))

@Composable
fun SyncType.formatDescription(): String = stringResource(
    when (this) {
        SyncType.CALENDAR -> R.string.type_calendar_format
        SyncType.TASKS -> R.string.type_tasks_format
        SyncType.NOTES -> R.string.type_notes_format
        SyncType.CONTACTS -> R.string.type_contacts_format
    },
)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Shows a chosen folder (or a warning if missing / no longer accessible) with a button to change it. */
@Composable
fun FolderRow(title: String, uri: String?, onPick: () -> Unit) {
    val context = LocalContext.current
    val accessible = SafFolder.hasPermission(context, uri)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            when {
                uri == null -> Text(
                    stringResource(R.string.folder_not_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                !accessible -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.folder_no_permission, SafFolder.describe(uri).orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Text(
                    SafFolder.describe(uri).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onPick) {
            Text(stringResource(if (uri == null) R.string.action_choose else R.string.action_change))
        }
    }
}

/** True when [target] is disabled or has a folder the app can still write to. */
fun AppSettings.folderReady(context: android.content.Context, type: SyncType): Boolean {
    val target: FolderTarget = target(type)
    if (!target.enabled) return true
    if (!SafFolder.hasPermission(context, target.folderUri)) return false
    if (type == SyncType.NOTES && notesSource == NotesSource.TAKEOUT) return SafFolder.hasPermission(context, takeoutFolderUri)
    return true
}

/**
 * Editor for the four data types: enable switch, destination folder and type-specific options.
 * Used by both the setup wizard (editing a draft) and the settings screen.
 */
@Composable
fun TargetsEditor(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    pickFolder: (target: String, current: String?) -> Unit,
    calendarContent: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (type in SyncType.entries) {
            val target = settings.target(type)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(type.icon(), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            SwitchRow(
                                title = type.label(),
                                subtitle = type.formatDescription(),
                                checked = target.enabled,
                                onCheckedChange = { on -> onChange { it.withTarget(type, target.copy(enabled = on)) } },
                            )
                        }
                    }
                    if (target.enabled) {
                        FolderRow(stringResource(R.string.folder_destination), target.folderUri) {
                            pickFolder(type.name, target.folderUri)
                        }
                        when (type) {
                            SyncType.NOTES -> NotesOptions(settings, onChange, pickFolder)
                            SyncType.CONTACTS -> SwitchRow(
                                title = stringResource(R.string.contacts_include_photos),
                                subtitle = stringResource(R.string.contacts_include_photos_hint),
                                checked = settings.contactsIncludePhotos,
                                onCheckedChange = { on -> onChange { it.copy(contactsIncludePhotos = on) } },
                            )
                            SyncType.TASKS -> HintText(stringResource(R.string.tasks_hint))
                            SyncType.CALENDAR -> {
                                HintText(stringResource(R.string.calendar_hint))
                                calendarContent?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesOptions(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    pickFolder: (target: String, current: String?) -> Unit,
) {
    Text(stringResource(R.string.notes_source), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    RadioRow(
        title = stringResource(R.string.notes_source_takeout),
        subtitle = stringResource(R.string.notes_source_takeout_hint),
        selected = settings.notesSource == NotesSource.TAKEOUT,
        onClick = { onChange { it.copy(notesSource = NotesSource.TAKEOUT) } },
    )
    RadioRow(
        title = stringResource(R.string.notes_source_api),
        subtitle = stringResource(R.string.notes_source_api_hint),
        selected = settings.notesSource == NotesSource.KEEP_API,
        onClick = { onChange { it.copy(notesSource = NotesSource.KEEP_API) } },
    )
    if (settings.notesSource == NotesSource.TAKEOUT) {
        FolderRow(stringResource(R.string.notes_takeout_folder), settings.takeoutFolderUri) {
            pickFolder(TARGET_TAKEOUT, settings.takeoutFolderUri)
        }
        HintText(stringResource(R.string.notes_takeout_folder_hint))
    }
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

const val TARGET_TAKEOUT = "takeout"
const val TARGET_BACKUP = "backup"
const val TARGET_PASSWORDS = "passwords"

/** Applies a folder picked with [rememberFolderPicker] to the settings. */
fun AppSettings.withPickedFolder(target: String, uri: String): AppSettings = when (target) {
    TARGET_TAKEOUT -> copy(takeoutFolderUri = uri)
    TARGET_BACKUP -> copy(settingsBackupFolderUri = uri)
    TARGET_PASSWORDS -> copy(passwords = passwords.copy(folderUri = uri))
    else -> SyncType.entries.firstOrNull { it.name == target }
        ?.let { withTarget(it, target(it).copy(folderUri = uri)) }
        ?: this
}
