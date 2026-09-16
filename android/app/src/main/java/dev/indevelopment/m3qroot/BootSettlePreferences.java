package dev.indevelopment.m3qroot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Storage for the configurable boot-settle wait.
 *
 * Kept next to {@link RootSafetyPolicy} rather than in the ported {@code rmg}
 * package because the policy is package-private, and kept free of any other
 * logic so the value can only ever be an allowed one.
 */
final class BootSettlePreferences {
    private static final String PREFS = "root_safety";
    private static final String KEY_BOOT_SETTLE_SECONDS = "boot_settle_seconds";

    private BootSettlePreferences() {
    }

    /** Stored wait, normalized, or the built-in default when nothing is stored. */
    static long seconds(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long stored = prefs.getLong(
                KEY_BOOT_SETTLE_SECONDS, RootSafetyPolicy.DEFAULT_SECONDS);
        return RootSafetyPolicy.normalizeSeconds(stored);
    }

    static void set(Context context, long seconds) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_BOOT_SETTLE_SECONDS, RootSafetyPolicy.normalizeSeconds(seconds))
                .apply();
    }
}
