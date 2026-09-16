package dev.indevelopment.m3qroot;

public final class VersionCompareTest {
    public static void main(String[] args) {
        // Numeric, not lexicographic: 1.70 is newer than 1.68, and 1.9 is older.
        expect(true, VersionCompare.isNewer("1.70", "1.68"));
        expect(true, VersionCompare.isNewer("v1.70", "1.68"));
        expect(true, VersionCompare.isNewer("1.69", "v1.68"));
        expect(false, VersionCompare.isNewer("1.9", "1.68"));
        expect(false, VersionCompare.isNewer("1.68", "1.68"));
        expect(false, VersionCompare.isNewer("v1.68", "1.68"));
        expect(false, VersionCompare.isNewer("1.67", "1.68"));

        // A third component is significant.
        expect(true, VersionCompare.isNewer("1.68.1", "1.68"));
        expect(false, VersionCompare.isNewer("1.68", "1.68.1"));
        expect(false, VersionCompare.isNewer("1.68.0", "1.68"));

        // Pre-release and unparsable labels fall back to their numeric run.
        expect(true, VersionCompare.isNewer("1.70-rc2", "1.68"));
        expect(false, VersionCompare.isNewer("1.68-beta", "1.68"));
        expect(false, VersionCompare.isNewer("latest", "1.68"));
        expect(false, VersionCompare.isNewer("", "1.68"));
        expect(false, VersionCompare.isNewer(null, "1.68"));
        expect(false, VersionCompare.isNewer("1.70", null));
        expect(false, VersionCompare.isNewer("1.70", "?"));
        expect(false, VersionCompare.isNewer("1.70", "unknown"));

        expect(3, VersionCompare.parse("v2.10.3").length);
        expect(2, VersionCompare.parse("v2.10.3")[0]);
        expect(10, VersionCompare.parse("v2.10.3")[1]);
        expect(3, VersionCompare.parse("v2.10.3")[2]);

        System.out.println("VersionCompare PASS");
    }

    private static void expect(boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void expect(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
