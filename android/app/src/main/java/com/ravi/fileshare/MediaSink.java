package com.ravi.fileshare;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Properties;

import fileshare.core.Dest;
import fileshare.core.Hexes;
import fileshare.core.Io;
import fileshare.core.Item;
import fileshare.core.Sanitize;

/**
 * Writes incoming files through MediaStore, which needs no storage permission
 * on Android 10 and up.
 *
 * The destination is chosen from the fixed {@link Dest} table by numeric code,
 * so the folder can never be steered by anything on the wire. Partial files are
 * held with IS_PENDING set, which keeps them out of your gallery and out of
 * other apps until they are complete and verified.
 */
public final class MediaSink implements Io.Sink {

    private final Context ctx;
    private final File metaDir;

    public MediaSink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.metaDir = new File(this.ctx.getFilesDir(), "partials");
        if (!metaDir.exists()) metaDir.mkdirs();
    }

    private File metaFile(String id) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length() && i < 64; i++) {
            char c = id.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return new File(metaDir, (sb.length() == 0 ? "unnamed" : sb.toString()) + ".meta");
    }

    @Override
    public long resumeOffset(Item it) {
        File meta = metaFile(it.id);
        if (!meta.isFile()) return 0;
        try {
            Properties p = load(meta);
            if (Long.parseLong(p.getProperty("size", "-1")) != it.size) return 0;
            Uri uri = Uri.parse(p.getProperty("uri", ""));
            long committed = Long.parseLong(p.getProperty("committed", "0"));
            if (committed <= 0) return 0;

            // Make sure the pending row still exists; the user may have cleaned it up.
            ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return 0;
            long actual = pfd.getStatSize();
            pfd.close();
            return Math.max(0, Math.min(committed, Math.min(it.size, actual)));
        } catch (Exception e) {
            meta.delete();
            return 0;
        }
    }

    @Override
    public Io.SinkFile open(Item it, long offset) throws IOException {
        File meta = metaFile(it.id);
        Uri uri = null;

        if (offset > 0 && meta.isFile()) {
            try {
                uri = Uri.parse(load(meta).getProperty("uri", ""));
            } catch (IOException e) {
                uri = null;
            }
        }

        if (uri == null) {
            offset = 0;
            uri = createPending(it);
            Properties p = new Properties();
            p.setProperty("uri", uri.toString());
            p.setProperty("name", it.name);
            p.setProperty("size", Long.toString(it.size));
            p.setProperty("committed", "0");
            store(meta, p);
        }

        return new MediaSinkFile(ctx, it, uri, meta, offset);
    }

    /** Creates the MediaStore row, hidden from other apps until we finish. */
    private Uri createPending(Item it) throws IOException {
        Dest dest = it.dest();
        String name = Sanitize.fileName(it.name);

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dest.relativePath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = collectionFor(dest, it.mime);
        Uri uri = ctx.getContentResolver().insert(collection, v);
        if (uri == null) {
            throw new IOException("Android refused to create " + name + " in " + dest.label);
        }
        return uri;
    }

    /**
     * MediaStore has separate collections and will reject a mismatch, so the
     * collection follows both the chosen folder and the file type. The laptop
     * has already filtered the folder list by MIME type, so a mismatch here
     * means a protocol-level bug rather than user error.
     */
    private static Uri collectionFor(Dest dest, String mime) {
        String v = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.US);
        switch (dest) {
            case DCIM_CAMERA:
                return m.startsWith("video/")
                        ? MediaStore.Video.Media.getContentUri(v)
                        : MediaStore.Images.Media.getContentUri(v);
            case PICTURES:
                return MediaStore.Images.Media.getContentUri(v);
            case MOVIES:
                return MediaStore.Video.Media.getContentUri(v);
            case MUSIC:
                return MediaStore.Audio.Media.getContentUri(v);
            case DOCUMENTS:
                // Downloads refuses a Documents/ relative path, so this one has
                // to go through the generic Files collection.
                return MediaStore.Files.getContentUri(v);
            case DOWNLOADS:
            default:
                return MediaStore.Downloads.getContentUri(v);
        }
    }

    @Override
    public long freeSpace() {
        try {
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------

    private static final class MediaSinkFile implements Io.SinkFile {
        private final Context ctx;
        private final Item item;
        private final Uri uri;
        private final File meta;
        private final ParcelFileDescriptor pfd;
        private final FileDescriptor fd;
        private final FileOutputStream out;
        private final MessageDigest md = Hexes.sha256();
        private long committed;

        MediaSinkFile(Context ctx, Item item, Uri uri, File meta, long offset) throws IOException {
            this.ctx = ctx;
            this.item = item;
            this.uri = uri;
            this.meta = meta;

            ParcelFileDescriptor p = ctx.getContentResolver().openFileDescriptor(uri, "rw");
            if (p == null) throw new IOException("could not open destination");
            this.pfd = p;
            this.fd = p.getFileDescriptor();

            try {
                // Anything past the last durable offset may be a torn write.
                Os.ftruncate(fd, offset);

                if (offset > 0) {
                    // Re-read the durable prefix so the hash covers the whole file.
                    Os.lseek(fd, 0, OsConstants.SEEK_SET);
                    FileInputStream in = new FileInputStream(fd);
                    byte[] buf = new byte[Io.BUFFER];
                    long left = offset;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("partial file shorter than recorded");
                        md.update(buf, 0, n);
                        left -= n;
                    }
                }
                Os.lseek(fd, offset, OsConstants.SEEK_SET);
            } catch (ErrnoException e) {
                try { p.close(); } catch (IOException ignored) { }
                throw new IOException("could not position destination file", e);
            }

            this.out = new FileOutputStream(fd);
            this.committed = offset;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            md.update(b, off, len);
        }

        @Override
        public void syncPoint() throws IOException {
            out.flush();
            try {
                Os.fsync(fd);
                committed = Os.lseek(fd, 0, OsConstants.SEEK_CUR);
            } catch (ErrnoException e) {
                throw new IOException("fsync failed", e);
            }
            Properties p = new Properties();
            p.setProperty("uri", uri.toString());
            p.setProperty("name", item.name);
            p.setProperty("size", Long.toString(item.size));
            p.setProperty("committed", Long.toString(committed));
            store(meta, p);
        }

        @Override
        public String digestHex() {
            return Hexes.hex(md.digest());
        }

        @Override
        public void commitVisible() throws IOException {
            out.flush();
            try { Os.fsync(fd); } catch (ErrnoException ignored) { }
            close();

            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
            meta.delete();
        }

        @Override
        public void abandon() {
            try { out.flush(); } catch (IOException ignored) { }
            try { Os.fsync(fd); } catch (ErrnoException ignored) { }
            close();
        }

        @Override
        public void discard() {
            close();
            try {
                ctx.getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
                // Already gone, or the row was cleaned up under us.
            }
            meta.delete();
        }

        @Override
        public void close() {
            try { out.close(); } catch (IOException ignored) { }
            try { pfd.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------

    private static Properties load(File f) throws IOException {
        Properties p = new Properties();
        InputStream in = new FileInputStream(f);
        try { p.load(in); } finally { in.close(); }
        return p;
    }

    private static void store(File f, Properties p) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try { p.store(out, "FileShare partial"); } finally { out.close(); }
    }

    /**
     * How long an unfinished transfer stays resumable.
     *
     * After this it is swept at startup, because a partial that is never going to
     * be finished is just storage nobody can see or reclaim.
     */
    public static final long PARTIAL_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000;

    /** Deletes one partial and its pending MediaStore row. */
    public void discardPartial(String id) {
        File meta = metaFile(id);
        if (!meta.isFile()) return;
        try {
            Uri uri = Uri.parse(load(meta).getProperty("uri", ""));
            ctx.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
            // Row already gone.
        }
        meta.delete();
    }

    public int partialCount() {
        File[] files = metaDir.listFiles();
        return files == null ? 0 : files.length;
    }

    /** Deletes every unfinished incoming file. */
    public int clearAllPartials() {
        File[] files = metaDir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            try {
                Uri uri = Uri.parse(load(f).getProperty("uri", ""));
                ctx.getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
                // Best effort.
            }
            if (f.delete()) n++;
        }
        return n;
    }

    /** Removes partials left over from transfers that were never resumed. */
    public void sweepStale(long olderThanMs) {
        File[] files = metaDir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - olderThanMs;
        for (File f : files) {
            if (f.lastModified() > cutoff) continue;
            try {
                Uri uri = Uri.parse(load(f).getProperty("uri", ""));
                ctx.getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
                // Best effort.
            }
            f.delete();
        }
    }

    public ContentResolver resolver() {
        return ctx.getContentResolver();
    }
}
