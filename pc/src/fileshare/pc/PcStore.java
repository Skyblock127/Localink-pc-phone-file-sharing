package fileshare.pc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Properties;

import fileshare.core.Hexes;
import fileshare.core.Io;
import fileshare.core.Item;
import fileshare.core.Sanitize;

/** Laptop-side implementations of the storage seams in {@link Io}. */
public final class PcStore {
    private PcStore() {}

    // ------------------------------------------------------------------
    // Reading files out of the send queue
    // ------------------------------------------------------------------

    public static final class Source implements Io.Source {
        @Override
        public InputStream open(Item it) throws IOException {
            File f = (File) it.handle;
            if (f == null || !f.isFile()) {
                throw new IOException("file is gone: " + it.name);
            }
            if (f.length() != it.size) {
                throw new IOException("file changed since it was queued: " + it.name);
            }
            return new java.io.BufferedInputStream(new FileInputStream(f), Io.BUFFER);
        }
    }

    // ------------------------------------------------------------------
    // Writing files the phone sends us
    // ------------------------------------------------------------------

    public static final class Sink implements Io.Sink {
        private final File finalDir;
        private final File partDir;
        private final boolean markOfTheWeb;

        public Sink(File finalDir, boolean markOfTheWeb) {
            this.finalDir = finalDir;
            this.partDir = new File(finalDir, ".incoming");
            this.markOfTheWeb = markOfTheWeb;
            if (!partDir.exists()) partDir.mkdirs();
        }

        private File partFile(Item it) { return new File(partDir, safeId(it.id) + ".part"); }

        private File metaFile(Item it) { return new File(partDir, safeId(it.id) + ".meta"); }

        /** The id comes off the wire, so it never touches a path unsanitised. */
        private static String safeId(String id) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < id.length() && i < 64; i++) {
                char c = id.charAt(i);
                sb.append(Character.isLetterOrDigit(c) ? c : '_');
            }
            return sb.length() == 0 ? "unnamed" : sb.toString();
        }

        @Override
        public long resumeOffset(Item it) {
            File part = partFile(it);
            File meta = metaFile(it);
            if (!part.isFile() || !meta.isFile()) return 0;
            try {
                Properties p = new Properties();
                InputStream in = new FileInputStream(meta);
                try { p.load(in); } finally { in.close(); }
                // A different size means it is not the same file any more.
                if (Long.parseLong(p.getProperty("size", "-1")) != it.size) return 0;
                long committed = Long.parseLong(p.getProperty("committed", "0"));
                return Math.max(0, Math.min(committed, Math.min(it.size, part.length())));
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public Io.SinkFile open(Item it, long offset) throws IOException {
            return new PcSinkFile(it, offset, partFile(it), metaFile(it), finalDir, markOfTheWeb);
        }

        @Override
        public long freeSpace() {
            return finalDir.getUsableSpace();
        }
    }

    private static final class PcSinkFile implements Io.SinkFile {
        private final Item item;
        private final File part;
        private final File meta;
        private final File finalDir;
        private final boolean markOfTheWeb;
        private final RandomAccessFile raf;
        private final MessageDigest md = Hexes.sha256();

        private long committed;
        private File committedTo;

        PcSinkFile(Item it, long offset, File part, File meta, File finalDir, boolean motw)
                throws IOException {
            this.item = it;
            this.part = part;
            this.meta = meta;
            this.finalDir = finalDir;
            this.markOfTheWeb = motw;

            raf = new RandomAccessFile(part, "rw");
            // Anything above the last synced offset may be torn, so drop it.
            raf.setLength(offset);

            // Re-read the durable prefix so the running hash covers the whole
            // file even though we only receive the tail.
            if (offset > 0) {
                raf.seek(0);
                byte[] buf = new byte[Io.BUFFER];
                long left = offset;
                while (left > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("partial file is shorter than recorded");
                    md.update(buf, 0, n);
                    left -= n;
                }
            }
            raf.seek(offset);
            committed = offset;
            writeMeta();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            raf.write(b, off, len);
            md.update(b, off, len);
        }

        @Override
        public void syncPoint() throws IOException {
            raf.getFD().sync();
            committed = raf.getFilePointer();
            writeMeta();
        }

        private void writeMeta() throws IOException {
            Properties p = new Properties();
            p.setProperty("name", item.name);
            p.setProperty("size", Long.toString(item.size));
            p.setProperty("committed", Long.toString(committed));
            OutputStream out = new FileOutputStream(meta);
            try {
                p.store(out, "FileShare partial transfer");
            } finally {
                out.close();
            }
        }

        @Override
        public String digestHex() {
            return Hexes.hex(md.digest());
        }

        @Override
        public void commitVisible() throws IOException {
            raf.getFD().sync();
            raf.close();

            String name = Sanitize.fileName(item.name);
            File target = new File(finalDir, name);
            for (int i = 2; target.exists() && i < 1000; i++) {
                target = new File(finalDir, Sanitize.withSuffix(name, i));
            }
            if (!part.renameTo(target)) {
                // Different volume or a lock: fall back to a copy.
                copy(part, target);
                part.delete();
            }
            meta.delete();
            committedTo = target;

            if (markOfTheWeb) applyMarkOfTheWeb(target);
        }

        /**
         * Tag the file as having come from outside this machine.
         *
         * Costs nothing and means SmartScreen still speaks up if you move an
         * installer across from the phone and run it. Written as an NTFS
         * alternate data stream; silently skipped on filesystems without them.
         */
        private static void applyMarkOfTheWeb(File f) {
            try {
                OutputStream out = new FileOutputStream(f.getAbsolutePath() + ":Zone.Identifier");
                try {
                    out.write("[ZoneTransfer]\r\nZoneId=3\r\n".getBytes("US-ASCII"));
                } finally {
                    out.close();
                }
            } catch (IOException ignored) {
                // Not NTFS, or the stream was refused. Nothing depends on this.
            }
        }

        private static void copy(File from, File to) throws IOException {
            InputStream in = new FileInputStream(from);
            try {
                OutputStream out = new FileOutputStream(to);
                try {
                    byte[] buf = new byte[Io.BUFFER];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        }

        @Override
        public void abandon() {
            try { raf.getFD().sync(); } catch (IOException ignored) { }
            try { raf.close(); } catch (IOException ignored) { }
        }

        @Override
        public void discard() {
            try { raf.close(); } catch (IOException ignored) { }
            part.delete();
            meta.delete();
        }

        @Override
        public void close() {
            try { raf.close(); } catch (IOException ignored) { }
        }

        public File committedTo() { return committedTo; }
    }

    /**
     * Transfer id: stable while the file is untouched, different the moment it is
     * edited or replaced. That is what lets an interrupted transfer resume, and
     * what stops a stale partial being appended to a file that has since changed.
     */
    public static String idFor(File f) {
        MessageDigest md = Hexes.sha256();
        try {
            md.update((f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified())
                    .getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
        return Hexes.hex(md.digest()).substring(0, 32);
    }
}
