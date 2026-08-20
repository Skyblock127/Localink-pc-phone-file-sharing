package fileshare.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Framing for the control channel.
 *
 * Every control message is: [1 byte type][4 byte big-endian length][payload]
 *
 * File payloads are NOT framed. After a FILE_BEGIN control frame the sender
 * writes exactly "count" raw bytes onto the stream and then sends FILE_END.
 * This keeps bulk transfer at zero framing overhead so we can use big buffers.
 */
public final class Wire {
    private Wire() {}

    /**
     * 2: file data moved from a raw byte run to framed chunks, so pause and
     * cancel can be answered mid-transfer instead of by dropping the socket.
     */
    public static final int VERSION = 2;

    // handshake
    public static final byte HELLO        = 0x01;
    public static final byte HELLO_OK     = 0x02;
    public static final byte ERROR        = 0x03;

    // pairing (only accepted while the phone is in pairing mode)
    public static final byte PAIR_PROOF_C = 0x11;
    public static final byte PAIR_PROOF_S = 0x12;

    // transfer
    public static final byte OFFER        = 0x20;
    public static final byte OFFER_REPLY  = 0x21;
    public static final byte FILE_BEGIN     = 0x30;
    public static final byte FILE_CHUNK     = 0x31;
    public static final byte FILE_END       = 0x32;
    public static final byte FILE_RESULT    = 0x33;
    public static final byte FILE_PAUSED    = 0x34;
    public static final byte FILE_CANCELLED = 0x35;

    // Interruptions. These may arrive at any moment, including part way through
    // a file, and are queued separately from the ordered protocol frames.
    public static final byte PAUSE          = 0x60;
    public static final byte RESUME         = 0x61;
    public static final byte CANCEL         = 0x62;

    // teardown. Liveness needs no message of its own: an empty offer round is
    // exchanged whenever nothing else is happening, which is the heartbeat.
    public static final byte BYE          = 0x4F;

    /** Sent when one side drops the pairing, so the other forgets it too. */
    public static final byte UNPAIR       = 0x50;

    /** Caps a single frame, chunks included. */
    public static final int MAX_CONTROL = 4 * 1024 * 1024;

    public static final class Frame {
        public final byte type;
        public final byte[] payload;

        Frame(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        public DataInputStream in() {
            return new DataInputStream(new ByteArrayInputStream(payload));
        }
    }

    public static void write(OutputStream out, byte type, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        byte[] head = new byte[5];
        head[0] = type;
        head[1] = (byte) (payload.length >>> 24);
        head[2] = (byte) (payload.length >>> 16);
        head[3] = (byte) (payload.length >>> 8);
        head[4] = (byte) (payload.length);
        out.write(head);
        out.write(payload);
        out.flush();
    }

    public static void write(OutputStream out, byte type) throws IOException {
        write(out, type, null);
    }

    public static Frame read(InputStream in) throws IOException {
        byte[] head = new byte[5];
        readFully(in, head, 0, 5);
        int len = ((head[1] & 0xff) << 24) | ((head[2] & 0xff) << 16)
                | ((head[3] & 0xff) << 8) | (head[4] & 0xff);
        if (len < 0 || len > MAX_CONTROL) {
            throw new IOException("bad control frame length " + len);
        }
        byte[] payload = new byte[len];
        readFully(in, payload, 0, len);
        return new Frame(head[0], payload);
    }

    public static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(b, off + got, len - got);
            if (n < 0) throw new EOFException("peer closed after " + got + " of " + len + " bytes");
            got += n;
        }
    }

    /** Small builder so message encoding stays readable at the call sites. */
    public static final class Buf {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream d = new DataOutputStream(bytes);

        public Buf str(String s) {
            try { d.writeUTF(s == null ? "" : s); } catch (IOException e) { throw new AssertionError(e); }
            return this;
        }

        public Buf i32(int v) {
            try { d.writeInt(v); } catch (IOException e) { throw new AssertionError(e); }
            return this;
        }

        public Buf i64(long v) {
            try { d.writeLong(v); } catch (IOException e) { throw new AssertionError(e); }
            return this;
        }

        public Buf bool(boolean v) {
            try { d.writeBoolean(v); } catch (IOException e) { throw new AssertionError(e); }
            return this;
        }

        public Buf raw(byte[] v) {
            try { d.writeInt(v.length); d.write(v); } catch (IOException e) { throw new AssertionError(e); }
            return this;
        }

        public byte[] done() {
            try { d.flush(); } catch (IOException e) { throw new AssertionError(e); }
            return bytes.toByteArray();
        }
    }

    public static Buf buf() {
        return new Buf();
    }

    public static byte[] readRaw(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_CONTROL) throw new IOException("bad blob length");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    public static String errorText(Frame f) {
        try { return f.in().readUTF(); } catch (IOException e) { return "unknown error"; }
    }
}
