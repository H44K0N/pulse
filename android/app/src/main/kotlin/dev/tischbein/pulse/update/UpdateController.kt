package dev.tischbein.pulse.update

import dev.tischbein.pulse.AppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What Android does for the update (AndroidUpdatePlatform; a fake in tests). */
interface UpdatePlatform {
    /** "Install unknown apps" is allowed for Pulse (PackageManager.canRequestPackageInstalls). */
    fun canInstallPackages(): Boolean

    fun installed(): InstalledApp

    /** The downloaded file read as an APK, or null when it is not one. */
    fun inspect(file: File): ApkFacts?

    /**
     * Writes [file] into a PackageInstaller session and commits it. The result arrives later
     * through [UpdateController.onSessionStatus]. Returns a sentence when the session could not
     * even be started, else null.
     */
    fun install(file: File): String?

    /** Gives up this app's install sessions that are still open or waiting for his answer. */
    fun abandonPending()
}

/** Survives the process: he tapped Update for this version and was sent to "Install unknown apps". */
interface UpdateMemory {
    var permissionAskedFor: Long
}

/** Where the update is. One at a time. */
sealed interface UpdatePhase {
    /** The banner offers "Update". */
    data object Ready : UpdatePhase

    /** Android needs "Install unknown apps" for Pulse first. */
    data object AskPermission : UpdatePhase

    data class Downloading(val percent: Int) : UpdatePhase

    /** Size, hash, package, version and signature are being checked. */
    data object Checking : UpdatePhase

    /** The file is here and right, but a session started meanwhile: Update installs it afterwards. */
    data object Downloaded : UpdatePhase

    /** Handed to Android (its confirmation, then it installs and stops Pulse). */
    data object Installing : UpdatePhase

    data class Failed(val reason: String) : UpdatePhase
}

data class UpdateView(val offer: UpdateOffer?, val phase: UpdatePhase = UpdatePhase.Ready) {
    val working: Boolean get() = phase is UpdatePhase.Downloading || phase == UpdatePhase.Checking || phase == UpdatePhase.Installing
}

