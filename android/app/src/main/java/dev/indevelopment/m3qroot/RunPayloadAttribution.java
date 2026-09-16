package dev.indevelopment.m3qroot;

/**
 * Decides which payload id a run history record may claim.
 *
 * An entry names the payload whose artifacts the run actually used, and nothing
 * else. There are two honest ways to know that, and one dishonest way the app
 * used to take: stamping the device's <em>resolved</em> payload onto every run at
 * the moment it starts. On a device this build has no payload for, the resolved
 * id is an automatic fallback that no run would ever execute, so a record
 * carrying it claims an artifact that was never part of the run.
 *
 * The rule ties the record to the same predicate the UI uses to decide whether a
 * payload exists for this device at all, so the two can never disagree.
 *
 * Deliberately free of Android types: {@code tests/RunPayloadAttributionTest.java}
 * pins this decision table in the repository's plain javac harness.
 */
public final class RunPayloadAttribution {

    private RunPayloadAttribution() {
    }

    /**
     * For a run whose artifacts come from the app's resolved payload.
     *
     * @param resolvedId the payload the app resolved for this device.
     * @param deviceSupported whether this build has a payload for this device.
     * @return the id when a payload really exists for this device, else null.
     */
    public static String resolved(String resolvedId, boolean deviceSupported) {
        return deviceSupported ? normalize(resolvedId) : null;
    }

    /**
     * For a run that selected its own artifacts, such as the Auto Root gate.
     *
     * A null or blank id is meaningful rather than missing: it says the run was
     * refused before it chose anything, which the record must show as no payload
     * rather than a substituted one.
     *
     * @param selectedId the payload the run chose, or null if it never chose one.
     */
    public static String selectedByRun(String selectedId) {
        return normalize(selectedId);
    }

    private static String normalize(String payloadId) {
        if (payloadId == null) return null;
        String trimmed = payloadId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
