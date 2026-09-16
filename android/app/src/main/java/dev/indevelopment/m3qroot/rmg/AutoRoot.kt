/*
 * Adapted from Root-My-Galaxy-Extended's AutoRoot support files
 * (AutoRootSupport.kt, AutoRootRunner.kt) by igorcv88, which derives from
 * BuSung-dev/Root-My-Galaxy. Apache License 2.0.
 *
 * Adapted to SamSU in one important way: Root My Galaxy gates unattended root on
 * an install receipt plus its known-good payload cache. SamSU's equivalent is
 * Tier 1's pinned payload baseline, so Auto Root refuses to run unless the exact
 * artifact it would execute has already been verified on this exact
 * firmware/kernel by a previous successful run. An unattended kernel write on a
 * build that has never rooted once is not something this app should do.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context
import android.content.pm.PackageManager
import dev.indevelopment.m3qroot.AutoRootSession
import dev.indevelopment.m3qroot.RootSafetyPolicy
import dev.indevelopment.m3qroot.ShizukuBridge
import java.io.File
import kotlinx.coroutines.delay

data class AutoRootOutcome(
    val ran: Boolean,
    val rooted: Boolean,
    val detail: String,
)

object AutoRoot {
    private const val KSU_MANAGER_PACKAGE = "me.weishu.kernelsu"
    private const val REQUIRED_KSU_MANAGER_PREFIX = "3.2.5"
    private const val SETTLE_POLL_MILLIS = 5_000L

    fun isEnabled(context: Context): Boolean = AutomationPrefs.autoRoot(context)

    /**
     * Blocking entry point for Java callers; run it on a worker thread. The two
     * sinks receive log lines and short progress phrases respectively.
     */
    fun runBlocking(
        context: Context,
        session: AutoRootSession,
        log: LogSink?,
        progress: LogSink?,
    ): AutoRootOutcome = kotlinx.coroutines.runBlocking {
        run(
            context = context,
            session = session,
            onLog = { line -> log?.onLine(line) },
            onProgress = { text -> progress?.onLine(text) },
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        AutomationPrefs.setAutoRoot(context, enabled)
    }

    /**
     * Runs the gated sequence. Never throws: every refusal is reported as an
     * outcome so the service can show exactly why nothing happened.
     */
    suspend fun run(
        context: Context,
        session: AutoRootSession,
        onLog: (String) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ): AutoRootOutcome {
        var status = session.status()

        if (status.isRootActive) {
            onProgress("already rooted")
            return AutoRootOutcome(false, true, "KernelSU root is already active this boot")
        }
        if (!status.isDeviceSupported) {
            onProgress("unsupported device")
            return AutoRootOutcome(false, false, "no payload targets this device build")
        }
        if (status.isAttemptedThisBoot) {
            onProgress("attempt already spent")
            return AutoRootOutcome(
                false,
                false,
                "the single kernel attempt for this boot was already used; reboot to retry",
            )
        }
        if (!managerVersionOk(context)) {
            onProgress("KernelSU manager mismatch")
            return AutoRootOutcome(
                false,
                false,
                "KernelSU Manager $REQUIRED_KSU_MANAGER_PREFIX is required",
            )
        }

        // Artifacts are chosen before the integrity gate so the gate can judge
        // exactly what would run.
        val payloadId = session.prepareArtifacts()
        val payloadFile = session.getPayloadPath().takeIf { it.isNotEmpty() }?.let(::File)
        val ksudFile = session.getKsudPath().takeIf { it.isNotEmpty() }?.let(::File)
        val integrity = PayloadIntegrityStore(context).verify(payloadId, payloadFile, ksudFile)
        onLog(
            "[*] Auto Root integrity ($payloadId): ${integrity.verdict} - ${integrity.detail}",
        )
        session.setPayloadHash(integrity.exploitSha256)
        if (integrity.verdict != IntegrityVerdict.Verified) {
            onProgress("not verified on this build")
            return AutoRootOutcome(
                false,
                false,
                "these artifacts have not been verified on this firmware yet; " +
                    "root once by hand to establish the baseline",
            )
        }

        // Wait out the boot-settle gate, which is measured from kernel boot.
        while (true) {
            status = session.status()
            val remaining = status.settleRemainingMillis
            if (remaining <= 0L) break
            val label = RootSafetyPolicy.formatRemaining(remaining)
            onProgress("waiting $label")
            onLog("[*] Auto Root waiting $label for the kernel to settle")
            delay(minOf(remaining, SETTLE_POLL_MILLIS))
        }

        if (!session.claimAttempt()) {
            onProgress("attempt not claimed")
            return AutoRootOutcome(
                false,
                false,
                "the once-per-boot attempt could not be claimed",
            )
        }

        val useShizuku = ShizukuBridge.isShellOrRoot()
        onLog(
            "[*] Auto Root running the payload " +
                (if (useShizuku) "through the Shizuku shell" else "from the app process (P0 route)"),
        )
        onProgress("running the payload")
        val exitCode = session.runFreshRoot(useShizuku)
        onLog("[*] Auto Root payload exit=$exitCode")
        if (session.isAttemptTerminationUnconfirmed(exitCode)) {
            return AutoRootOutcome(
                true,
                false,
                "process control was lost; do not retry before rebooting",
            )
        }

        if (session.bootstrapReady()) {
            onProgress("activating KernelSU")
            onLog("[*] Auto Root finishing the KernelSU activation step")
            val activation = session.activateKernelSu()
            onLog("[*] Auto Root KernelSU activation exit=$activation")
        }

        val rooted = session.rootReady()
        onProgress(if (rooted) "rooted" else "did not root")
        return AutoRootOutcome(
            ran = true,
            rooted = rooted,
            detail = if (rooted) {
                "temporary root is active; KernelSU was activated"
            } else {
                "the payload ran but KernelSU root did not verify; reboot to retry"
            },
        )
    }

    private fun managerVersionOk(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(
            KSU_MANAGER_PACKAGE,
            PackageManager.PackageInfoFlags.of(0),
        )
        val version = info.versionName.orEmpty().removePrefix("v").removePrefix("V").trim()
        version.startsWith(REQUIRED_KSU_MANAGER_PREFIX)
    }.getOrDefault(false)
}
