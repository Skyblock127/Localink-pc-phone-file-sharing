package fileshare.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * An authenticated connection between the two devices.
 *
 * The laptop always dials and the phone always listens, regardless of which way
 * files are about to move. On a phone hotspot the phone IS the laptop's default
 * gateway, so there is nothing to discover.
 *
 * After the handshake a reader thread owns the socket and splits what arrives
 * into two streams: protocol frames, taken in order by the transfer engine, and
 * control frames (pause, resume, cancel), which the engine polls between chunks.
 * That split is what lets either side interrupt a transfer in progress without
 * the connection being disturbed.
 */
public final class Session implements Closeable {

    public static final int PORT = 47654;

    static final int MODE_NORMAL = 0;
    static final int MODE_PAIR = 1;

    /**
     * How long to wait for the next frame before calling the peer dead.
     *
     * Generous on purpose: one of the things we wait for is a person deciding
     * whether to accept a file. This used to be shorter than that decision could
     * take, so every slow answer dropped the connection, re-queued the batch and
     * offered it all over again. Idle links are still noticed quickly, because
     * the empty offer round trip runs continuously.
     */
    private static final long TAKE_TIMEOUT_MS = 180_000;

    public interface Trust {
        /** Display name for a pinned fingerprint, or null if this peer is unknown. */
        String nameFor(String fingerprint);

        void remember(String fingerprint, String name);
    }

    public final SSLSocket sock;
    final InputStream in;
    final OutputStream out;

    public String peerName = "";
    public String peerFingerprint = "";

    /**
     * Bounded on purpose: this is the backpressure.
     *
     * The reader pulls frames as fast as the socket delivers them, but the thread
     * writing to storage is often slower. With an unbounded queue the difference
     * accumulates a chunk at a time until the heap is gone -- which is exactly how
     * this failed on a large file. Blocking the reader instead lets the socket
     * buffer fill, which stalls the sender, which is what TCP is for.
     */
    private static final int MAX_QUEUED_FRAMES = 8;

    private final BlockingQueue<Wire.Frame> frames =
            new LinkedBlockingQueue<Wire.Frame>(MAX_QUEUED_FRAMES);
    private final BlockingQueue<Wire.Frame> control = new LinkedBlockingQueue<Wire.Frame>();

    private final Object writeLock = new Object();
    private volatile IOException readerError;
    private volatile boolean ended;
    private volatile boolean closed;
    private Thread reader;

    private Session(SSLSocket s) throws IOException {
        this.sock = s;
        this.in = new BufferedInputStream(s.getInputStream(), 256 * 1024);
        this.out = new BufferedOutputStream(s.getOutputStream(), 256 * 1024);
    }

    // ------------------------------------------------------------------
    // Frame plumbing
    // ------------------------------------------------------------------

    private static boolean isControl(byte type) {
        return type == Wire.PAUSE || type == Wire.RESUME || type == Wire.CANCEL;
    }

