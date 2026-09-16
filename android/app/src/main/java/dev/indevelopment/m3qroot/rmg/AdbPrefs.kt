/*
 * Ported from Root-My-Galaxy-Extended (dev.busung.s25uroot) by igorcv88, which
 * derives from BuSung-dev/Root-My-Galaxy. Apache License 2.0. Adapted to SamSU:
 * package name, preference storage, notification resources.
 */

package dev.indevelopment.m3qroot.rmg

import android.content.Context

/**
 * Storage the ported ADB stack needs, standing in for the Root-My-Galaxy
 * `AppPreferences` helpers those files originally called.
 *
 * The flag only records that a pairing transaction once succeeded. It is never
 * proof that adbd still accepts this key; a live connection is what proves that,
 * which is why the diagnostics deliberately report a saved credential as
 * "saved but unverified" until a real shell round trip succeeds.
 */
object AdbPrefs {
    private const val PREFS = "adb_credentials"
    private const val KEY_PAIRED = "adb_paired"

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAIRED, false)

    fun setPaired(context: Context, paired: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAIRED, paired)
            .apply()
    }
}
