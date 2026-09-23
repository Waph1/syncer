package io.github.waph1.syncer.ui.setup

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.waph1.syncer.R
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.SyncType
import io.github.waph1.syncer.storage.SafFolder
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.ui.components.AccessEditor
import io.github.waph1.syncer.ui.components.BackupEditor
import io.github.waph1.syncer.ui.components.CalendarSelection
import io.github.waph1.syncer.ui.components.HintText
import io.github.waph1.syncer.ui.components.SyncOptionsEditor
import io.github.waph1.syncer.ui.components.TargetsEditor
import io.github.waph1.syncer.ui.components.folderReady
import io.github.waph1.syncer.ui.components.label
import io.github.waph1.syncer.ui.components.rememberAccountPicker
import io.github.waph1.syncer.ui.components.rememberFolderPicker
import io.github.waph1.syncer.ui.components.withPickedFolder
import kotlinx.coroutines.launch

private enum class Step(val title: Int) {
    IMPORT(R.string.setup_step_import),
    ACCOUNT(R.string.setup_step_account),
    DATA(R.string.setup_step_data),
    SYNC(R.string.setup_step_sync),
    BACKUP(R.string.setup_step_backup),
    ACCESS(R.string.setup_step_access),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: MainViewModel, onCancel: (() -> Unit)?, onFinished: () -> Unit) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saved by vm.settings.collectAsStateWithLifecycle()
    val authStates by vm.auth.collectAsStateWithLifecycle()
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var accountConfirmed by rememberSaveable { mutableStateOf(false) }
    val step = Step.entries[stepIndex]
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val pickFolder = rememberFolderPicker { target, uri -> vm.editDraft { it.withPickedFolder(target, uri) } }
    val pickAccount = rememberAccountPicker { name ->
        accountConfirmed = true
        vm.editDraft { it.copy(accountName = name) }
    }

    fun back() {
        if (stepIndex > 0) stepIndex-- else onCancel?.invoke()
    }
    BackHandler(enabled = stepIndex > 0 || onCancel != null) { back() }

    val context = LocalContext.current
    // An account restored from an import must be confirmed through the system picker, which
    // also makes it visible to the app.
    val accountOk = draft.accountName != null &&
        (accountConfirmed || (saved.setupCompleted && draft.accountName == saved.accountName))
    val dataOk = draft.enabledTypes().isNotEmpty() && SyncType.entries.all { draft.folderReady(context, it) }
    val backupOk = !draft.settingsBackupEnabled || SafFolder.hasPermission(context, draft.settingsBackupFolderUri)
    val canContinue = when (step) {
        Step.ACCOUNT -> accountOk
        Step.DATA -> dataOk
        Step.BACKUP -> backupOk
        else -> true
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(step.title))
                            Text(
                                stringResource(R.string.setup_step_counter, stepIndex + 1, Step.entries.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
                LinearProgressIndicator(
                    progress = { (stepIndex + 1f) / Step.entries.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = { back() }, enabled = stepIndex > 0 || onCancel != null) {
                    Text(stringResource(R.string.action_back))
                }
                if (step == Step.ACCESS) {
                    Button(onClick = { vm.finishSetup(); onFinished() }) { Text(stringResource(R.string.action_finish)) }
                } else {
                    Button(onClick = { stepIndex++ }, enabled = canContinue) { Text(stringResource(R.string.action_next)) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            when (step) {
                Step.IMPORT -> ImportStep(vm, draft)
                Step.ACCOUNT -> {
                    HintText(stringResource(R.string.setup_account_intro))
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.padding(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(draft.accountName ?: stringResource(R.string.account_none), style = MaterialTheme.typography.bodyLarge)
                                if (draft.accountName != null && !accountOk) {
                                    Text(
                                        stringResource(R.string.account_confirm_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Button(onClick = { pickAccount(draft.accountName) }) {
                                Text(stringResource(if (draft.accountName == null) R.string.action_choose else if (accountOk) R.string.action_change else R.string.action_confirm))
                            }
                        }
                    }
                }
                Step.DATA -> {
                    HintText(stringResource(R.string.setup_data_intro))
                    Spacer(Modifier.height(8.dp))
                    TargetsEditor(draft, vm::editDraft, pickFolder) { CalendarSelection(vm, draft, vm::editDraft) }
                    if (!dataOk) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.setup_data_incomplete),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Step.SYNC -> {
                    HintText(stringResource(R.string.setup_sync_intro))
                    SyncOptionsEditor(draft, vm::editDraft)
                }
                Step.BACKUP -> {
                    HintText(stringResource(R.string.setup_backup_intro))
                    BackupEditor(draft, vm::editDraft, pickFolder)
                }
                Step.ACCESS -> {
                    HintText(stringResource(R.string.setup_access_intro))
                    AccessEditor(draft, vm, authStates)
                }
            }
        }
    }
}

@Composable
private fun ImportStep(vm: MainViewModel, draft: AppSettings) {
    val scope = rememberCoroutineScope()
    var imported by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            vm.readSettingsFile(uri)
                .onSuccess { settings ->
                    vm.editDraft { settings.copy(setupCompleted = false) }
                    imported = true
                    vm.message("Impostazioni importate: controllale nei passaggi successivi")
                }
                .onFailure { vm.message("Importazione non riuscita: ${it.message}") }
        }
    }
    Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.setup_welcome_body), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(24.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.setup_import_title), style = MaterialTheme.typography.titleMedium)
            HintText(stringResource(R.string.setup_import_body))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { launcher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
                Text(stringResource(R.string.action_import_settings))
            }
            if (imported) {
                Spacer(Modifier.height(12.dp))
                ImportSummary(draft)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    HintText(stringResource(R.string.setup_import_skip))
}

@Composable
private fun ImportSummary(settings: AppSettings) {
    val context = LocalContext.current
    Text(stringResource(R.string.setup_import_done), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(R.string.setup_import_account, settings.accountName ?: "—"),
        style = MaterialTheme.typography.bodySmall,
    )
    val types = SyncType.entries.filter { settings.target(it).enabled }
    Text(
        stringResource(R.string.setup_import_types, types.map { it.label() }.joinToString(", ").ifEmpty { "—" }),
        style = MaterialTheme.typography.bodySmall,
    )
    if (types.any { !settings.folderReady(context, it) } ||
        (settings.settingsBackupEnabled && !SafFolder.hasPermission(context, settings.settingsBackupFolderUri))
    ) {
        Text(
            stringResource(R.string.setup_import_folders_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
