/*
 * New file for SamSU, standing in for the Root-My-Galaxy `AppPreferences`
 * helpers the ported post-root automation originally called.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context

/**
 * Post-root automation switches.
 *
 * Both default to off: the sequence they automate is the one the README tells
 * the user to perform by hand, so it should not start happening on its own
 * until it is asked for.
 */
object AutomationPrefs {
    private const val PREFS = "post_root_automation"
    private const val KEY_START_SHIZUKU = "start_shizuku_after_root"
    private const val KEY_SOFT_REBOOT = "soft_reboot_after_root"
    private const val KEY_AUTO_ROOT = "auto_root_enabled"
    private const val KEY_AUTO_ROOT_RESULT = "auto_root_last_result"

    fun startShizukuAfterRoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_START_SHIZUKU, false)

    fun setStartShizukuAfterRoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_SHIZUKU, enabled).apply()
    }

    fun softRebootAfterRoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOFT_REBOOT, false)

    fun setSoftRebootAfterRoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOFT_REBOOT, enabled).apply()
    }

    /**
     * Unattended root at boot. Off by default, and only ever honoured for
     * artifacts that a previous run already verified on this exact build.
     */
    fun autoRoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ROOT, false)

    fun setAutoRoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ROOT, enabled).apply()
    }

    /** One-line record of the last Auto Root attempt, shown in the card. */
    fun lastAutoRootResult(context: Context): String =
        prefs(context).getString(KEY_AUTO_ROOT_RESULT, "").orEmpty()

    fun setLastAutoRootResult(context: Context, summary: String) {
        prefs(context).edit().putString(KEY_AUTO_ROOT_RESULT, summary).apply()
    }

    fun isConfigured(context: Context): Boolean =
        startShizukuAfterRoot(context) || softRebootAfterRoot(context)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
