package io.github.waph1.syncer.ui.components

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.waph1.syncer.R
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.NotesSource
import io.github.waph1.syncer.source.GoogleAuth
import io.github.waph1.syncer.ui.AuthState
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.util.AppInfo
import io.github.waph1.syncer.util.Permissions

/** Android permissions, Google API authorizations and battery optimization status. */
@Composable
fun AccessEditor(settings: AppSettings, vm: MainViewModel, authStates: Map<String, AuthState>) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val requestPermissions = rememberPermissionRequester { refresh++ }
    val authorize = rememberGoogleAuthorizer(vm)

    // Re-read permission states when returning to the app or after a permission request.
    val required = Permissions.required(settings)
    val grants = remember(refresh, required) { required.associateWith { Permissions.granted(context, it) } }
    val batteryOk = remember(refresh) { AppInfo.isIgnoringBatteryOptimizations(context) }

    Column {
        for (permission in required) {
            val granted = grants[permission] == true
            StatusRow(
                title = stringResource(
                    when (permission) {
                        Manifest.permission.READ_CALENDAR -> R.string.permission_calendar
                        Manifest.permission.READ_CONTACTS -> R.string.permission_contacts
                        else -> R.string.permission_notifications
                    },
                ),
                ok = granted,
                detail = stringResource(if (granted) R.string.status_granted else R.string.status_not_granted),
                action = if (granted) null else stringResource(R.string.action_grant),
                onAction = { requestPermissions(listOf(permission)) },
            )
        }

        val account = settings.accountName
        val scopes = buildList {
            if (settings.tasks.enabled) add(GoogleAuth.SCOPE_TASKS to R.string.auth_tasks)
            if (settings.notes.enabled && settings.notesSource == NotesSource.KEEP_API) add(GoogleAuth.SCOPE_KEEP to R.string.auth_keep)
        }
        if (account != null) {
            for ((scope, label) in scopes) {
                val state = authStates[scope] ?: AuthState.Unknown
                StatusRow(
                    title = stringResource(label),
                    ok = state == AuthState.Granted,
                    loading = state == AuthState.Checking,
                    detail = when (state) {
                        AuthState.Granted -> stringResource(R.string.status_authorized)
                        AuthState.Checking -> stringResource(R.string.status_checking)
                        is AuthState.NeedsConsent -> stringResource(R.string.status_needs_consent)
                        is AuthState.Failed -> state.message
                        AuthState.Unknown -> stringResource(R.string.status_unknown)
                    },
                    action = if (state == AuthState.Granted) null else stringResource(R.string.action_authorize),
                    onAction = { authorize(account, scope) },
                )
            }
            if (scopes.isNotEmpty()) HintText(stringResource(R.string.auth_hint))
        }

        StatusRow(
            title = stringResource(R.string.battery_title),
            ok = batteryOk,
            warnIfNotOk = false,
            detail = stringResource(if (batteryOk) R.string.battery_ok else R.string.battery_hint),
            action = if (batteryOk) null else stringResource(R.string.action_open_settings),
            onAction = { runCatching { context.startActivity(AppInfo.batteryOptimizationSettings()) } },
        )
    }
}

@Composable
fun StatusRow(
    title: String,
    ok: Boolean,
    detail: String,
    action: String?,
    onAction: () -> Unit,
    loading: Boolean = false,
    warnIfNotOk: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            ok -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            else -> Icon(
                Icons.Filled.Warning, null, Modifier.size(20.dp),
                tint = if (warnIfNotOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}
