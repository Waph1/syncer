package io.github.waph1.syncer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.ui.home.HomeScreen
import io.github.waph1.syncer.ui.settings.SettingsScreen
import io.github.waph1.syncer.ui.setup.SetupScreen
import io.github.waph1.syncer.ui.theme.SyncerTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncerTheme { SyncerRoot(vm) }
        }
        if (savedInstanceState == null) handleAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    /** "Importa ora" in the password reminder notification. */
    private fun handleAction(intent: Intent?) {
        if (intent?.action != ACTION_IMPORT_PASSWORDS) return
        appContainer.notifier.clearPasswordReminder()
        vm.importPasswordsFromGoogle(this)
    }

    companion object {
        const val ACTION_IMPORT_PASSWORDS = "io.github.waph1.syncer.action.IMPORT_PASSWORDS"
    }
}

private enum class Screen { HOME, SETTINGS, SETUP }

@Composable
private fun SyncerRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    if (!settings.setupCompleted) {
        // First run (or setup never finished): the wizard is the only way in.
        SetupScreen(vm, onCancel = null, onFinished = { screen = Screen.HOME })
        return
    }
    when (screen) {
        Screen.HOME -> HomeScreen(vm, onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> SettingsScreen(
            vm,
            onBack = { screen = Screen.HOME },
            onRestartSetup = {
                vm.startSetup()
                screen = Screen.SETUP
            },
        )
        Screen.SETUP -> SetupScreen(vm, onCancel = { screen = Screen.SETTINGS }, onFinished = { screen = Screen.HOME })
    }
}