    /** Start demultiplexing. Call once, after the handshake has completed. */
    public void startReader() {
        if (reader != null) return;
        reader = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (!closed) {
                        Wire.Frame f = Wire.read(in);
                        if (isControl(f.type)) control.put(f);
                        else frames.put(f);
                    }
                } catch (IOException e) {
                    readerError = e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    // Anything else -- including running out of memory -- must end
                    // the session rather than kill the process.
                    readerError = new IOException(String.valueOf(t));
                } finally {
                    ended = true;
                }
            }
        }, "session-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Next protocol frame, in order. */
    public Wire.Frame take() throws IOException {
        long deadline = System.currentTimeMillis() + TAKE_TIMEOUT_MS;
        try {
            while (true) {
                Wire.Frame f = frames.poll(150, TimeUnit.MILLISECONDS);
                if (f != null) return f;
                if (closed) throw new IOException("connection closed");
                if (ended) {
                    throw readerError != null ? readerError : new IOException("peer disconnected");
                }
                if (System.currentTimeMillis() > deadline) throw new IOException("peer went quiet");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** Next pause/resume/cancel request, or null if none is waiting. */
    public Wire.Frame pollControl() {
        return control.poll();
    }

    /** Return a control frame that was not ours to consume. */
    public void pushBackControl(Wire.Frame f) {
        if (f != null) control.offer(f);
    }

    /** Writes are serialised, since pause can be raised from a different thread. */
    public void send(byte type, byte[] payload) throws IOException {
        synchronized (writeLock) {
            Wire.write(out, type, payload);
        }
    }

    public void send(byte type) throws IOException {
        send(type, null);
    }

    /**
     * Ask the peer to restart a paused item.
     *
     * Handed to a background thread because this is called straight from button
     * handlers, and on Android a socket write on the main thread is a hard crash.
     * It is also best effort: if the peer has gone, the local queue change still
     * stands and the next connection sorts it out.
     */
    public void requestResume(final String itemId) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    send(Wire.RESUME, Wire.buf().str(itemId).done());
                } catch (IOException ignored) { }
            }
        }, "resume-notify").start();
    }

    // ------------------------------------------------------------------
    // Laptop side
    // ------------------------------------------------------------------

    public static Session dial(InetAddress addr, int port, int connectTimeoutMs,
                               SSLContext ctx, String myName,
                               String expectPin, String pairCode, Trust trust)
            throws IOException, GeneralSecurityException {

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(addr, port), connectTimeoutMs);
        Tls.tune(raw);

        SSLSocket s = (SSLSocket) ctx.getSocketFactory()
                .createSocket(raw, addr.getHostAddress(), port, true);
        s.setUseClientMode(true);
        Tls.restrict(s);

        boolean ok = false;
        try {
            s.startHandshake();
            X509Certificate serverCert = Tls.peerCert(s);
            X509Certificate clientCert = myCert(s);
            String fp = Certs.fingerprint(serverCert);

            if (pairCode == null) {
                if (expectPin == null || !expectPin.equals(fp)) {
                    throw new IOException("This is not your paired phone.");
                }
            }

            Session sess = new Session(s);
            sess.peerFingerprint = fp;

            sess.send(Wire.HELLO, Wire.buf()
                    .i32(Wire.VERSION).str(myName)
                    .i32(pairCode == null ? MODE_NORMAL : MODE_PAIR).done());

            if (pairCode != null) {
                byte[] psk = Pairing.pskFromCode(pairCode);
                sess.send(Wire.PAIR_PROOF_C, Wire.buf()
                        .raw(Pairing.proof(psk, Pairing.ROLE_CLIENT, clientCert, serverCert)).done());

                Wire.Frame f = Wire.read(sess.in);
                if (f.type == Wire.ERROR) throw new IOException(Wire.errorText(f));
                if (f.type != Wire.PAIR_PROOF_S) throw new IOException("unexpected reply during pairing");

                java.io.DataInputStream d = f.in();
                byte[] got = Wire.readRaw(d);
                String name = d.readUTF();
                byte[] want = Pairing.proof(psk, Pairing.ROLE_SERVER, clientCert, serverCert);
                if (!Hexes.eq(got, want)) {
                    throw new IOException("Pairing code did not match. Nothing was paired.");
                }
                sess.peerName = name;
                if (trust != null) trust.remember(fp, name);
            } else {
                Wire.Frame f = Wire.read(sess.in);
                if (f.type == Wire.ERROR) throw new IOException(Wire.errorText(f));
                if (f.type != Wire.HELLO_OK) throw new IOException("unexpected reply from phone");
                java.io.DataInputStream d = f.in();
                d.readInt();
                sess.peerName = d.readUTF();
            }

            sess.startReader();
            ok = true;
            return sess;
        } finally {
            if (!ok) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // Phone side
    // ------------------------------------------------------------------

    /**
     * @return null if the peer is not paired and we are not pairing. An unknown
     *         device gets no reply and no prompt: a prompt would be an
     *         accept-by-reflex vector with nothing useful to decide.
     */
    public static Session serve(SSLSocket s, String myName, Trust trust, String pairCode)
            throws IOException, GeneralSecurityException {

        Tls.tune(s);
        s.setUseClientMode(false);
        s.setNeedClientAuth(true);
        Tls.restrict(s);
        s.startHandshake();

        Session sess = new Session(s);

        // Read HELLO first: under TLS 1.3 the client certificate arrives with its
        // first flight after the server Finished, so it is only reliably
        // available once we have read something.
        Wire.Frame f = Wire.read(sess.in);
        if (f.type != Wire.HELLO) {
            sess.closeQuietly();
            return null;
        }
        java.io.DataInputStream d = f.in();
        int version = d.readInt();
        String name = d.readUTF();
        int mode = d.readInt();

        X509Certificate clientCert = Tls.peerCert(s);
        X509Certificate serverCert = myCert(s);
        String fp = Certs.fingerprint(clientCert);
        sess.peerFingerprint = fp;
        sess.peerName = name;

        if (version != Wire.VERSION) {
            sess.send(Wire.ERROR, Wire.buf()
                    .str("Version mismatch: phone speaks v" + Wire.VERSION
                            + ", laptop speaks v" + version
                            + ". Reinstall both from the same build.").done());
            sess.closeQuietly();
            return null;
        }

        if (mode == MODE_PAIR) {
            if (pairCode == null) {
                sess.closeQuietly();
                return null;
            }
            Wire.Frame pf = Wire.read(sess.in);
            if (pf.type != Wire.PAIR_PROOF_C) {
                sess.closeQuietly();
                return null;
            }
            byte[] psk = Pairing.pskFromCode(pairCode);
            byte[] got = Wire.readRaw(pf.in());
            byte[] want = Pairing.proof(psk, Pairing.ROLE_CLIENT, clientCert, serverCert);
            if (!Hexes.eq(got, want)) {
                sess.send(Wire.ERROR, Wire.buf().str("Wrong pairing code.").done());
                sess.closeQuietly();
                throw new BadPairingCode();
            }
            sess.send(Wire.PAIR_PROOF_S, Wire.buf()
                    .raw(Pairing.proof(psk, Pairing.ROLE_SERVER, clientCert, serverCert))
                    .str(myName).done());
            trust.remember(fp, name);
            sess.startReader();
            return sess;
        }

        if (trust.nameFor(fp) == null) {
            sess.closeQuietly();
            return null;
        }

        sess.send(Wire.HELLO_OK, Wire.buf().i32(Wire.VERSION).str(myName).done());
        sess.startReader();
        return sess;
    }

    /** Thrown so the phone can burn the pairing code after a single wrong attempt. */
    public static final class BadPairingCode extends IOException {
        public BadPairingCode() { super("wrong pairing code"); }
    }

    private static X509Certificate myCert(SSLSocket s) throws IOException {
        java.security.cert.Certificate[] chain = s.getSession().getLocalCertificates();
        if (chain == null || chain.length == 0) throw new IOException("no local certificate configured");
        return (X509Certificate) chain[0];
    }

    public void bye() {
        try {
            send(Wire.BYE);
        } catch (IOException ignored) { }
    }

    public void closeQuietly() {
        try { close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        frames.clear();       // unblock the reader if it is waiting for room
        sock.close();
    }
}
