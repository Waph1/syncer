package io.github.waph1.syncer.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.waph1.syncer.BuildConfig
import io.github.waph1.syncer.R
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.ui.components.AccessEditor
import io.github.waph1.syncer.ui.components.BackupEditor
import io.github.waph1.syncer.ui.components.HintText
import io.github.waph1.syncer.ui.components.SectionHeader
import io.github.waph1.syncer.ui.components.SyncOptionsEditor
import io.github.waph1.syncer.ui.components.TargetsEditor
import io.github.waph1.syncer.ui.components.rememberAccountPicker
import io.github.waph1.syncer.ui.components.rememberFolderPicker
import io.github.waph1.syncer.ui.components.withPickedFolder
import io.github.waph1.syncer.util.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onRestartSetup: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val authStates by vm.auth.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(onBack = onBack)

    // Every committed change is saved immediately (and backed up, if enabled).
    val pickFolder = rememberFolderPicker { target, uri -> vm.update { it.withPickedFolder(target, uri) } }
    val pickAccount = rememberAccountPicker { name -> vm.update { it.copy(accountName = name) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.section_account))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(settings.accountName ?: stringResource(R.string.account_none), style = MaterialTheme.typography.bodyLarge)
                    HintText(stringResource(R.string.account_hint))
                }
                OutlinedButton(onClick = { pickAccount(settings.accountName) }) { Text(stringResource(R.string.action_change)) }
            }

            SectionHeader(stringResource(R.string.section_data))
            TargetsEditor(settings, vm::update, pickFolder)

            SectionHeader(stringResource(R.string.section_sync))
            SyncOptionsEditor(settings, vm::update)

            SectionHeader(stringResource(R.string.section_backup))
            BackupEditor(settings, vm::update, pickFolder)
            status.lastBackupAt?.let { at ->
                HintText(
                    stringResource(
                        R.string.backup_last,
                        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        status.lastBackupFile.orEmpty(),
                    ),
                )
            }
            status.lastBackupError?.let {
                Text(stringResource(R.string.backup_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row {
                if (settings.settingsBackupEnabled && settings.settingsBackupFolderUri != null) {
                    OutlinedButton(onClick = { vm.backupNow() }) { Text(stringResource(R.string.action_backup_now)) }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = onRestartSetup) { Text(stringResource(R.string.action_import_settings)) }
            }
            HintText(stringResource(R.string.import_from_settings_hint))

            SectionHeader(stringResource(R.string.section_access))
            AccessEditor(settings, vm, authStates)

            SectionHeader(stringResource(R.string.section_info))
            val sha1 = remember { AppInfo.signingSha1(context) }
            InfoLine(stringResource(R.string.info_version), BuildConfig.VERSION_NAME)
            InfoLine(stringResource(R.string.info_package), context.packageName)
            InfoLine(stringResource(R.string.info_sha1), sha1 ?: "—")
            HintText(stringResource(R.string.info_cloud_hint))
            val copied = stringResource(R.string.info_copied)
            Row {
                OutlinedButton(onClick = {
                    val text = "package: ${context.packageName}\nSHA-1: ${sha1.orEmpty()}"
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Syncer OAuth", text))
                    vm.message(copied)
                }) { Text(stringResource(R.string.action_copy)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRestartSetup) { Text(stringResource(R.string.action_restart_setup)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
    }
    HorizontalDivider()
}
