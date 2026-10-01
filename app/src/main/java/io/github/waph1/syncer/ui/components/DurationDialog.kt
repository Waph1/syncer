package io.github.waph1.syncer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.waph1.syncer.R
import io.github.waph1.syncer.format.DurationText
import java.time.Duration

/** "Ogni 2 settimane" for a saved free-text duration ([fallback] if it no longer parses). */
fun describeEvery(text: String, min: Duration, max: Duration, fallback: Duration): String =
    "Ogni " + DurationText.format(DurationText.durationOf(text, min, max) ?: fallback)

/** A setting row showing a free-text duration, with a button opening [DurationDialog]. */
@Composable
fun DurationRow(
    title: String,
    value: String,
    min: Duration,
    max: Duration,
    fallback: Duration,
    examples: String,
    onChange: (String) -> Unit,
    detail: String? = null,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                describeEvery(value, min, max, fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.action_change)) }
    }
    if (editing) {
        DurationDialog(
            title = title,
            current = value,
            min = min,
            max = max,
            examples = examples,
            onDismiss = { editing = false },
            onConfirm = {
                editing = false
                onChange(it)
            },
        )
    }
}

/** Free-text duration ("2 settimane", "36 ore", "1 mese e mezzo"...) with live interpretation. */
@Composable
fun DurationDialog(
    title: String,
    current: String,
    min: Duration,
    max: Duration,
    examples: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    val result = remember(text) { DurationText.parse(text, min, max) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.duration_label)) },
                    isError = result is DurationText.Invalid,
                    supportingText = {
                        Text(
                            when (result) {
                                is DurationText.Valid -> "Ogni " + DurationText.format(result.duration)
                                is DurationText.Invalid -> result.message
                            },
                        )
                    },
                )
                HintText(examples)
            }
        },
        confirmButton = {
            TextButton(enabled = result is DurationText.Valid, onClick = { onConfirm(text.trim()) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
