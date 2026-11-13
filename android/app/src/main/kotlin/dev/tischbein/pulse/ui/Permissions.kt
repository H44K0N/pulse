package dev.tischbein.pulse.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/*
 * Runtime permissions (Android asks the user; the manifest alone is not enough).
 *
 * Required: BLUETOOTH_SCAN (find the strap) and BLUETOOTH_CONNECT (talk to it; also the
 * system's prerequisite for starting a "connectedDevice" foreground service on Android 14+,
 * which is why even simulator sessions need it).
 * Optional: POST_NOTIFICATIONS. Without it the session still runs, but its notification
 * (HR, phase, Pause/End) is hidden.
 *
 * Android only shows its permission dialog a limited number of times: after the user denies
 * twice (or ticks "don't ask again"), requesting again silently fails. Then the only way is
 * the app's page in system Settings, so the screen offers a button that opens it.
 */

val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun hasBluetoothPermissions(context: Context): Boolean = BLUETOOTH_PERMISSIONS.all { granted(context, it) }

fun hasNotificationPermission(context: Context): Boolean = granted(context, Manifest.permission.POST_NOTIFICATIONS)

/** Opens this app's page in system Settings (permissions, battery, notifications). */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
fun PermissionsScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var blocked by remember { mutableStateOf(false) }
    var deniedOnce by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasBluetoothPermissions(context)) {
                onGranted()
            } else {
                deniedOnce = true
                // No rationale + not granted right after asking = Android will not ask again.
                blocked =
                    BLUETOOTH_PERMISSIONS.any { p ->
                        !granted(context, p) && activity?.shouldShowRequestPermissionRationale(p) == false
                    }
            }
        }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Before we start", style = MaterialTheme.typography.headlineMedium)
        Text("Pulse needs a few permissions to coach you:")
        Text(
            "• Nearby devices (Bluetooth): to find and connect to your Polar H10. " +
                "Pulse does not use Bluetooth to determine your location.",
        )
        Text(
            "• Notifications (recommended): shows heart rate and phase while a session runs " +
                "with the screen off, with Pause and End buttons.",
        )
        if (deniedOnce) {
            Text(
                "Without the Bluetooth permission Pulse cannot read your heart rate, and Android " +
                    "does not allow it to run a workout in the background.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (blocked) {
            Text(
                "Android will not ask again. Open Settings > Permissions > Nearby devices and " +
                    "choose Allow, then come back.",
            )
            Button(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        } else {
            Button(onClick = { launcher.launch(BLUETOOTH_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Continue")
            }
        }
        if (deniedOnce && !blocked) {
            OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
        }
    }
}
