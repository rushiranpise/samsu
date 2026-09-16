package dev.indevelopment.m3qroot;

/**
 * Minimum kernel uptime before a root run is allowed.
 *
 * Ported from Root-My-Galaxy-Extended's boot gates (`DiagnosticUptime.kt`) by
 * igorcv88 (Apache License 2.0), keeping SamSU's stricter posture: the wait is
 * configurable inside a safe band, but it can never be turned off, because the
 * exploit races a kernel that is still settling after boot.
 *
 * This class deliberately has no Android dependencies so the javac test harness
 * can exercise it directly.
 */
public final class RootSafetyPolicy {
    /** SamSU's established floor, and the value used when nothing is stored. */
    public static final long DEFAULT_SECONDS = 180L;

    /** Selectable waits, shortest first. */
    private static final long[] ALLOWED_SECONDS = {120L, 180L, 240L, 300L, 600L};

    private static volatile long configuredSeconds = DEFAULT_SECONDS;

    private RootSafetyPolicy() {
    }

    public static long[] allowedSeconds() {
        return ALLOWED_SECONDS.clone();
    }

    /**
     * Nearest allowed wait, so a stored or hand-edited value stays in band.
     * A tie rounds up: this is a safety gate, so an ambiguous value must not be
     * the one that shortens the wait.
     */
    public static long normalizeSeconds(long seconds) {
        long best = ALLOWED_SECONDS[0];
        long bestDistance = Math.abs(seconds - best);
        for (long candidate : ALLOWED_SECONDS) {
            long distance = Math.abs(seconds - candidate);
            if (distance < bestDistance
                    || (distance == bestDistance && candidate > best)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static void setConfiguredSeconds(long seconds) {
        configuredSeconds = normalizeSeconds(seconds);
    }

    public static long configuredSeconds() {
        return configuredSeconds;
    }

    /** Restores the built-in default. Used by the test harness. */
    static void resetConfiguredSeconds() {
        configuredSeconds = DEFAULT_SECONDS;
    }

    public static long bootSettleRemainingMillis(long elapsedRealtimeMillis) {
        return bootSettleRemainingMillis(elapsedRealtimeMillis, configuredSeconds);
    }

    public static long bootSettleRemainingMillis(long elapsedRealtimeMillis, long seconds) {
        long target = normalizeSeconds(seconds) * 1_000L;
        return Math.max(0L, target - elapsedRealtimeMillis);
    }
}
