package com.kooo.evcam.update;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric versions and pre-release ordering, for stable and explicitly selected release channels. */
public final class VersionName {
    private static final Pattern VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
    private static final Pattern CHANNEL = Pattern.compile("(?:alpha|beta)(?:\\.(?:0|[1-9][0-9]*))*");
    private VersionName() {}

    private static final class Parts {
        final int[] core;
        final String pre;
        Parts(int[] core, String pre) { this.core = core; this.pre = pre; }
    }

    private static Parts parse(String version) {
        if (version == null || version.length() > 256) return null;
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        Matcher match = VERSION.matcher(value);
        if (!match.matches()) return null;
        int[] core = new int[3];
        try {
            for (int i = 0; i < core.length; i++)
                core[i] = match.group(i + 1) == null ? 0 : Integer.parseInt(match.group(i + 1));
        } catch (NumberFormatException invalid) { return null; }
        String pre = match.group(4) == null ? "" : match.group(4);
        for (String identifier : pre.split("\\."))
            if (identifier.matches("[0-9]+") && identifier.length() > 1 && identifier.charAt(0) == '0')
                return null;
        return new Parts(core, pre);
    }

    /** Invalid inputs cannot rank above a valid version. Use isNewer when offering an update. */
    public static int compare(String a, String b) {
        Parts left = parse(a), right = parse(b);
        if (left == null || right == null) return left == right ? 0 : left == null ? -1 : 1;
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(left.core[i], right.core[i]);
            if (result != 0) return result;
        }
        if (left.pre.isEmpty() || right.pre.isEmpty())
            return left.pre.isEmpty() == right.pre.isEmpty() ? 0 : left.pre.isEmpty() ? 1 : -1;
        String[] l = left.pre.split("\\."), r = right.pre.split("\\.");
        for (int i = 0; i < Math.min(l.length, r.length); i++) {
            boolean ln = l[i].matches("[0-9]+"), rn = r[i].matches("[0-9]+");
            int result = ln && rn ? new BigInteger(l[i]).compareTo(new BigInteger(r[i]))
                    : ln != rn ? (ln ? -1 : 1) : l[i].compareTo(r[i]);
            if (result != 0) return result;
        }
        return Integer.compare(l.length, r.length);
    }

    public static boolean isNewer(String remote, String local) {
        return parse(remote) != null && parse(local) != null && compare(remote, local) > 0;
    }

    public static boolean isBetaOrRelease(String version) {
        Parts parts = parse(version);
        return parts != null && (parts.pre.isEmpty() || beta(parts.pre));
    }

    public static boolean isRelease(String version) {
        Parts parts = parse(version);
        return parts != null && parts.pre.isEmpty();
    }

    /** Require agreement between the version channel and GitHub's pre-release checkbox. */
    public static boolean isEligible(String version, boolean prerelease, UpdateChannel channel) {
        Parts parts = parse(version);
        if (parts == null) return false;
        if (parts.pre.isEmpty()) return !prerelease;
        if (!prerelease || channel == null || channel == UpdateChannel.STABLE) return false;
        if (beta(parts.pre)) return true;
        String suffix = parts.pre.toLowerCase(Locale.US);
        return channel == UpdateChannel.ALPHA && CHANNEL.matcher(suffix).matches()
                && (suffix.equals("alpha") || suffix.startsWith("alpha."));
    }

    private static boolean beta(String suffix) {
        String lower = suffix.toLowerCase(Locale.US);
        return CHANNEL.matcher(lower).matches() && (lower.equals("beta") || lower.startsWith("beta."));
    }
}
