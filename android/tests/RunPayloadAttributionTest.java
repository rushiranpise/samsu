package dev.indevelopment.m3qroot;

public final class RunPayloadAttributionTest {
    public static void main(String[] args) {
        // The regression: on a device this build has no payload for, the app's
        // resolved id is a fallback (the bundled default), and a run must not be
        // credited with it. This is what an A16 recorded as "pa1q-S931BXXUCZZI4"
        // for an Auto Root attempt that was refused at the device gate.
        expect(null, RunPayloadAttribution.resolved("pa1q-S931BXXUCZZI4", false));
        expect(null, RunPayloadAttribution.resolved("S936B", false));
        expect(null, RunPayloadAttribution.resolved(null, false));
        expect(null, RunPayloadAttribution.resolved("", false));

        // A supported device keeps the id, so a real run still names its payload.
        expect("pa1q-S931BXXUCZZI4",
                RunPayloadAttribution.resolved("pa1q-S931BXXUCZZI4", true));
        expect("some-registry-payload",
                RunPayloadAttribution.resolved("some-registry-payload", true));

        // Supported but with no id to record is still nothing to record.
        expect(null, RunPayloadAttribution.resolved(null, true));
        expect(null, RunPayloadAttribution.resolved("", true));
        expect(null, RunPayloadAttribution.resolved("    ", true));

        // A run that chose its own artifacts is authoritative: the gate that let
        // it choose already established that a payload exists for this device.
        expect("pa1q-S931BXXUCZZI4", RunPayloadAttribution.selectedByRun("pa1q-S931BXXUCZZI4"));
        expect("manual-payload", RunPayloadAttribution.selectedByRun("manual-payload"));

        // Refused before choosing: no payload, not a placeholder.
        expect(null, RunPayloadAttribution.selectedByRun(null));
        expect(null, RunPayloadAttribution.selectedByRun(""));
        expect(null, RunPayloadAttribution.selectedByRun("   "));

        // Whitespace is trimmed rather than recorded verbatim.
        expect("pa1q-S931BXXUCZZI4",
                RunPayloadAttribution.selectedByRun("  pa1q-S931BXXUCZZI4  "));

        System.out.println("RunPayloadAttribution PASS");
    }

    private static void expect(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
