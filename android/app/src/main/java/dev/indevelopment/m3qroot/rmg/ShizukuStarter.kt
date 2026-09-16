/*
 * Ported from Root-My-Galaxy-Extended (dev.busung.s25uroot) by igorcv88, which
 * derives from BuSung-dev/Root-My-Galaxy. Apache License 2.0. Adapted to SamSU:
 * the binder wait lives in ShizukuBinderWait so the shell transport stays a
 * caller-supplied lambda.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context
import dev.indevelopment.m3qroot.ShizukuBridge
import java.io.File
import kotlinx.coroutines.delay

/**
 * Resolves and starts Shizuku without coupling SamSU to one packaging
 * generation. Modern Shizuku (including thedjchi/Shizuku) uses the native
 * `libshizuku.so` starter; older/manual distributions may expose `start.sh` on
 * shared storage. The native starter is always preferred, and legacy paths are
 * compatibility-only fallbacks whose absence is not an error.
 *
 * Every shell-based start is serialized and re-checks the Binder immediately
 * before each launch, which closes the edge where two callers both observed
 * "not running" and would otherwise launch two servers.
 */
object ShizukuStarter {
    data class Outcome(
        val started: Boolean,
        val method: String? = null,
        val detail: String = "",
    )

    suspend fun start(
        context: Context,
        shell: (String) -> LocalAdbClient.ShellResult,
        binderTimeoutMillis: Long,
        onLog: (String) -> Unit = {},
    ): Outcome = ShizukuStartCoordinator.withStartLock(context) {
        startLocked(context, shell, binderTimeoutMillis, onLog)
    }

    private suspend fun startLocked(
        context: Context,
        shell: (String) -> LocalAdbClient.ShellResult,
        binderTimeoutMillis: Long,
        onLog: (String) -> Unit,
    ): Outcome {
        if (ShizukuBinderWait.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
            onLog("[+] Shizuku Binder already available; no starter selected")
            return Outcome(started = true, method = "existing-binder")
        }

        val nativeCommand = nativeCommand(context)
        if (nativeCommand != null) {
            val nativeProbe = shell("test -f ${shellQuote(nativeCommand.starterPath)}")
            if (nativeProbe.exitCode == 0) {
                if (ShizukuBinderWait.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
                    onLog("[+] Shizuku Binder appeared before native-lib launch")
                    return Outcome(started = true, method = "existing-binder")
                }

                onLog("[*] Shizuku startup selected: native-lib")
                val result = shell("${nativeCommand.command} 2>&1")
                if (result.exitCode == 0) {
                    if (ShizukuBinderWait.pingUntilRunning(binderTimeoutMillis)) {
                        onLog("[+] Shizuku started via native-lib; Binder is available")
                        return Outcome(started = true, method = "native-lib")
                    }
                    onLog(
                        "[!] Shizuku native-lib starter succeeded but no Binder " +
                            "appeared; checking legacy fallback",
                    )
                } else {
                    val detail = result.output.trim().takeLast(240)
                    onLog(
                        "[!] Shizuku native-lib starter rc=${result.exitCode}" +
                            (if (detail.isBlank()) "" else ": $detail") +
                            "; checking legacy fallback",
                    )
                }
            } else {
                onLog("[*] Shizuku native-lib starter is not present; checking legacy fallback")
            }
        } else {
            onLog("[*] Shizuku native-lib paths are unavailable; checking legacy fallback")
        }

        if (ShizukuBinderWait.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
            onLog("[+] Shizuku Binder appeared before the legacy fallback")
            return Outcome(started = true, method = "existing-binder")
        }

        val legacyPath = firstLegacyScript(shell)
        if (legacyPath != null) {
            if (ShizukuBinderWait.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
                onLog("[+] Shizuku Binder appeared before the legacy start.sh launch")
                return Outcome(started = true, method = "existing-binder")
            }

            onLog("[*] Shizuku startup selected: legacy-start.sh")
            val result = shell("sh ${shellQuote(legacyPath)} 2>&1")
            if (result.exitCode == 0) {
                if (ShizukuBinderWait.pingUntilRunning(binderTimeoutMillis)) {
                    onLog("[+] Shizuku started via legacy start.sh; Binder is available")
                    return Outcome(started = true, method = "legacy-start.sh")
                }
                val detail = "legacy start.sh succeeded but no Binder appeared"
                onLog("[!] $detail")
                return Outcome(started = false, method = "legacy-start.sh", detail = detail)
            }

            val output = result.output.trim().takeLast(240)
            val detail = "legacy start.sh failed rc=${result.exitCode}" +
                (if (output.isBlank()) "" else ": $output")
            onLog("[!] $detail")
            return Outcome(started = false, method = "legacy-start.sh", detail = detail)
        }

        // Missing legacy scripts are normal on current Shizuku builds. Only the
        // aggregate inability to start the service is reported.
        val detail = "no compatible Shizuku shell starter produced a Binder"
        onLog("[!] $detail (legacy start.sh not present; skipped)")
        return Outcome(started = false, detail = detail)
    }

    private fun nativeCommand(context: Context): NativeCommand? {
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        }.getOrNull() ?: return null

        val nativeLibraryDir = appInfo.nativeLibraryDir?.takeIf { it.isNotBlank() } ?: return null
        val sourceDir = appInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val starterPath = File(nativeLibraryDir, "libshizuku.so").absolutePath
        return NativeCommand(
            starterPath = starterPath,
            command = "${shellQuote(starterPath)} --apk=${shellQuote(sourceDir)}",
        )
    }

    private fun firstLegacyScript(
        shell: (String) -> LocalAdbClient.ShellResult,
    ): String? {
        for (path in LEGACY_START_PATHS) {
            if (shell("test -f ${shellQuote(path)}").exitCode == 0) return path
        }
        return null
    }

    private data class NativeCommand(
        val starterPath: String,
        val command: String,
    )

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val BINDER_RACE_PROBE_MILLIS = 500L
    private val LEGACY_START_PATHS = arrayOf(
        "/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh",
        "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
    )
}

/**
 * Waits for Shizuku's Binder instead of guessing by timing.
 *
 * Root-My-Galaxy used an event-driven wait registered on the Shizuku API; a
 * bounded poll is used here so the same helper works from a boot service and
 * from the activity without holding a listener across process death.
 */
internal object ShizukuBinderWait {
    suspend fun pingUntilRunning(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (ShizukuBridge.isRunning()) return true
            if (System.nanoTime() >= deadline) return false
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private const val POLL_INTERVAL_MILLIS = 150L
}