/**
 * The in-app update, from the check to Android's installer. Nothing is downloaded or installed
 * without his tap on Update ([tap]); the check only shows the banner. Lives as long as the
 * process ([scope]), so a download goes on while he is on another screen.
 *
 * Never during a session ([gate]): no check, no download start, no install, and the UI shows no
 * banner. A download that is already running finishes quietly and waits ([UpdatePhase.Downloaded]).
 *
 * Files live in [dir]: `pulse-<code>.part` while downloading, `pulse-<code>.apk` once its size and
 * hash are right (the next tap reuses it instead of downloading again).
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val dir: File,
    private val server: UpdateServer,
    private val platform: UpdatePlatform,
    private val memory: UpdateMemory,
    private val gate: SessionGate,
    private val nowMs: () -> Long,
) {
    private val _state = MutableStateFlow(UpdateView(null))
    val state: StateFlow<UpdateView> = _state.asStateFlow()

    /** Android's confirmation activity (an Intent), waiting for the visible activity to launch it. */
    private val _confirm = MutableStateFlow<Any?>(null)
    val confirm: StateFlow<Any?> = _confirm.asStateFlow()

    private val checking = Mutex()
    private var lastAnswerMs: Long? = null
    private var lastFailureMs: Long? = null
    private var job: Job? = null
    private var installTimeout: Job? = null

    /** Pulse came to the front: check (if due), then carry on after "Install unknown apps". */
    fun onForeground() {
        scope.launch(io) {
            checkIfDue()
            continueAfterPermission()
        }
    }

    /**
     * Asks the server, at most about once an hour and never during a session. Offline, a wrong
     * token or a server error change nothing on screen. No sync set up: no request, no banner.
     */
    suspend fun checkIfDue() {
        if (!checking.tryLock()) return
        try {
            if (!UpdateRules.checkDue(nowMs(), lastAnswerMs, lastFailureMs, gate.running.value)) return
            when (val r = server.check()) {
                ReleaseCheck.NotConfigured -> {
                    lastAnswerMs = null
                    apply(null)
                }
                ReleaseCheck.NothingPublished -> {
                    lastAnswerMs = nowMs()
                    apply(null)
                }
                is ReleaseCheck.Published -> {
                    lastAnswerMs = nowMs()
                    apply(UpdateRules.offer(r.release, platform.installed().versionCode))
                }
                is ReleaseCheck.Failed -> lastFailureMs = nowMs()
            }
        } finally {
            checking.unlock()
        }
    }

    /** The server URL or token changed: forget the offer; the next time Pulse is in front it asks again. */
    fun onServerChanged() {
        lastAnswerMs = null
        lastFailureMs = null
        if (!_state.value.working) {
            _state.value = UpdateView(null)
            scope.launch(io) { clearFiles(keep = null) }
        }
    }

    private fun apply(offer: UpdateOffer?) {
        _state.update { s ->
            when {
                s.working -> s // finish what was started
                offer == null -> UpdateView(null)
                offer.versionCode != s.offer?.versionCode ->
                    UpdateView(offer, if (memory.permissionAskedFor == offer.versionCode && !platform.canInstallPackages()) UpdatePhase.AskPermission else UpdatePhase.Ready)
                else -> s.copy(offer = offer)
            }
        }
        if (_state.value.offer == null && !_state.value.working) clearFiles(keep = null)
    }

    /** His tap on Update (banner or Settings) and on "Try again". */
    fun tap() {
        if (gate.running.value) return
        val s = _state.value
        val offer = s.offer ?: return
        if (s.working) return
        if (!platform.canInstallPackages()) {
            memory.permissionAskedFor = offer.versionCode
            setPhase(UpdatePhase.AskPermission)
            return
        }
        start(offer)
    }

    /** Back from "Install unknown apps" with it allowed: carry on by itself (he already tapped Update). */
    fun continueAfterPermission() {
        val s = _state.value
        val offer = s.offer ?: return
        if (s.working || gate.running.value) return
        val asked = s.phase == UpdatePhase.AskPermission || memory.permissionAskedFor == offer.versionCode
        if (asked && platform.canInstallPackages()) start(offer)
    }

    /** The PackageInstaller session's status ([confirmIntent]: Intent.EXTRA_INTENT, if any). */
    fun onSessionStatus(
        status: Int,
        message: String?,
        confirmIntent: Any?,
    ) {
        val installing = _state.value.phase == UpdatePhase.Installing
        when (val outcome = UpdateRules.sessionOutcome(status, message)) {
            SessionOutcome.Confirm ->
                when {
                    // Timed out meanwhile, or a stale session from before: nobody asked for this one now.
                    !installing -> platform.abandonPending()
                    confirmIntent == null -> setPhase(UpdatePhase.Failed(UpdateTexts.NO_CONFIRM))
                    else -> _confirm.value = confirmIntent
                }
            SessionOutcome.Installed -> setPhase(UpdatePhase.Ready)
            SessionOutcome.Cancelled -> if (installing) setPhase(UpdatePhase.Ready)
            is SessionOutcome.Failed -> if (installing) setPhase(UpdatePhase.Failed(outcome.reason))
        }
    }

    /** The visible activity launched [confirm]: he has [AppConstants.UPDATE_INSTALL_TIMEOUT_MS] to answer. */
    fun confirmShown() {
        _confirm.value = null
        armInstallTimeout()
    }

    /** Android's confirmation could not be opened. */
    fun confirmFailed() {
        _confirm.value = null
        platform.abandonPending()
        if (_state.value.phase == UpdatePhase.Installing) setPhase(UpdatePhase.Failed(UpdateTexts.NO_CONFIRM))
    }

    private fun start(offer: UpdateOffer) {
        if (job?.isActive == true || gate.running.value) return
        memory.permissionAskedFor = 0
        setPhase(UpdatePhase.Downloading(0))
        job =
            scope.launch(io) {
                val problem =
                    try {
                        fetchCheckInstall(offer)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        UpdateTexts.OFFLINE
                    }
                if (problem != null) setPhase(UpdatePhase.Failed(problem))
            }
    }

    /** Null when handed to Android (or waiting for the session to end), else the sentence for the banner. */
    private suspend fun fetchCheckInstall(offer: UpdateOffer): String? {
        val expected = offer.expected
        dir.mkdirs()
        val apk = File(dir, "pulse-${offer.versionCode}.apk")
        clearFiles(keep = apk.name)
        val reuse = apk.isFile && apk.length() == expected.size && HttpApkDownloader.sha256(apk) == expected.sha256
        if (!reuse) {
            apk.delete()
            val part = File(dir, "pulse-${offer.versionCode}.part")
            var shown = 0
            val r =
                server.download(offer, part) { bytes ->
                    val pct = (bytes * 100 / expected.size).toInt().coerceIn(0, 100)
                    if (pct != shown) {
                        shown = pct
                        setPhase(UpdatePhase.Downloading(pct))
                    }
                }
            val problem =
                when (r) {
                    is DownloadResult.Failed -> r.reason
                    is DownloadResult.Done -> UpdateRules.fileProblem(expected, r.size, r.sha256)
                }
            if (problem != null || !part.renameTo(apk)) {
                part.delete()
                return problem ?: UpdateTexts.NO_SPACE
            }
        }
        // A session started while it downloaded: keep the file, install only after his next tap.
        if (gate.running.value) return waitForSessionEnd()
        setPhase(UpdatePhase.Checking)
        UpdateRules.apkProblem(platform.inspect(apk), platform.installed())?.let {
            apk.delete()
            return it
        }
        if (!gate.tryBeginInstall()) return waitForSessionEnd()
        setPhase(UpdatePhase.Installing)
        armInstallTimeout()
        return platform.install(apk)
    }

    private fun waitForSessionEnd(): String? {
        setPhase(UpdatePhase.Downloaded)
        return null
    }

    /**
     * An install that never reports back (he left Android's dialog unanswered, or the app was in
     * the background when it came) must not block Start forever: after the timeout the session
     * is abandoned and the banner offers Update again, with the file kept.
     */
    private fun armInstallTimeout() {
        installTimeout?.cancel()
        installTimeout =
            scope.launch {
                delay(AppConstants.UPDATE_INSTALL_TIMEOUT_MS)
                if (_state.value.phase == UpdatePhase.Installing) {
                    platform.abandonPending()
                    _confirm.value = null
                    setPhase(UpdatePhase.Downloaded)
                }
            }
    }

    private fun setPhase(phase: UpdatePhase) {
        if (phase != UpdatePhase.Installing) {
            gate.endInstall()
            if (phase !is UpdatePhase.Downloading && phase != UpdatePhase.Checking) installTimeout?.cancel()
        }
        _state.update { it.copy(phase = phase) }
    }

    private fun clearFiles(keep: String?) {
        dir.listFiles()?.filter { it.name != keep }?.forEach { it.delete() }
    }
}
