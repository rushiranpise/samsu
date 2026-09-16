/*
 * Adapted from Root-My-Galaxy-Extended's AutoRootBootReceiver (dev.busung.s25uroot)
 * by igorcv88 (Apache License 2.0), reduced to the Shizuku bootstrap it performs
 * before any root work.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Framework boot trigger for the optional Shizuku bootstrap.
 *
 * Deliberately independent from anything root-related: this fires on every
 * BOOT_COMPLETED and does nothing unless the user enabled the option. A
 * repeated BOOT_COMPLETED (a KernelSU soft reboot re-emits it) is harmless
 * here, because the service first checks whether Shizuku is already up.
 */
class ShizukuBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ShizukuPrefs.startOnBoot(context)) return
        runCatching { ShizukuBootService.startIfConfigured(context) }
            .onFailure { error ->
                Log.w(
                    TAG,
                    "Unable to launch the Shizuku bootstrap: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
    }

    private companion object {
        const val TAG = "SamSU-ShizukuBoot"
    }
}
