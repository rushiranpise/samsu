/*
 * Adapted from Root-My-Galaxy-Extended's PostRootAutomation.kt,
 * PostRootModuleKeeper.kt and RootRecoveryActions.kt (dev.busung.s25uroot) by
 * igorcv88, which derives from BuSung-dev/Root-My-Galaxy. Apache License 2.0.
 * Adapted to SamSU: the reboot delegates to the bundled KernelSU daemon's
 * native `soft-reboot` before falling back to a zygote restart, and Shizuku
 * startup reuses ShizukuAutoStart instead of a second chain.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context
import dev.indevelopment.m3qroot.RootShellBridge
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** An automation step that either happened or did not, with the reason. */
data class RecoveryResult(
    val accepted: Boolean,
    val method: String,
    val detail: String,
)

data class PostRootOutcome(
    val shizukuStarted: Boolean,
    val softRebootRequested: Boolean,
    val softRebootMethod: String,
    val detail: String,
)

/** Line sink for Java callers, mirroring [ShizukuAutoStart.StartReporter]. */
fun interface LogSink {
    fun onLine(line: String)
}

/**
 * What the README asks the user to do by hand after a successful root: start
 * Shizuku, reload the KernelSU module, then softly reboot so the modules mount
 * into a fresh Zygote.
 *
 * This runs only after KernelSU has been verified, never touches the exploit
 * path, and never replays late-load. The module reload stays with the engine,
 * which already confirms it through its own boot-completed marker.
 */
object PostRootAutomation {

    /** Blocking entry point for Java callers; run it on a worker thread. */
    fun runBlocking(
        context: Context,
        sink: LogSink?,
    ): PostRootOutcome = runBlocking { run(context) { line -> sink?.onLine(line) } }

    fun isConfigured(context: Context): Boolean = AutomationPrefs.isConfigured(context)

    suspend fun run(
        context: Context,
        onLog: (String) -> Unit = {},
    ): PostRootOutcome {
        var shizukuStarted = false
        var rebootRequested = false
        var rebootMethod = ""
        val notes = mutableListOf<String>()

        if (AutomationPrefs.startShizukuAfterRoot(context)) {
            val outcome = runCatching {
                ShizukuAutoStart.start(context, onLog)
            }.onFailure {
                onLog("[!] Shizuku start failed: ${it.message ?: it.javaClass.simpleName}")
            }.getOrNull()
            shizukuStarted = outcome?.started == true
            if (shizukuStarted) {
                notes += "Shizuku started via ${outcome?.method}"
            } else {
                notes += "Shizuku did not start: ${outcome?.detail ?: "unknown reason"}"
            }
        }

        if (AutomationPrefs.softRebootAfterRoot(context)) {
            val result = userspaceReboot(context, onLog)
            rebootRequested = result.accepted
            rebootMethod = result.method
            notes += if (result.accepted) {
                "userspace reboot accepted via ${result.method}"
            } else {
                "userspace reboot was not accepted: ${result.detail}"
            }
        }

        if (notes.isEmpty()) {
            notes += "nothing configured"
        }
        return PostRootOutcome(
            shizukuStarted = shizukuStarted,
            softRebootRequested = rebootRequested,
            softRebootMethod = rebootMethod,
            detail = notes.joinToString("; "),
        )
    }

