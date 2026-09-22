package io.github.waph1.syncer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.waph1.syncer.ui.MainViewModel
import io.github.waph1.syncer.ui.home.HomeScreen
import io.github.waph1.syncer.ui.settings.SettingsScreen
import io.github.waph1.syncer.ui.setup.SetupScreen
import io.github.waph1.syncer.ui.theme.SyncerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncerTheme { SyncerRoot() }
        }
    }
}

private enum class Screen { HOME, SETTINGS, SETUP }

@Composable
private fun SyncerRoot(vm: MainViewModel = viewModel()) {
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
