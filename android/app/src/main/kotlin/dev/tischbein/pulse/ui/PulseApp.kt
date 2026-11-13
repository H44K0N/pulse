package dev.tischbein.pulse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.AppContainer
import kotlinx.coroutines.launch

private enum class Screen { HOME, SETTINGS }

/**
 * Top-level screen switch. Deliberately no navigation library: four screens, chosen by state.
 * A running session always shows the session screen.
 */
@Composable
fun PulseApp(container: AppContainer) {
    val context = LocalContext.current
    var bluetoothGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    // Re-check on every resume: the user may have changed permissions in system Settings.
    LifecycleResumeEffect(Unit) {
        bluetoothGranted = hasBluetoothPermissions(context)
        onPauseOrDispose { }
    }
    val active by container.activeSession.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.HOME }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val session = active
            when {
                !bluetoothGranted ->
                    PermissionsScreen(onGranted = {
                        bluetoothGranted = true
                        container.appScope.launch { container.sensors.autoConnect() }
                    })
                session != null -> SessionScreen(session)
                screen == Screen.SETTINGS -> SettingsScreen(container, onDone = { screen = Screen.HOME })
                else -> HomeScreen(container, onOpenSettings = { screen = Screen.SETTINGS })
            }
        }
    }
}