    /**
     * Userspace reboot through KernelSU's own `soft-reboot`, which owns the
     * stop/start plus the KernelSU userspace lifecycle. A raw `setprop
     * ctl.restart zygote` is the fallback, and is also what the engine uses
     * when the action has to run from the activity instead.
     *
     * The action is handed to a detached keeper because a successful reboot
     * kills this process: the keeper writes an acceptance marker before it
     * reboots anything, so "accepted" is confirmed by a file rather than by an
     * exit code that may never arrive.
     */
    suspend fun userspaceReboot(
        context: Context,
        onLog: (String) -> Unit = {},
    ): RecoveryResult {
        if (!RootShellBridge.isRootActive(context)) {
            return RecoveryResult(
                accepted = false,
                method = "none",
                detail = "KernelSU root is not available in this boot",
            )
        }

        val marker = "$WORK_DIR/$ACCEPT_MARKER"
        val scriptPath = "$WORK_DIR/$KEEPER_SCRIPT"
        val logPath = "$WORK_DIR/$KEEPER_LOG"
        val command = buildKeeperCommand(context, scriptPath, logPath, marker)
        onLog("[*] Requesting a userspace reboot through KernelSU's native soft-reboot")
        val launch = RootShellBridge.run(context, command, LAUNCH_TIMEOUT_SECONDS)
        if (launch.exitCode != 0) {
            onLog("[!] Keeper launch rc=${launch.exitCode}: ${launch.output.trim().takeLast(200)}")
        }

        if (awaitMarker(context, marker, ACCEPT_WAIT_MILLIS)) {
            onLog("[+] Userspace reboot accepted by the KernelSU daemon")
            return RecoveryResult(
                accepted = true,
                method = "ksud-soft-reboot",
                detail = "accepted; the device is restarting userspace",
            )
        }

        onLog("[!] The native soft-reboot was not confirmed; falling back to a zygote restart")
        val fallback = RootShellBridge.run(
            context,
            "if [ \"\$(getprop init.svc.zygote_secondary)\" = running ]; then " +
                "setprop ctl.restart zygote_secondary; fi; setprop ctl.restart zygote",
            LAUNCH_TIMEOUT_SECONDS,
        )
        val accepted = fallback.exitCode == 0 || fallback.exitCode == 124
        return RecoveryResult(
            accepted = accepted,
            method = "zygote-restart",
            detail = if (accepted) {
                "zygote restart requested (rc=${fallback.exitCode})"
            } else {
                "zygote restart rc=${fallback.exitCode}: ${fallback.output.trim().takeLast(200)}"
            },
        )
    }

    private fun buildKeeperCommand(
        context: Context,
        scriptPath: String,
        logPath: String,
        marker: String,
    ): String = buildString {
        append("rm -f ").append(quote(marker)).append(' ').append(quote(scriptPath)).append('\n')
        append("cat > ").append(quote(scriptPath)).append(" <<'SAMSU_REBOOT'\n")
        append("#!/system/bin/sh\n")
        append("printf '%s\\n' accepted > ").append(quote(marker)).append('\n')
        append("sleep ").append(SETTLE_BEFORE_REBOOT_SECONDS).append('\n')
        append("ksud=\"\"\n")
        append("for candidate in ")
        ksudCandidates(context).forEach { append(quote(it)).append(' ') }
        append("; do if [ -x \"\$candidate\" ]; then ksud=\"\$candidate\"; break; fi; done\n")
        append("if [ -n \"\$ksud\" ]; then\n")
        append("  \"\$ksud\" soft-reboot && exit 0\n")
        append("fi\n")
        append("if [ \"\$(getprop init.svc.zygote_secondary)\" = running ]; then\n")
        append("  setprop ctl.restart zygote_secondary\n")
        append("fi\n")
        append("setprop ctl.restart zygote\n")
        append("SAMSU_REBOOT\n")
        append("chmod 0755 ").append(quote(scriptPath)).append('\n')
        append("nohup ").append(quote(scriptPath)).append(" > ").append(quote(logPath))
        append(" 2>&1 &\n")
    }

    /**
     * Where a KernelSU daemon may be found, most authoritative first: the one
     * KernelSU installed, the copy the engine staged for late-load, then the
     * one packaged in this APK.
     */
    private fun ksudCandidates(context: Context): List<String> = listOf(
        "/data/adb/ksud",
        "/data/local/tmp/ksud-s25u-kdp",
        File(context.applicationInfo.nativeLibraryDir, "libm3qksud.so").absolutePath,
    )

    private suspend fun awaitMarker(
        context: Context,
        marker: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val probe = RootShellBridge.run(context, "test -f ${quote(marker)}", 8)
            if (probe.exitCode == 0) return true
            delay(MARKER_POLL_MILLIS)
        }
        return false
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val WORK_DIR = "/data/local/tmp"
    private const val ACCEPT_MARKER = ".samsu-postroot-reboot-accepted"
    private const val KEEPER_SCRIPT = ".samsu-postroot-reboot.sh"
    private const val KEEPER_LOG = "samsu-postroot-reboot.log"
    private const val LAUNCH_TIMEOUT_SECONDS = 20
    private const val SETTLE_BEFORE_REBOOT_SECONDS = 2
    private const val ACCEPT_WAIT_MILLIS = 10_000L
    private const val MARKER_POLL_MILLIS = 400L
}
