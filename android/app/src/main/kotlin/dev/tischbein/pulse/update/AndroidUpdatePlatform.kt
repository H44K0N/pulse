package dev.tischbein.pulse.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.tischbein.pulse.PulseApplication
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * [UpdatePlatform] on Android: "Install unknown apps", the signing certificates of the installed
 * app and of the downloaded file, and a PackageInstaller session (not the deprecated install
 * intents).
 */
class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    private val pm get() = context.packageManager

    override fun canInstallPackages(): Boolean = runCatching { pm.canRequestPackageInstalls() }.getOrDefault(false)

    override fun installed(): InstalledApp {
        val info = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        return InstalledApp(info.packageName, info.longVersionCode, signers(info))
    }

    override fun inspect(file: File): ApkFacts? {
        val info =
            runCatching {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            }.getOrNull() ?: return null
        return ApkFacts(info.packageName, info.longVersionCode, signers(info))
    }

    override fun install(file: File): String? {
        val installer = pm.packageInstaller
        abandonPending() // an older session of ours that was never finished
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                // Only ever reached after his tap on Update. Android may then skip its own dialog
                // where it allows that for an app updating itself (UPDATE_PACKAGES_WITHOUT_USER_ACTION);
                // where the conditions do not hold, it shows the dialog.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("pulse.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, BUFFER) }
                    session.fsync(out)
                }
                session.commit(statusReceiver(sessionId).intentSender)
            }
            null
        } catch (e: IOException) {
            Log.w(TAG, "install session failed: ${e.javaClass.simpleName}")
            abandon(installer, sessionId)
            UpdateTexts.NO_SPACE
        } catch (e: RuntimeException) {
            // SecurityException (permission withdrawn meanwhile), IllegalStateException, ...
            Log.w(TAG, "install session failed: ${e.javaClass.simpleName}")
            abandon(installer, sessionId)
            UpdateTexts.START_FAILED
        }
    }

    override fun abandonPending() {
        val installer = pm.packageInstaller
        runCatching { installer.mySessions }.getOrDefault(emptyList()).forEach { abandon(installer, it.sessionId) }
    }

    private fun abandon(
        installer: PackageInstaller,
        id: Int,
    ) {
        if (id >= 0) runCatching { installer.abandonSession(id) }
    }

    /**
     * Where Android reports the session's status. Mutable, because PackageInstaller adds the
     * status extras to it; explicit (this app's own, not exported receiver), so nobody else can
     * receive or fill it.
     */
    private fun statusReceiver(sessionId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, UpdateStatusReceiver::class.java).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    companion object {
        private const val TAG = "Pulse/Update"
        private const val BUFFER = 64 * 1024

        /** Android's "Install unknown apps" page for Pulse. */
        fun unknownAppsSettings(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

        /** SHA-256 (hex) of the certificates the package is signed with now; empty when not readable. */
        fun signers(info: PackageInfo): Set<String> {
            val si = info.signingInfo ?: return emptySet()
            val certs =
                if (si.hasMultipleSigners()) {
                    si.apkContentsSigners?.toList().orEmpty()
                } else {
                    // Rotation history: the current certificate is the last one.
                    listOfNotNull(si.signingCertificateHistory?.lastOrNull())
                }
            return certs.map { HttpApkDownloader.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        }
    }
}

/** The PackageInstaller session's answers, handed to the update controller. Not exported. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        (context.applicationContext as PulseApplication).container.update.onSessionStatus(status, message, confirm)
    }
}

/** [UpdateMemory] in SharedPreferences (one small value; survives Android stopping the app). */
class PrefsUpdateMemory(context: Context) : UpdateMemory {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    override var permissionAskedFor: Long
        get() = prefs.getLong(KEY, 0L)
        set(value) = prefs.edit { putLong(KEY, value) }

    private companion object {
        const val KEY = "permission_asked_for"
    }
}
