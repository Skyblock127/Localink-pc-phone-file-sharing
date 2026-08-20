package fileshare.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class Hexes {
    private Hexes() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2]     = HEX[(b[i] >>> 4) & 0xf];
            out[i * 2 + 1] = HEX[b[i] & 0xf];
        }
        return new String(out);
    }

    /**
     * SHA-256 rather than BLAKE3: on both ARMv8 phones and x86 laptops this runs
     * on hardware crypto instructions at well over 1 GB/s, which is 20-40x faster
     * than the Wi-Fi link will ever deliver. A pure-Java BLAKE3 would actually be
     * slower here, and this way there are zero third-party dependencies.
     */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static byte[] sha256(byte[] data) {
        return sha256().digest(data);
    }

    /** Constant-time compare. Used for pairing proofs. */
    public static boolean eq(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    public static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    public static String humanRate(long bytesPerSec) {
        if (bytesPerSec <= 0) return "";
        double mbps = bytesPerSec / 1048576.0;
        if (mbps < 0.1) return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", mbps);
    }

    /** Rough time remaining, or "" when there is nothing sensible to say. */
    public static String eta(long remainingBytes, long bytesPerSec) {
        if (bytesPerSec <= 0 || remainingBytes <= 0) return "";
        long sec = remainingBytes / bytesPerSec;
        if (sec < 60) return sec + "s left";
        if (sec < 3600) return (sec / 60) + "m " + (sec % 60) + "s left";
        return (sec / 3600) + "h " + ((sec % 3600) / 60) + "m left";
    }
}
