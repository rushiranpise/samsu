/*
 * New file for SamSU, standing in for the Root-My-Galaxy `AppPreferences`
 * helpers the ported Shizuku startup files originally called.
 */
package dev.indevelopment.m3qroot.rmg

import android.content.Context

/** Storage for the Shizuku auto-start settings. */
object ShizukuPrefs {
    private const val PREFS = "shizuku_automation"
    private const val KEY_START_ON_BOOT = "start_shizuku_on_boot"
    private const val KEY_AUTOMATION_TOKEN = "shizuku_automation_token"
    private const val KEY_LAST_RESULT = "last_start_result"

    fun startOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    /**
     * Auth token expected by the automation interface of the thedjchi/Shizuku
     * fork. Never logged, and empty unless the user pastes one in.
     */
    fun automationToken(context: Context): String =
        prefs(context).getString(KEY_AUTOMATION_TOKEN, "").orEmpty()

    fun setAutomationToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_AUTOMATION_TOKEN, token.trim()).apply()
    }

    /** One-line record of the last attempt, shown in the card. */
    fun lastResult(context: Context): String =
        prefs(context).getString(KEY_LAST_RESULT, "").orEmpty()

    fun setLastResult(context: Context, summary: String) {
        prefs(context).edit().putString(KEY_LAST_RESULT, summary).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
