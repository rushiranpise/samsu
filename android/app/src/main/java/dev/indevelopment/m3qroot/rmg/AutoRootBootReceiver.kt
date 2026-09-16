/*
 * Adapted from Root-My-Galaxy-Extended's AutoRootBootReceiver
 * (dev.busung.s25uroot) by igorcv88 (Apache License 2.0).
 *
 * Root My Galaxy consumes each kernel boot event once and hard-stops stale
 * runtime on a duplicate BOOT_COMPLETED, because a KernelSU soft reboot re-emits
 * it while keeping the kernel boot token. SamSU needs no extra bookkeeping for
 * that: a soft reboot leaves the root already active and the once-per-boot
 * attempt already claimed, and both are checked before anything runs.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoRootBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AutoRoot.isEnabled(context)) return
        runCatching { AutoRootService.startIfConfigured(context) }
            .onFailure { error ->
                Log.w(
                    TAG,
                    "Unable to launch Auto Root: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
    }

    private companion object {
        const val TAG = "SamSU-AutoRoot"
    }
}
