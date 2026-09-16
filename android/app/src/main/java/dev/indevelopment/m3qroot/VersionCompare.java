package dev.indevelopment.m3qroot;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares the dotted release tags this project publishes (for example
 * "1.68" against "v1.70").
 *
 * Public rather than package-private because the update layer lives in the
 * {@code rmg} package and Kotlin cannot see package-private Java types across
 * packages. Keeping it plain Java also keeps it inside the javac test harness.
 */
public final class VersionCompare {
    private VersionCompare() {
    }

    /**
     * True only when {@code candidate} is strictly newer than {@code current}.
     *
     * Fail-closed: when either side has no numeric version to compare, the
     * answer is false. An unreadable installed version must never be enough to
     * justify replacing a working build.
     */
    public static boolean isNewer(String candidate, String current) {
        int[] left = parse(candidate);
        int[] right = parse(current);
        if (left.length == 0 || right.length == 0) return false;
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    /**
     * Leading dotted-numeric run of a tag: "v1.70-rc2" becomes {1, 70}, a
     * trailing "1.70.1" becomes {1, 70, 1}, and anything without digits is empty.
     */
    public static int[] parse(String raw) {
        if (raw == null) return new int[0];
        String text = raw.trim();
        if (!text.isEmpty() && (text.charAt(0) == 'v' || text.charAt(0) == 'V')) {
            text = text.substring(1);
        }
        List<Integer> parts = new ArrayList<>();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c == '.') {
                parts.add(number(digits));
                digits.setLength(0);
            } else {
                break;
            }
        }
        if (digits.length() > 0) parts.add(number(digits));
        // Drop a trailing empty group so "1.70." compares equal to "1.70".
        while (!parts.isEmpty() && parts.get(parts.size() - 1) == 0) {
            parts.remove(parts.size() - 1);
        }
        int[] values = new int[parts.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = parts.get(i);
        }
        return values;
    }

    private static int number(StringBuilder digits) {
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException overflow) {
            return Integer.MAX_VALUE;
        }
    }
}
