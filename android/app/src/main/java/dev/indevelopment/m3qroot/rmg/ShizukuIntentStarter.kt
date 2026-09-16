/*
 * Ported from Root-My-Galaxy-Extended (dev.busung.s25uroot) by igorcv88, which
 * derives from BuSung-dev/Root-My-Galaxy. Apache License 2.0. Adapted to SamSU:
 * the auth token lives in ShizukuPrefs.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Optional compatibility fallback for the automation interface exposed by the
 * thedjchi/Shizuku fork. The token is never logged and the broadcast is
 * package-scoped so it cannot leak to unrelated receivers.
 */
object ShizukuIntentStarter {
    data class Outcome(
        val started: Boolean,
        val attempted: Boolean,
        val detail: String = "",
    )

    suspend fun start(
        context: Context,
        binderTimeoutMillis: Long,
        onLog: (String) -> Unit = {},
    ): Outcome = ShizukuStartCoordinator.withStartLock(context) {
        if (ShizukuBinderWait.pingUntilRunning(BINDER_RACE_PROBE_MILLIS)) {
            onLog("[+] Shizuku Binder appeared before the Intent fallback; skipped broadcast")
            return@withStartLock Outcome(started = true, attempted = false)
        }

        val token = ShizukuPrefs.automationToken(context).trim()
        if (token.isBlank()) {
            onLog("[*] Shizuku authenticated Intent fallback is not configured; skipped")
            return@withStartLock Outcome(
                started = false,
                attempted = false,
                detail = "Shizuku automation auth token is not configured",
            )
        }

        // Do not pre-query the receiver: a package-scoped broadcast is harmless
        // when the compatible receiver is absent, while pre-querying can produce
        // false negatives on package-visibility and component-state edges.
        val intent = Intent(START_ACTION)
            .setPackage(ShizukuStarter.SHIZUKU_PACKAGE)
            .putExtra(AUTH_EXTRA, token)

        return@withStartLock try {
            context.sendBroadcast(intent)
            onLog("[*] Shizuku startup fallback selected: authenticated-intent")
            if (ShizukuBinderWait.pingUntilRunning(binderTimeoutMillis)) {
                onLog("[+] Shizuku authenticated Intent fallback produced a Binder")
                Outcome(started = true, attempted = true)
            } else {
                val detail = "authenticated Shizuku START broadcast sent but no Binder appeared"
                onLog("[!] $detail")
                Outcome(started = false, attempted = true, detail = detail)
            }
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            onLog("[!] Shizuku authenticated Intent fallback failed: $detail")
            Outcome(started = false, attempted = true, detail = detail)
        }
    }

    /**
     * Coexistence hint: the fork keeps its own BOOT_COMPLETED receiver disabled
     * in the manifest and enables it when its "start on boot" option is on.
     */
    fun isShizukuBootReceiverEnabled(context: Context): Boolean {
        val component = ComponentName(
            ShizukuStarter.SHIZUKU_PACKAGE,
            SHIZUKU_BOOT_RECEIVER_CLASS,
        )
        val pm = context.packageManager
        return runCatching {
            when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> {
                    @Suppress("DEPRECATION")
                    pm.getReceiverInfo(component, 0).enabled
                }
            }
        }.getOrDefault(false)
    }

    private const val START_ACTION = "${ShizukuStarter.SHIZUKU_PACKAGE}.START"
    private const val AUTH_EXTRA = "auth"
    private const val SHIZUKU_BOOT_RECEIVER_CLASS =
        "moe.shizuku.manager.receiver.BootCompleteReceiver"
    private const val BINDER_RACE_PROBE_MILLIS = 500L
}
