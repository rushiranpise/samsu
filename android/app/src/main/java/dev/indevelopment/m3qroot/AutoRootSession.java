package dev.indevelopment.m3qroot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Automation-facing wrapper around one {@link M3qRootEngine} instance.
 *
 * The engine is package-private and its payload overrides are per-instance, so
 * the automation layer in {@code dev.indevelopment.m3qroot.rmg} needs a single
 * object that both chooses the artifacts and runs them. Unlike the activity,
 * this never resolves the remote registry: unattended root must not depend on a
 * download, so it uses the bundled payload for this device or an already-cached
 * manual selection.
 */
public final class AutoRootSession {
    /** Where engine log lines go; the automation layer forwards them. */
    public interface LogSink {
        void line(String text);
    }

    /** Read-only view of everything the automation gates depend on. */
    public static final class Status {
        private final boolean deviceSupported;
        private final boolean rootActive;
        private final boolean bootstrap;
        private final boolean attemptedThisBoot;
        private final long settleRemainingMillis;

        Status(boolean deviceSupported, boolean rootActive, boolean bootstrap,
                boolean attemptedThisBoot, long settleRemainingMillis) {
            this.deviceSupported = deviceSupported;
            this.rootActive = rootActive;
            this.bootstrap = bootstrap;
            this.attemptedThisBoot = attemptedThisBoot;
            this.settleRemainingMillis = settleRemainingMillis;
        }

        public boolean isDeviceSupported() {
            return deviceSupported;
        }

        public boolean isRootActive() {
            return rootActive;
        }

        /** Root landed but KernelSU activation still has to finish. */
        public boolean isBootstrap() {
            return bootstrap;
        }

        public boolean isAttemptedThisBoot() {
            return attemptedThisBoot;
        }

        public long getSettleRemainingMillis() {
            return settleRemainingMillis;
        }
    }

    private final Context context;
    private final LogSink sink;
    private final M3qRootEngine engine;
    private String payloadId = "";

    private AutoRootSession(Context context, LogSink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink == null ? text -> { } : sink;
        this.engine = new M3qRootEngine(this.context, new M3qRootEngine.Listener() {
            @Override
            public void onStatus(String text, int color) {
                /* The automation layer renders its own status. */
            }

            @Override
            public void onLog(String line) {
                AutoRootSession.this.sink.line(line);
            }
        });
    }

    public static AutoRootSession create(Context context, LogSink sink) {
        return new AutoRootSession(context, sink);
    }

    public Status status() {
        M3qRootEngine.RootState state = engine.checkRoot(false);
        return new Status(
                engine.isSupported(),
                state.ready(),
                state.bootstrap(),
                engine.hasAttemptedThisBoot(),
                M3qRootEngine.bootSettleRemainingMillis());
    }

    /**
     * Chooses the artifacts this session will execute and returns the payload
     * id, so the caller can check them against the verified baseline before
     * anything claims the once-per-boot attempt.
     */
    public String prepareArtifacts() {
        SharedPreferences prefs =
                context.getSharedPreferences("samsu_payload", Context.MODE_PRIVATE);
        String manualId = prefs.getString("manual_payload_id", "");
        if (manualId != null && !manualId.isEmpty() && !PayloadStore.isBundledId(manualId)) {
            File cached = PayloadStore.cachedPayload(context, manualId);
            if (cached.isFile()) {
                engine.setPayloadOverride(cached);
                File cachedKsud = PayloadStore.cachedKsud(context, manualId);
                if (cachedKsud.isFile()) {
                    engine.setKsudOverride(cachedKsud, -1);
                }
                payloadId = manualId;
                return payloadId;
            }
        }
        String bundledId = PayloadStore.bundledPayloadIdForDevice();
        engine.setPayloadOverride(null);
        engine.setBundledPayloadLib(PayloadStore.bundledLibName(bundledId));
        engine.setKsudOverride(null, -1);
        payloadId = bundledId;
        return payloadId;
    }

    public String getPayloadId() {
        return payloadId;
    }

    public String getPayloadPath() {
        File file = engine.activePayloadFile();
        return file == null ? "" : file.getAbsolutePath();
    }

    public String getKsudPath() {
        File file = engine.activeKsudFile();
        return file == null ? "" : file.getAbsolutePath();
    }

    /** Records the payload hash in the run log, for the same reason a manual run does. */
    public void setPayloadHash(String sha256) {
        engine.setActivePayloadHash(sha256);
    }

    /** Claims the single kernel-write attempt for this boot. */
    public boolean claimAttempt() {
        return engine.markAttemptForThisBoot();
    }

    public int runFreshRoot(boolean useShizuku) {
        return engine.runFreshRoot(useShizuku);
    }

    public boolean isAttemptTerminationUnconfirmed(int exitCode) {
        return exitCode == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED;
    }

    public boolean rootReady() {
        return engine.checkRoot(false).ready();
    }

    public boolean bootstrapReady() {
        return engine.checkRoot(false).bootstrap();
    }

    public int activateKernelSu() {
        return engine.activateKernelSu();
    }
}
