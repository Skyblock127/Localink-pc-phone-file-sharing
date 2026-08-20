package fileshare.pc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Extension to MIME type.
 *
 * Used to decide which destinations a file is allowed to go to, because Android
 * MediaStore rejects a non-image insert into Pictures and so on. Getting this
 * right on the laptop means the destination dropdown only ever offers folders
 * that will actually accept the file, instead of failing after the bytes have
 * already crossed the wire.
 */
public final class Mimes {
    private Mimes() {}

    private static final Map<String, String> MAP = new HashMap<String, String>();

    static {
        String[][] t = {
            {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"png", "image/png"},
            {"gif", "image/gif"}, {"webp", "image/webp"}, {"bmp", "image/bmp"},
            {"heic", "image/heic"}, {"heif", "image/heif"}, {"tif", "image/tiff"},
            {"tiff", "image/tiff"}, {"svg", "image/svg+xml"}, {"avif", "image/avif"},
            {"dng", "image/x-adobe-dng"}, {"cr2", "image/x-canon-cr2"},

            {"mp4", "video/mp4"}, {"mkv", "video/x-matroska"}, {"webm", "video/webm"},
            {"mov", "video/quicktime"}, {"avi", "video/x-msvideo"}, {"3gp", "video/3gpp"},
            {"m4v", "video/x-m4v"}, {"wmv", "video/x-ms-wmv"}, {"flv", "video/x-flv"},
            {"ts", "video/mp2t"}, {"mpg", "video/mpeg"}, {"mpeg", "video/mpeg"},

            {"mp3", "audio/mpeg"}, {"flac", "audio/flac"}, {"wav", "audio/wav"},
            {"m4a", "audio/mp4"}, {"aac", "audio/aac"}, {"ogg", "audio/ogg"},
            {"opus", "audio/opus"}, {"wma", "audio/x-ms-wma"}, {"mid", "audio/midi"},

            {"pdf", "application/pdf"}, {"txt", "text/plain"}, {"md", "text/markdown"},
            {"csv", "text/csv"}, {"json", "application/json"}, {"xml", "application/xml"},
            {"html", "text/html"}, {"htm", "text/html"},
            {"doc", "application/msword"},
            {"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
            {"xls", "application/vnd.ms-excel"},
            {"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
            {"ppt", "application/vnd.ms-powerpoint"},
            {"pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},

            {"zip", "application/zip"}, {"rar", "application/vnd.rar"},
            {"7z", "application/x-7z-compressed"}, {"tar", "application/x-tar"},
            {"gz", "application/gzip"}, {"iso", "application/x-iso9660-image"},
            {"apk", "application/vnd.android.package-archive"},
            {"exe", "application/vnd.microsoft.portable-executable"},
            {"msi", "application/x-msi"}, {"epub", "application/epub+zip"},
        };
        for (String[] row : t) MAP.put(row[0], row[1]);
    }

    public static String of(String fileName) {
        if (fileName == null) return "application/octet-stream";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "application/octet-stream";
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        String m = MAP.get(ext);
        return m == null ? "application/octet-stream" : m;
    }
}
