package fileshare.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Filename hardening for the receive side.
 *
 * The destination folder is never sender-controlled (see {@link Dest}), so the
 * filename is the only attacker-influenced string that reaches the filesystem.
 * This is a whitelist, not a blacklist: anything not explicitly allowed becomes
 * an underscore. That is deliberately blunt, because the cost of being wrong
 * here is arbitrary file write, and the cost of being blunt is an ugly filename.
 *
 * Handles, in order: directory separators and traversal, NUL and control chars,
 * Unicode direction overrides (used to disguise .exe as .txt), Windows reserved
 * device names, NTFS alternate data streams, and trailing dots/spaces which
 * Windows silently strips.
 */
public final class Sanitize {
    private Sanitize() {}

    public static final int MAX_NAME = 150;

    private static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"));

    public static String fileName(String raw) {
        if (raw == null || raw.isEmpty()) return "unnamed";

        // 1. Take the last path segment only. Kills ../../ and C:\ and \\server\share
        //    regardless of which separator style the sender used.
        int cut = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String s = (cut >= 0) ? raw.substring(cut + 1) : raw;

        // 2. Character whitelist. Everything outside it, including NUL, control
        //    characters, ':' (NTFS alternate data streams) and the bidirectional
        //    override codepoints, collapses to '_'.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ' ' || c == '(' || c == ')'
                    || c == '[' || c == ']' || c == ',' || c == '\'' || c == '+' || c == '&'
                    || c == '#' || c == '@' || c == '!' || c == '='
                    // Allow ordinary non-ASCII letters (accents, Devanagari, CJK ...)
                    // but never the C1 range or the bidi/format controls.
                    || (c > 0x00A0 && !isDangerousUnicode(c));
            sb.append(ok ? c : '_');
        }
        s = sb.toString();

        // 3. Leading dots would hide the file; a name of only dots is meaningless.
        while (s.startsWith(".")) s = s.substring(1);

        // 4. Windows strips trailing dots and spaces, which lets "evil.exe. "
        //    round-trip into "evil.exe" after our checks. Strip them ourselves.
        s = trimTrailing(s);

        if (s.isEmpty()) return "unnamed";

        // 5. Windows reserved device names, with or without an extension.
        String stem = s;
        int dot = s.indexOf('.');
        if (dot > 0) stem = s.substring(0, dot);
        if (RESERVED.contains(stem.toLowerCase(Locale.US))) {
            s = "_" + s;
        }

        // 6. Length cap, preserving the extension so the file still opens.
        if (s.length() > MAX_NAME) {
            String ext = "";
            int lastDot = s.lastIndexOf('.');
            if (lastDot > 0 && s.length() - lastDot <= 12) {
                ext = s.substring(lastDot);
            }
            int keep = MAX_NAME - ext.length();
            if (keep < 1) keep = 1;
            s = s.substring(0, keep) + ext;
            s = trimTrailing(s);
        }

        return s.isEmpty() ? "unnamed" : s;
    }

    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '.' || c == ' ') end--;
            else break;
        }
        return s.substring(0, end);
    }

    private static boolean isDangerousUnicode(char c) {
        // Bidirectional overrides and embeddings: the classic "gpj.exe" -> "exe.jpg"
        // display trick. Also zero-width and other invisible formatting characters.
        return (c >= 0x200B && c <= 0x200F)
                || (c >= 0x202A && c <= 0x202E)
                || (c >= 0x2066 && c <= 0x2069)
                || c == 0xFEFF
                || Character.isISOControl(c);
    }

    /** Adds " (2)", " (3)" ... before the extension when a name is already taken. */
    public static String withSuffix(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 12) {
            return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
        }
        return name + " (" + n + ")";
    }
}
