package io.github.waph1.syncer.ui.components

import android.Manifest
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.waph1.syncer.R
import io.github.waph1.syncer.format.DurationText
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.storage.SafFolder
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.util.Permissions
import java.time.Duration

/** True when passwords are off, or have a writable folder and a database password. */
fun AppSettings.passwordsReady(context: android.content.Context, hasDatabasePassword: Boolean): Boolean =
    !passwords.enabled || (SafFolder.hasPermission(context, passwords.folderUri) && hasDatabasePassword)

/**
 * Google passwords → KeePass: enable switch, database folder, database password and (outside the
 * setup wizard) the import buttons.
 */
@Composable
fun PasswordsEditor(
    vm: MainViewModel,
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    pickFolder: (target: String, current: String?) -> Unit,
    showImportActions: Boolean,
) {
    val hasPassword by vm.hasDatabasePassword.collectAsStateWithLifecycle()
    val busy by vm.passwordBusy.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SwitchRow(
                        title = stringResource(R.string.type_passwords),
                        subtitle = stringResource(R.string.type_passwords_format),
                        checked = settings.passwords.enabled,
                        onCheckedChange = { on -> onChange { it.copy(passwords = it.passwords.copy(enabled = on)) } },
                    )
                }
            }
            if (settings.passwords.enabled) {
                FolderRow(stringResource(R.string.passwords_folder), settings.passwords.folderUri) {
                    pickFolder(TARGET_PASSWORDS, settings.passwords.folderUri)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.passwords_db_password), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (hasPassword) R.string.passwords_db_password_set else R.string.passwords_db_password_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasPassword) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { showDialog = true }, enabled = !busy) {
                        Text(stringResource(if (hasPassword) R.string.action_change else R.string.action_set))
                    }
                }
                ReminderEditor(vm, settings, onChange, showNextDue = showImportActions)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                HintText(stringResource(R.string.passwords_hint))
                if (showImportActions) PasswordImportActions(vm)
            }
        }
    }
    if (showDialog) {
        DatabasePasswordDialog(
            hasPassword = hasPassword,
            onDismiss = { showDialog = false },
            onChange = { current, new -> showDialog = false; vm.changeDatabasePassword(current, new) },
            onReset = { new -> showDialog = false; vm.resetDatabasePassword(new) },
        )
    }
}

/** Periodic notification reminding to import the passwords again (Google allows no automatic export). */
@Composable
private fun ReminderEditor(
    vm: MainViewModel,
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    showNextDue: Boolean,
) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val requestPermissions = rememberPermissionRequester()
    val reminder = settings.passwordReminder
    SwitchRow(
        title = stringResource(R.string.reminder_enabled),
        subtitle = stringResource(R.string.reminder_enabled_hint),
        checked = reminder.enabled,
        onCheckedChange = { on ->
            onChange { it.copy(passwordReminder = it.passwordReminder.copy(enabled = on)) }
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        },
    )
    if (reminder.enabled) {
        val next = if (showNextDue) vm.nextPasswordReminder(settings, status) else null
        DurationRow(
            title = stringResource(R.string.reminder_every),
            value = reminder.every,
            min = DurationText.ONE_HOUR,
            max = DurationText.ONE_YEAR,
            fallback = Duration.ofDays(30),
            examples = stringResource(R.string.reminder_examples),
            onChange = { every -> onChange { it.copy(passwordReminder = it.passwordReminder.copy(every = every)) } },
            detail = next?.let {
                stringResource(
                    R.string.reminder_next,
                    DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME),
                )
            },
        )
    }
}

/** "Import from Google Password Manager" and "Import CSV file" buttons, plus the CSV deletion prompt. */
@Composable
fun PasswordImportActions(vm: MainViewModel) {
    val activity = LocalActivity.current
    val busy by vm.passwordBusy.collectAsStateWithLifecycle()
    val csvToDelete by vm.csvToDelete.collectAsStateWithLifecycle()
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPasswordsFromCsv(uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Button(onClick = { activity?.let(vm::importPasswordsFromGoogle) }, enabled = !busy && activity != null) {
            Text(stringResource(R.string.action_import_passwords))
        }
        TextButton(
            onClick = { pickCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) },
            enabled = !busy,
        ) { Text(stringResource(R.string.action_import_passwords_csv)) }
    }
    if (csvToDelete != null) {
        AlertDialog(
            onDismissRequest = { vm.deleteImportedCsv(false) },
            title = { Text(stringResource(R.string.passwords_delete_csv_title)) },
            text = { Text(stringResource(R.string.passwords_delete_csv_text)) },
            confirmButton = { TextButton(onClick = { vm.deleteImportedCsv(true) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { vm.deleteImportedCsv(false) }) { Text(stringResource(R.string.action_keep)) } },
        )
    }
}

@Composable
private fun DatabasePasswordDialog(
    hasPassword: Boolean,
    onDismiss: () -> Unit,
    onChange: (current: String?, new: String) -> Unit,
    onReset: (new: String) -> Unit,
) {
    var forgot by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val askCurrent = hasPassword && !forgot
    val tooShort = new.isNotEmpty() && new.length < MIN_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != new
    val valid = new.length >= MIN_LENGTH && confirm == new && (!askCurrent || current.isNotEmpty())
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (hasPassword) R.string.passwords_dialog_change else R.string.passwords_dialog_set)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (forgot) {
                    Text(stringResource(R.string.passwords_forgot_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (askCurrent) {
                    OutlinedTextField(
                        value = current, onValueChange = { current = it }, singleLine = true,
                        label = { Text(stringResource(R.string.passwords_current)) },
                        visualTransformation = transformation, keyboardOptions = keyboard,
                    )
                }
                OutlinedTextField(
                    value = new, onValueChange = { new = it }, singleLine = true,
                    label = { Text(stringResource(R.string.passwords_new)) },
                    isError = tooShort,
                    supportingText = { Text(pluralStringResource(R.plurals.passwords_min_length, MIN_LENGTH, MIN_LENGTH)) },
                    visualTransformation = transformation, keyboardOptions = keyboard,
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it }, singleLine = true,
                    label = { Text(stringResource(R.string.passwords_confirm)) },
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text(stringResource(R.string.passwords_mismatch)) }) else null,
                    visualTransformation = transformation, keyboardOptions = keyboard,
                )
                SwitchRow(title = stringResource(R.string.passwords_show), checked = visible, onCheckedChange = { visible = it })
                if (askCurrent) {
                    TextButton(onClick = { forgot = true }) { Text(stringResource(R.string.passwords_forgot)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (forgot) onReset(new) else onChange(current.takeIf { hasPassword }, new) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MIN_LENGTH = 8
