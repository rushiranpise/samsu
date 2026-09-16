package dev.indevelopment.m3qroot;

public final class RootSafetyPolicyTest {
    public static void main(String[] args) {
        // Default: the established 180 s gate.
        expect(180_000L, RootSafetyPolicy.bootSettleRemainingMillis(0));
        expect(1L, RootSafetyPolicy.bootSettleRemainingMillis(179_999));
        expect(0L, RootSafetyPolicy.bootSettleRemainingMillis(180_000));
        expect(0L, RootSafetyPolicy.bootSettleRemainingMillis(240_000));

        // The wait is configurable, and the gate is never disabled.
        RootSafetyPolicy.setConfiguredSeconds(300);
        expect(300_000L, RootSafetyPolicy.bootSettleRemainingMillis(0));
        expect(60_000L, RootSafetyPolicy.bootSettleRemainingMillis(240_000));
        expect(300L, RootSafetyPolicy.configuredSeconds());

        RootSafetyPolicy.setConfiguredSeconds(120);
        expect(120_000L, RootSafetyPolicy.bootSettleRemainingMillis(0));
        expect(0L, RootSafetyPolicy.bootSettleRemainingMillis(120_000));

        // Out-of-band values snap to the nearest allowed wait, and 0 is refused.
        RootSafetyPolicy.setConfiguredSeconds(0);
        expect(120L, RootSafetyPolicy.configuredSeconds());
        RootSafetyPolicy.setConfiguredSeconds(-5);
        expect(120L, RootSafetyPolicy.configuredSeconds());
        RootSafetyPolicy.setConfiguredSeconds(6_000_000);
        expect(600L, RootSafetyPolicy.configuredSeconds());
        RootSafetyPolicy.setConfiguredSeconds(200);
        expect(180L, RootSafetyPolicy.configuredSeconds());
        // A tie rounds up, so an ambiguous value never shortens the wait.
        RootSafetyPolicy.setConfiguredSeconds(210);
        expect(240L, RootSafetyPolicy.configuredSeconds());
        RootSafetyPolicy.setConfiguredSeconds(150);
        expect(180L, RootSafetyPolicy.configuredSeconds());
        RootSafetyPolicy.setConfiguredSeconds(450);
        expect(600L, RootSafetyPolicy.configuredSeconds());

        // Explicit seconds are honoured without touching the configured value.
        RootSafetyPolicy.setConfiguredSeconds(180);
        expect(240_000L, RootSafetyPolicy.bootSettleRemainingMillis(0, 240));
        expect(180L, RootSafetyPolicy.configuredSeconds());

        RootSafetyPolicy.resetConfiguredSeconds();
        expect(180_000L, RootSafetyPolicy.bootSettleRemainingMillis(0));

        long[] allowed = RootSafetyPolicy.allowedSeconds();
        if (allowed.length == 0 || allowed[0] < 60) {
            throw new AssertionError("allowed waits must keep a safe floor");
        }
        // Mutating the returned array must not affect the policy.
        allowed[0] = 1;
        expect(120L, RootSafetyPolicy.allowedSeconds()[0]);

        System.out.println("RootSafetyPolicy PASS");
    }

    private static void expect(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
