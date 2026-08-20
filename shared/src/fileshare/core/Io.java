package fileshare.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The storage seams between the shared transfer engine and each platform, plus
 * the two control surfaces the engine exposes upward.
 *
 * The laptop implements storage over java.io.File; the phone over MediaStore.
 * Everything above this interface -- framing, resume arithmetic, hashing,
 * pause and cancel -- is shared code that runs identically on both.
 */
public final class Io {
    private Io() {}

    public interface Source {
        /**
         * Opens the file for reading from the beginning.
         *
         * Always from zero, even when resuming: the end-to-end hash covers the
         * whole file, so the sender has to digest the part it already sent. The
         * skipped prefix is read from local storage an order of magnitude faster
         * than the link, so this costs seconds and buys real corruption detection.
         */
        InputStream open(Item it) throws IOException;
    }

    public interface Sink {
        /** Bytes already safely on disk for this transfer id, or 0 for a fresh start. */
        long resumeOffset(Item it);

        /**
         * Opens (or reopens) the destination, positioned to append at {@code offset}.
         *
         * The implementation must truncate anything above {@code offset} and must
         * seed its running SHA-256 by reading back the bytes below it, so that
         * {@link SinkFile#digestHex} covers the whole file on a resumed transfer.
         */
        SinkFile open(Item it, long offset) throws IOException;

        long freeSpace();
    }

    public interface SinkFile extends Closeable {
        void write(byte[] b, int off, int len) throws IOException;

        /**
         * Flush and fsync, then record the new committed offset.
         *
         * This is what makes resume safe without hashing anything: whatever is
         * below the recorded offset is durably on disk, and on reopen the file is
         * truncated back to it.
         */
        void syncPoint() throws IOException;

        /** SHA-256 over the whole file, hex. Compared against the sender's value. */
        String digestHex() throws IOException;

        /** Make the completed file visible (rename off .part, clear IS_PENDING). */
        void commitVisible() throws IOException;

        /** Leave the partial in place so a later connection can resume it. */
        void abandon();

        /** Remove the partial entirely. */
        void discard();
    }

    /** What the engine should do with the file it is working on. */
    public enum Act { GO, PAUSE, CANCEL }

    /**
     * Asked between chunks, so a pause takes effect within about a megabyte.
     *
     * Both ends consult their own copy of this and also honour the other end's
     * request over the wire, which is what makes pause work identically from
     * either side.
     */
    public interface Control {
        Act check(Item it);
    }

    public static final Control GO_ON = new Control() {
        @Override public Act check(Item it) { return Act.GO; }
    };

    /** How a single file ended. */
    public enum Outcome { DONE, PAUSED, CANCELLED, REJECTED, FAILED }

    public interface Events {
        void onStart(Item it, long fromOffset, boolean sending);

        /**
         * @param bytesPerSec measured over the last reporting window, not
         *        averaged from the start. An average is wrong the moment a
         *        transfer resumes, because bytes already on disk took no time
         *        on this connection.
         */
        void onBytes(Item it, long done, long total, long bytesPerSec);

        void onDone(Item it, boolean ok, String message);

        void onPaused(Item it, long done, long total, boolean sending);

        void onCancelled(Item it);

        void log(String message);
    }

    /** Called on the receiving side to decide what to take. */
    public interface Approver {
        /**
         * @return true for each item to accept, in the same order.
         *         Blocks until the user decides.
         */
        boolean[] decide(List<Item> offered) throws IOException;
    }

    /** Big enough to keep the radio busy, small enough that a pause feels instant. */
    public static final int BUFFER = 512 * 1024;

    /** Costs at most ~1.5 s of re-sent data after a crash at 40 MB/s. */
    public static final long SYNC_EVERY = 64L * 1024 * 1024;
}
