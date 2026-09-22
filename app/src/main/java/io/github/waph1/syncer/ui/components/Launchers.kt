package io.github.waph1.syncer.ui.components

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.waph1.syncer.source.CalendarSource
import io.github.waph1.syncer.storage.SafFolder
import io.github.waph1.syncer.ui.AuthState
import io.github.waph1.syncer.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * Folder picker (ACTION_OPEN_DOCUMENT_TREE) shared by several targets: call the returned
 * function with a target key; [onPicked] receives that key and the persisted tree URI.
 */
@Composable
fun rememberFolderPicker(onPicked: (target: String, uri: String) -> Unit): (target: String, current: String?) -> Unit {
    val context = LocalContext.current
    var pendingTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val target = pendingTarget
        pendingTarget = null
        if (uri != null && target != null) {
            SafFolder.takePermission(context, uri)
            onPicked(target, uri.toString())
        }
    }
    return { target, current ->
        pendingTarget = target
        val initial = current?.toUri()?.let { tree ->
            runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()
        }
        launcher.launch(initial)
    }
}

/** System account chooser restricted to Google accounts; grants the app visibility of the chosen one. */
@Composable
fun rememberAccountPicker(onPicked: (String) -> Unit): (current: String?) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let(onPicked)
        }
    }
    return { current ->
        val selected = current?.let { Account(it, CalendarSource.GOOGLE_ACCOUNT_TYPE) }
        launcher.launch(
            AccountManager.newChooseAccountIntent(
                selected, null, arrayOf(CalendarSource.GOOGLE_ACCOUNT_TYPE), null, null, null, null,
            ),
        )
    }
}

/** Checks access to a Google API scope and, if needed, shows Google's consent screen. */
@Composable
fun rememberGoogleAuthorizer(vm: MainViewModel): (account: String, scope: String) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val (account, apiScope) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        scope.launch { vm.checkAuthorization(account, apiScope) }
    }
    return { account, apiScope ->
        scope.launch {
            when (val state = vm.checkAuthorization(account, apiScope)) {
                is AuthState.NeedsConsent -> state.pendingIntent?.let {
                    pending = account to apiScope
                    launcher.launch(IntentSenderRequest.Builder(it).build())
                }
                is AuthState.Failed -> vm.message(state.message)
                else -> Unit
            }
        }
    }
}

@Composable
fun rememberPermissionRequester(onResult: () -> Unit = {}): (List<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return { permissions -> if (permissions.isNotEmpty()) launcher.launch(permissions.toTypedArray()) }
}
