/*
 * New file for SamSU: the strategy chain that was implicit across Root-My-Galaxy
 * Extended's ShizukuBootService and PostRootAutomation, expressed once so the
 * boot service, the post-root hook and the manual button all share it.
 * Bouncy/structural credit: igorcv88/Root-My-Galaxy-Extended (Apache-2.0).
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context
import dev.indevelopment.m3qroot.RootShellBridge
import dev.indevelopment.m3qroot.ShizukuBridge

/** Result of an auto-start attempt, for display and for the log. */
data class ShizukuStartOutcome(
    val started: Boolean,
    val method: String,
    val detail: String,
)

/**
 * Starts Shizuku from the strongest transport available in this boot.
 *
 * Order matters and mirrors Root My Galaxy: an existing Binder first, then the
 * KernelSU root shell (only present in a boot where a root run already
 * succeeded), then the app's own paired local ADB shell, then the
 * authenticated Intent exposed by the Shizuku fork. Every step is serialized by
 * [ShizukuStartCoordinator], so a boot-time attempt and a manual press cannot
 * launch two servers.
 */
object ShizukuAutoStart {

    /** Blocking entry point for Java callers; run it on a worker thread. */
    fun startBlocking(
        context: Context,
        reporter: StartReporter?,
    ): ShizukuStartOutcome = kotlinx.coroutines.runBlocking {
        start(context) { line -> reporter?.onLine(line) }
    }

    suspend fun start(
        context: Context,
        onLog: (String) -> Unit = {},
    ): ShizukuStartOutcome {
        if (ShizukuBinderWait.pingUntilRunning(BINDER_PROBE_MILLIS)) {
            onLog("[+] Shizuku is already running")
            return ShizukuStartOutcome(true, "existing-binder", "Shizuku is already running")
        }

        var rootOutcome: ShizukuStarter.Outcome? = null
        if (RootShellBridge.isRootActive(context)) {
            onLog("[*] KernelSU root is active; trying the root shell for the Shizuku starter")
            rootOutcome = runCatching {
                ShizukuStarter.start(
                    context = context,
                    shell = rootShell(context),
                    binderTimeoutMillis = ROOT_BINDER_TIMEOUT_MILLIS,
                    onLog = onLog,
                )
            }.onFailure {
                onLog("[!] Root-shell Shizuku start failed: ${it.message ?: it.javaClass.simpleName}")
            }.getOrNull()
            if (rootOutcome?.started == true) {
                return ShizukuStartOutcome(
                    true,
                    rootOutcome.method ?: "root-shell",
                    "started through the KernelSU root shell",
                )
            }
        } else {
            onLog("[*] No KernelSU root in this boot; the starter needs a shell transport")
        }

        val adbOutcome = startViaLocalAdb(context, onLog)
        if (adbOutcome?.started == true) {
            return ShizukuStartOutcome(
                true,
                "local-adb",
                "started through the paired local ADB shell",
            )
        }

        val intentOutcome = ShizukuIntentStarter.start(
            context = context,
            binderTimeoutMillis = INTENT_BINDER_TIMEOUT_MILLIS,
            onLog = onLog,
        )
        if (intentOutcome.started) {
            return ShizukuStartOutcome(
                true,
                "authenticated-intent",
                "started through the Shizuku automation interface",
            )
        }

        val detail = listOfNotNull(
            rootOutcome?.detail,
            adbOutcome?.detail,
            intentOutcome.detail,
        ).firstOrNull { it.isNotBlank() }
            ?: "no available transport could start Shizuku"
        onLog("[!] Shizuku auto-start failed: $detail")
        return ShizukuStartOutcome(false, "", detail)
    }

    private suspend fun startViaLocalAdb(
        context: Context,
        onLog: (String) -> Unit,
    ): ShizukuStarter.Outcome? {
        if (!AdbCredentialStore.hasStoredKey(context)) {
            onLog("[*] No local ADB key is stored; skipping the local ADB starter")
            return null
        }
        return try {
            // Enables Wireless Debugging for the duration of the start, then
            // hands it back off once the session closes.
            WirelessAdbSession.open(context, PORT_DISCOVERY_TIMEOUT_MILLIS).use { session ->
                ShizukuStarter.start(
                    context = context,
                    shell = { command -> session.shell(command) },
                    binderTimeoutMillis = ADB_BINDER_TIMEOUT_MILLIS,
                    onLog = onLog,
                )
            }
        } catch (error: Throwable) {
            onLog(
                "[!] Local ADB starter unavailable: " +
                    (error.message ?: error.javaClass.simpleName),
            )
            null
        }
    }

    private fun rootShell(context: Context): (String) -> LocalAdbClient.ShellResult = { command ->
        val result = RootShellBridge.run(context, command, ROOT_COMMAND_TIMEOUT_SECONDS)
        LocalAdbClient.ShellResult(result.exitCode, result.output)
    }

    /** Channel an [onLog] line is forwarded to, for Java callers. */
    fun interface StartReporter {
        fun onLine(line: String)
    }

    internal fun isRunning(): Boolean = ShizukuBridge.isRunning()

    private const val BINDER_PROBE_MILLIS = 500L
    private const val ROOT_BINDER_TIMEOUT_MILLIS = 8_000L
    private const val ADB_BINDER_TIMEOUT_MILLIS = 12_000L
    private const val INTENT_BINDER_TIMEOUT_MILLIS = 8_000L
    private const val PORT_DISCOVERY_TIMEOUT_MILLIS = 30_000L
    private const val ROOT_COMMAND_TIMEOUT_SECONDS = 20
}
