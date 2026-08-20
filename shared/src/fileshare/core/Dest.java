package fileshare.core;

/**
 * Destination folders on the phone.
 *
 * This is a fixed, compile-time table on purpose. The wire protocol carries the
 * numeric code, never a path string, so neither a buggy sender nor a hostile one
 * can steer a write anywhere the phone app did not already intend to write.
 * Destination path traversal is structurally impossible, not merely filtered.
 *
 * "relativePath" is the Android MediaStore RELATIVE_PATH. "kind" encodes what
 * MediaStore will actually accept in that collection: putting a PDF in DCIM
 * fails at insert time, so the PC validates this before it offers the file.
 */
public enum Dest {
    DOWNLOADS  (0, "Downloads",     "Download/",    Kind.ANY),
    DCIM_CAMERA(1, "DCIM / Camera", "DCIM/Camera/", Kind.MEDIA),
    PICTURES   (2, "Pictures",      "Pictures/",    Kind.IMAGE),
    MOVIES     (3, "Movies",        "Movies/",      Kind.VIDEO),
    MUSIC      (4, "Music",         "Music/",       Kind.AUDIO),
    DOCUMENTS  (5, "Documents",     "Documents/",   Kind.ANY);

    public enum Kind { ANY, MEDIA, IMAGE, VIDEO, AUDIO }

    public final int code;
    public final String label;
    public final String relativePath;
    public final Kind kind;

    Dest(int code, String label, String relativePath, Kind kind) {
        this.code = code;
        this.label = label;
        this.relativePath = relativePath;
        this.kind = kind;
    }

    public static Dest byCode(int code) {
        for (Dest d : values()) {
            if (d.code == code) return d;
        }
        // Unknown code from a newer peer: fall back to the universal folder
        // rather than failing, since Downloads accepts every MIME type.
        return DOWNLOADS;
    }

    /** True if Android MediaStore will accept this MIME type in this collection. */
    public boolean accepts(String mime) {
        if (mime == null) mime = "application/octet-stream";
        String m = mime.toLowerCase(java.util.Locale.US);
        switch (kind) {
            case ANY:   return true;
            case IMAGE: return m.startsWith("image/");
            case VIDEO: return m.startsWith("video/");
            case AUDIO: return m.startsWith("audio/");
            case MEDIA: return m.startsWith("image/") || m.startsWith("video/");
            default:    return false;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
