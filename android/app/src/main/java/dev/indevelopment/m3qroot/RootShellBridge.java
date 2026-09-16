package dev.indevelopment.m3qroot;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Public bridge that runs a single command through the KernelSU root shell.
 *
 * The engine stays package-private and its Listener is a UI concept, so the
 * automation layer in {@code dev.indevelopment.m3qroot.rmg} reaches root via
 * this bridge with a silent listener. Root here is temporary: it only exists in
 * a boot where a root run already succeeded, which is exactly why callers must
 * check {@link #isRootActive} first and fall back to the local ADB transport.
 */
public final class RootShellBridge {
    /** Exit code and combined output of one root command. */
    public static final class Result {
        private final int exitCode;
        private final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }
    }

    private RootShellBridge() {
    }

    public static boolean isRootActive(Context context) {
        M3qRootEngine engine = engine(context);
        if (engine == null) return false;
        return engine.checkRoot(false).ready();
    }

    public static Result run(Context context, String command, int timeoutSeconds) {
        M3qRootEngine engine = engine(context);
        if (engine == null) {
            return new Result(126, "root shell unavailable");
        }
        List<String> output = new ArrayList<>();
        int code = engine.runRootCommand(command, timeoutSeconds, output);
        return new Result(code, String.join("\n", output));
    }

    private static M3qRootEngine engine(Context context) {
        try {
            return new M3qRootEngine(context, new M3qRootEngine.Listener() {
                @Override
                public void onStatus(String text, int color) {
                    /* Silent: this bridge is not attached to the activity. */
                }

                @Override
                public void onLog(String line) {
                    /* Silent: the caller owns the log. */
                }
            });
        } catch (RuntimeException error) {
            return null;
        }
    }
}
