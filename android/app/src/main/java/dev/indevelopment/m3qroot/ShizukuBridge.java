package dev.indevelopment.m3qroot;

/**
 * Public bridge to the app's Shizuku state.
 *
 * {@link ShizukuShell} stays package-private, and Kotlin cannot see
 * package-private Java types across packages, so the ported automation layer in
 * {@code dev.indevelopment.m3qroot.rmg} reads Shizuku through this.
 */
public final class ShizukuBridge {
    private ShizukuBridge() {
    }

    public static boolean isRunning() {
        return ShizukuShell.isRunning();
    }

    public static boolean isGranted() {
        return ShizukuShell.isGranted();
    }

    /** Shizuku's uid (2000 for shell, 0 for root), or -1 when unavailable. */
    public static int uid() {
        return ShizukuShell.uid();
    }

    /** True when the shell bridge can actually be used for a command. */
    public static boolean isShellOrRoot() {
        int uid = uid();
        return (uid == 2000 || uid == 0) && isGranted();
    }
}
