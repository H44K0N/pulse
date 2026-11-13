package dev.tischbein.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tischbein.pulse.ui.PulseApp
import dev.tischbein.pulse.ui.hasBluetoothPermissions
import dev.tischbein.pulse.ui.theme.PulseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as PulseApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PulseTheme {
                PulseApp(container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Auto-connect to the remembered strap whenever the app is visible.
        if (hasBluetoothPermissions(this)) container.appScope.launch { container.sensors.autoConnect() }
    }

    override fun onStop() {
        super.onStop()
        // Not visible and no session: let go of the strap (its battery and ours). During a
        // session the foreground service needs the connection, so it stays.
        if (container.activeSession.value == null && !isChangingConfigurations && hasBluetoothPermissions(this)) {
            container.sensors.disconnect()
        }
    }
}
