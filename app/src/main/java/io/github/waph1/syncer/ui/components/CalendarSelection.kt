package io.github.waph1.syncer.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.waph1.syncer.R
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.source.CalendarSource
import io.github.waph1.syncer.ui.CalendarStatus
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.util.Permissions

/**
 * The account's calendars as Android stores them: whether their sync is on, how many events
 * are on the device, and a checkbox to include them in the export.
 */
@Composable
fun CalendarSelection(
    vm: MainViewModel,
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val account = settings.accountName ?: return
    val context = LocalContext.current
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val canRead = remember(refresh) { Permissions.granted(context, Manifest.permission.READ_CALENDAR) }
    LaunchedEffect(account, canRead, refresh) { if (canRead) vm.refreshCalendars(account) }
    val requestRead = rememberPermissionRequester { refresh++ }
    var pendingEnable by remember { mutableStateOf<CalendarSource.CalendarRef?>(null) }
    val requestWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val calendar = pendingEnable
        pendingEnable = null
        if (granted && calendar != null) vm.enableCalendarSync(account, calendar)
    }

    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.calendars_title), style = MaterialTheme.typography.labelLarge)
        if (!canRead) {
            HintText(stringResource(R.string.calendars_need_permission))
            OutlinedButton(onClick = { requestRead(listOf(Manifest.permission.READ_CALENDAR)) }) {
                Text(stringResource(R.string.action_grant))
            }
            return@Column
        }
        val list = calendars
        when {
            list == null -> HintText(stringResource(R.string.calendars_loading))
            list.isEmpty() -> HintText(stringResource(R.string.calendars_none, account))
            else -> for (calendar in list) {
                CalendarRow(
                    calendar = calendar,
                    included = calendar.ref.key !in settings.excludedCalendars,
                    onIncludedChange = { on ->
                        onChange { s ->
                            s.copy(excludedCalendars = if (on) s.excludedCalendars - calendar.ref.key else s.excludedCalendars + calendar.ref.key)
                        }
                    },
                    onEnableSync = {
                        if (Permissions.granted(context, Manifest.permission.WRITE_CALENDAR)) {
                            vm.enableCalendarSync(account, calendar.ref)
                        } else {
                            pendingEnable = calendar.ref
                            requestWrite.launch(Manifest.permission.WRITE_CALENDAR)
                        }
                    },
                )
            }
        }
        HintText(stringResource(R.string.calendars_hint))
    }
}

@Composable
private fun CalendarRow(
    calendar: CalendarStatus,
    included: Boolean,
    onIncludedChange: (Boolean) -> Unit,
    onEnableSync: () -> Unit,
) {
    val syncOff = calendar.ref.syncEvents == false
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = included, role = Role.Checkbox, onValueChange = onIncludedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = included, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(calendar.ref.name, style = MaterialTheme.typography.bodyMedium)
            val events = pluralStringResource(R.plurals.calendar_events_on_device, calendar.events, calendar.events)
            val status = buildString {
                append(
                    when (calendar.ref.syncEvents) {
                        false -> stringResource(R.string.calendar_sync_off)
                        null -> stringResource(R.string.calendar_sync_unknown)
                        true -> stringResource(R.string.calendar_sync_on)
                    },
                )
                append(" · ").append(events)
                if (!calendar.ref.visible) append(" · ").append(stringResource(R.string.calendar_hidden))
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (syncOff && included) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (syncOff) {
        TextButton(onClick = onEnableSync, modifier = Modifier.padding(start = 40.dp)) {
            Text(stringResource(R.string.action_enable_sync))
        }
    }
}
