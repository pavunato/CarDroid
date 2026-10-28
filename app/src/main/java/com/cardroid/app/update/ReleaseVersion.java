package com.cardroid.app.update;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** SemVer 2.0 precedence, with the repository's optional lowercase v tag prefix. */
public final class ReleaseVersion {
    private static final Pattern VERSION = Pattern.compile("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private ReleaseVersion() {}

    public static int compare(String left, String right) {
        String[] a = parts(left), b = parts(right);
        for (int i = 0; i < 3; i++) {
            int order = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            if (order != 0) return order;
        }
        if (a[3] == null || b[3] == null) return a[3] == b[3] ? 0 : a[3] == null ? 1 : -1;
        String[] ap = a[3].split("\\."), bp = b[3].split("\\.");
        for (int i = 0; i < Math.min(ap.length, bp.length); i++) {
            boolean an = ap[i].matches("[0-9]+"), bn = bp[i].matches("[0-9]+");
            int order = an && bn ? new BigInteger(ap[i]).compareTo(new BigInteger(bp[i]))
                    : an != bn ? (an ? -1 : 1) : ap[i].compareTo(bp[i]);
            if (order != 0) return order;
        }
        return Integer.compare(ap.length, bp.length);
    }

    public static boolean isStable(String value) { return parts(value)[3] == null; }

    private static String[] parts(String value) {
        java.util.regex.Matcher m = VERSION.matcher(value == null ? "" : value);
        if (!m.matches()) throw new IllegalArgumentException("Unsupported version: " + value);
        if (m.group(4) != null) for (String id : m.group(4).split("\\.")) {
            if (id.matches("0[0-9]+")) throw new IllegalArgumentException("Invalid prerelease version");
        }
        return new String[]{m.group(1), m.group(2), m.group(3), m.group(4)};
    }
}
