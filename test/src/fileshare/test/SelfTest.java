package fileshare.test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import fileshare.core.Certs;
import fileshare.core.Dest;
import fileshare.core.Hexes;
import fileshare.core.Io;
import fileshare.core.Item;
import fileshare.core.Pairing;
import fileshare.core.Sanitize;
import fileshare.core.Session;
import fileshare.core.Tls;
import fileshare.core.Transfer;
import fileshare.pc.PcStore;

/**
 * End-to-end exercise of the shared core over loopback: pairing, a full
 * transfer, a resumed transfer, rejection of an unpaired peer, and the
 * filename hardening rules.
 */
public final class SelfTest {

    private static int failures = 0;
    private static final int PORT = 47999;

    public static void main(String[] args) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "fileshare-selftest");
        deleteTree(tmp);
        tmp.mkdirs();

        testSanitize();
        testPairingProofs();

        Identity phone = new Identity("Test Phone");
        Identity pc = new Identity("Test Laptop");

        testPairing(phone, pc);
        testTransfer(phone, pc, tmp, false);
        testTransfer(phone, pc, tmp, true);
        testPauseResume(phone, pc, tmp);
        testUnpairedRejected(phone, tmp);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    static void testSanitize() {
        section("filename hardening");
        check("traversal stripped", "passwd".equals(Sanitize.fileName("../../../etc/passwd")));
        check("windows traversal stripped",
                "authorized_keys".equals(Sanitize.fileName("..\\..\\.ssh\\authorized_keys")));
        check("drive letter stripped", "evil.exe".equals(Sanitize.fileName("C:\\Windows\\evil.exe")));
        check("UNC stripped", "x.dll".equals(Sanitize.fileName("\\\\attacker\\share\\x.dll")));
        check("reserved device renamed", Sanitize.fileName("CON.txt").startsWith("_"));
        check("bare reserved renamed", Sanitize.fileName("nul").startsWith("_"));
        check("ADS colon removed", Sanitize.fileName("report.txt:hidden.exe").indexOf(':') < 0);
        check("trailing dot removed", !Sanitize.fileName("evil.exe. ").endsWith(". "));
        check("bidi override removed", Sanitize.fileName("photo\u202Egnp.exe").indexOf('\u202E') < 0);
        check("nul byte removed", Sanitize.fileName("a\u0000b.txt").indexOf('\u0000') < 0);
        check("empty becomes unnamed", "unnamed".equals(Sanitize.fileName("")));
        check("dots only becomes unnamed", "unnamed".equals(Sanitize.fileName("...")));
        check("unicode kept", Sanitize.fileName("गाना.mp3").endsWith(".mp3"));
        check("long name truncated but keeps ext",
                Sanitize.fileName(repeat("a", 400) + ".mp4").endsWith(".mp4")
                        && Sanitize.fileName(repeat("a", 400) + ".mp4").length() <= Sanitize.MAX_NAME);
        check("normal name untouched", "Holiday Video (1).mp4"
                .equals(Sanitize.fileName("Holiday Video (1).mp4")));
    }

    static void testPairingProofs() throws Exception {
        section("pairing");
        String code = Pairing.newCode();
        check("code normalises with dash", Pairing.looksComplete(code));
        check("code normalises lowercase", Pairing.looksComplete(code.toLowerCase()));
        check("code normalises with spaces", Pairing.looksComplete(" " + code + " "));
        check("wrong length rejected", !Pairing.looksComplete("ABC"));

        // What the laptop's code field does to whatever the user types.
        check("format uppercases", "ABCD-2345".equals(Pairing.format("abcd2345")));
        check("format inserts the dash", "ABCD-2345".equals(Pairing.format("ABCD2345")));
        check("format keeps a typed dash", "ABCD-2345".equals(Pairing.format("abcd-2345")));
        check("format strips spaces", "ABCD-2345".equals(Pairing.format(" ab cd 23 45 ")));
        check("format caps at 8 characters", "ABCD-2345".equals(Pairing.format("abcd2345XYZQ")));
        check("format drops junk", "ABCD-2345".equals(Pairing.format("a!b@c#d$2345")));
        check("format leaves a short code alone", "ABC".equals(Pairing.format("abc")));
        check("format adds no dash below five", "ABCD".equals(Pairing.format("abcd")));
        check("format is idempotent", Pairing.format(Pairing.format("abcd2345"))
                .equals(Pairing.format("abcd2345")));
        check("generated codes survive formatting",
                Pairing.format(code).equals(code));

        byte[] psk1 = Pairing.pskFromCode(code);
        byte[] psk2 = Pairing.pskFromCode(code.toLowerCase().replace("-", ""));
        check("same code gives same key", Hexes.eq(psk1, psk2));

        Identity a = new Identity("A");
        Identity b = new Identity("B");
        Identity mitm = new Identity("M");

        byte[] good = Pairing.proof(psk1, Pairing.ROLE_CLIENT, a.cert, b.cert);
        byte[] same = Pairing.proof(psk1, Pairing.ROLE_CLIENT, a.cert, b.cert);
        check("proof is deterministic", Hexes.eq(good, same));

        byte[] relayed = Pairing.proof(psk1, Pairing.ROLE_CLIENT, a.cert, mitm.cert);
        check("proof is bound to the certificates (blocks relay)", !Hexes.eq(good, relayed));

        byte[] otherRole = Pairing.proof(psk1, Pairing.ROLE_SERVER, a.cert, b.cert);
        check("roles are distinct", !Hexes.eq(good, otherRole));

        byte[] wrongCode = Pairing.proof(Pairing.pskFromCode(Pairing.newCode()),
                Pairing.ROLE_CLIENT, a.cert, b.cert);
        check("wrong code fails", !Hexes.eq(good, wrongCode));
    }

    static void testPairing(final Identity phone, final Identity pc) throws Exception {
        section("pairing handshake over TLS");
        final String code = Pairing.newCode();

        final AtomicReference<Exception> serverErr = new AtomicReference<Exception>();
        final AtomicReference<Boolean> serverHadCert = new AtomicReference<Boolean>(Boolean.FALSE);
        SSLServerSocket ss = listen(phone);
        Thread server = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SSLSocket s = (SSLSocket) accept(PORT);
                    Session sess = Session.serve(s, phone.name, phone.trust, code);
                    if (sess != null) {
                        // The listening side must actually put a certificate on the
                        // wire. A KeyManager that resolves to nothing fails here with
                        // NO_CERTIFICATE_SET rather than anything self-explanatory.
                        serverHadCert.set(Boolean.valueOf(
                                s.getSession().getLocalCertificates() != null));
                        sess.closeQuietly();
                    }
                } catch (Exception e) {
                    serverErr.set(e);
                }
            }
        });
        server.start();
        Thread.sleep(150);

        SSLContext ctx = Tls.context(pc.key, pc.cert);
        Session c = Session.dial(java.net.InetAddress.getByName("127.0.0.1"), PORT, 3000,
                ctx, pc.name, null, code, pc.trust);
        check("client learned phone name", "Test Phone".equals(c.peerName));
        check("client presented its own certificate",
                c.sock.getSession().getLocalCertificates() != null);
        c.closeQuietly();
        server.join(4000);
        ss.close();

        check("no server error", serverErr.get() == null);
        if (serverErr.get() != null) serverErr.get().printStackTrace();
        check("listening side presented its certificate", serverHadCert.get().booleanValue());
        check("phone pinned the laptop", pc.fp().equals(firstKey(phone.trust.map)));
        check("laptop pinned the phone", phone.fp().equals(firstKey(pc.trust.map)));
    }

    static void testTransfer(final Identity phone, final Identity pc, File tmp, boolean resume)
            throws Exception {
        section(resume ? "resumed transfer" : "full transfer");

        File srcDir = new File(tmp, "src");
        File dstDir = new File(tmp, resume ? "dst-resume" : "dst-full");
        srcDir.mkdirs();
        dstDir.mkdirs();

        int size = 12 * 1024 * 1024 + 12345;
        File src = new File(srcDir, resume ? "resumed.bin" : "movie.bin");
        byte[] content = randomBytes(size);
        writeAll(src, content, size);
        String wantHash = Hexes.hex(Hexes.sha256(content));

        Item it = new Item();
        it.id = PcStore.idFor(src);
        it.name = src.getName();
        it.size = src.length();
        it.destCode = Dest.DOWNLOADS.code;
        it.mime = "application/octet-stream";
        it.sourceHint = srcDir.getAbsolutePath();
        it.handle = src;

        long seeded = 0;
        if (resume) {
            // Stage a half-finished transfer the way a dropped connection leaves one.
            seeded = 5L * 1024 * 1024;
            File partDir = new File(dstDir, ".incoming");
            partDir.mkdirs();
            File part = new File(partDir, it.id + ".part");
            writeAll(part, content, (int) seeded);
            Properties p = new Properties();
            p.setProperty("name", it.name);
            p.setProperty("size", Long.toString(it.size));
            p.setProperty("committed", Long.toString(seeded));
            OutputStream mo = new FileOutputStream(new File(partDir, it.id + ".meta"));
            p.store(mo, "test");
            mo.close();
        }

        final List<Item> queue = new ArrayList<Item>();
        queue.add(it);

        final AtomicReference<Exception> serverErr = new AtomicReference<Exception>();
        final Ev serverEv = new Ev();
        final PcStore.Sink sink = new PcStore.Sink(dstDir, false);

        SSLServerSocket ss = listen(phone);
        Thread server = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SSLSocket s = (SSLSocket) accept(PORT);
                    Session sess = Session.serve(s, phone.name, phone.trust, null);
                    if (sess == null) { serverErr.set(new IOException("peer rejected")); return; }
                    Ev p = serverEv;
                    Transfer.receiveRound(sess, sink, ACCEPT_ALL, p, Io.GO_ON);
                    Transfer.sendRound(sess, new ArrayList<Item>(), NO_SOURCE, p, Io.GO_ON);
                    sess.closeQuietly();
                } catch (Exception e) {
                    serverErr.set(e);
                }
            }
        });
        server.start();
        Thread.sleep(150);

        SSLContext ctx = Tls.context(pc.key, pc.cert);
        Session c = Session.dial(java.net.InetAddress.getByName("127.0.0.1"), PORT, 3000,
                ctx, pc.name, phone.fp(), null, pc.trust);

        
        Ev p = new Ev();
        long t0 = System.currentTimeMillis();
        Transfer.sendRound(c, queue, new PcStore.Source(), p, Io.GO_ON);
        Transfer.receiveRound(c, sink, ACCEPT_ALL, p, Io.GO_ON);
        long ms = System.currentTimeMillis() - t0;
        c.closeQuietly();
        server.join(20000);
        ss.close();

        check("no server error", serverErr.get() == null);
        if (serverErr.get() != null) serverErr.get().printStackTrace();
        check("transfer reported ok", "ok".equals(p.result));

        if (resume) {
            check("resumed from the staged offset, not from zero",
                    serverEv.startedAt == seeded);
        } else {
            check("started from zero", serverEv.startedAt == 0);
        }

        File got = new File(dstDir, it.name);
        check("file landed", got.isFile());
        check("size matches", got.length() == size);
        check("content hash matches end to end", wantHash.equals(hashOf(got)));
        check("no partial left behind",
                !new File(new File(dstDir, ".incoming"), it.id + ".part").exists());

        System.out.println("    " + Hexes.humanBytes(size) + " over loopback in " + ms
                + " ms (" + Hexes.humanRate(ms > 0 ? size * 1000L / ms : 0) + ")");
    }

    /**
     * Pause from the receiving side part way through, then resume.
     *
     * This is the case the old design got wrong: pausing tore the connection
     * down, so the queue was re-offered, already-answered files were re-prompted
     * and the two ends disagreed about what was paused. Here the socket must
     * survive the pause and the resumed transfer must start from the bytes that
     * were already committed, not from zero.
     */
    static void testPauseResume(final Identity phone, final Identity pc, File tmp) throws Exception {
        section("pause and resume mid-transfer");

        File srcDir = new File(tmp, "psrc");
        File dstDir = new File(tmp, "pdst");
        srcDir.mkdirs();
        dstDir.mkdirs();

        int size = 20 * 1024 * 1024;
        File src = new File(srcDir, "big.bin");
        byte[] content = randomBytes(size);
        writeAll(src, content, size);
        String wantHash = Hexes.hex(Hexes.sha256(content));

        final Item it = new Item();
        it.id = PcStore.idFor(src);
        it.name = src.getName();
        it.size = src.length();
        it.destCode = Dest.DOWNLOADS.code;
        it.mime = "application/octet-stream";
        it.handle = src;

        final PcStore.Sink sink = new PcStore.Sink(dstDir, false);
        final Ev serverEv = new Ev();
        final AtomicReference<Exception> serverErr = new AtomicReference<Exception>();
        final AtomicReference<Session> serverSess = new AtomicReference<Session>();

        // Receiver pauses once it has taken a few megabytes.
        // Counted rather than timed: the control is consulted between every
        // chunk, so this pauses at a known offset no matter how fast loopback is.
        final java.util.concurrent.atomic.AtomicLong checks =
                new java.util.concurrent.atomic.AtomicLong();
        final Io.Control pauseSoon = new Io.Control() {
            @Override public Io.Act check(Item i) {
                return checks.incrementAndGet() > 8 ? Io.Act.PAUSE : Io.Act.GO;
            }
        };

        SSLServerSocket ss = listen(phone);
        Thread server = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SSLSocket sk = (SSLSocket) accept(PORT);
                    Session sess = Session.serve(sk, phone.name, phone.trust, null);
                    serverSess.set(sess);
                    Ev counting = new Ev() {
                        @Override public void onBytes(Item i, long d, long t, long b) {
                            bytesSeen.set(d);
                        }
                    };
                    // First round: receive until the control says pause.
                    Transfer.receiveRound(sess, sink, ACCEPT_ALL, counting, pauseSoon);
                    serverEv.pausedAt = counting.pausedAt;
                    serverEv.result = counting.result;

                    // Second round: the same file again, now resumed.
                    bytesSeen.set(0);
                    Transfer.receiveRound(sess, sink, ACCEPT_ALL, serverEv, Io.GO_ON);
                } catch (Exception e) {
                    serverErr.set(e);
                }
            }
        });
        server.start();
        Thread.sleep(150);

        SSLContext ctx = Tls.context(pc.key, pc.cert);
        Session c = Session.dial(java.net.InetAddress.getByName("127.0.0.1"), PORT, 3000,
                ctx, pc.name, phone.fp(), null, pc.trust);

        Ev clientEv = new Ev();
        List<Item> queue = new ArrayList<Item>();
        queue.add(it);
        Map<String, Io.Outcome> r1 =
                Transfer.sendRound(c, queue, new PcStore.Source(), clientEv, Io.GO_ON);

        check("sender saw the pause", r1.get(it.id) == Io.Outcome.PAUSED);
        check("connection survived the pause", !c.sock.isClosed());
        long paused = clientEv.pausedAt;
        check("paused part way through", paused > 0 && paused < size);

        // Resume: offer the same file again on the same connection.
        Item again = new Item();
        again.id = it.id;
        again.name = it.name;
        again.size = it.size;
        again.destCode = it.destCode;
        again.mime = it.mime;
        again.handle = src;

        Ev resumeEv = new Ev();
        List<Item> q2 = new ArrayList<Item>();
        q2.add(again);
        Map<String, Io.Outcome> r2 =
                Transfer.sendRound(c, q2, new PcStore.Source(), resumeEv, Io.GO_ON);

        check("resumed transfer completed", r2.get(it.id) == Io.Outcome.DONE);
        check("resumed from the paused offset, not zero", resumeEv.startedAt > 0);

        c.closeQuietly();
        server.join(20000);
        ss.close();

        check("no server error", serverErr.get() == null);
        if (serverErr.get() != null) serverErr.get().printStackTrace();

        File got = new File(dstDir, it.name);
        check("file landed", got.isFile());
        check("content survived the pause", got.isFile() && wantHash.equals(hashOf(got)));
    }

    static final java.util.concurrent.atomic.AtomicLong bytesSeen =
            new java.util.concurrent.atomic.AtomicLong();

    static void testUnpairedRejected(final Identity phone, File tmp) throws Exception {
        section("unpaired device is dropped");
        final Identity stranger = new Identity("Friend Laptop");

        final AtomicReference<Boolean> served = new AtomicReference<Boolean>(Boolean.TRUE);
        SSLServerSocket ss = listen(phone);
        Thread server = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SSLSocket s = (SSLSocket) accept(PORT);
                    Session sess = Session.serve(s, phone.name, phone.trust, null);
                    served.set(Boolean.valueOf(sess != null));
                    if (sess != null) sess.closeQuietly();
                } catch (Exception e) {
                    served.set(Boolean.FALSE);
                }
            }
        });
        server.start();
        Thread.sleep(150);

        boolean threw = false;
        try {
            SSLContext ctx = Tls.context(stranger.key, stranger.cert);
            Session c = Session.dial(java.net.InetAddress.getByName("127.0.0.1"), PORT, 3000,
                    ctx, stranger.name, phone.fp(), null, stranger.trust);
            // Reaching here means the phone answered a device it has never paired with.
            Transfer.sendRound(c, new ArrayList<Item>(), NO_SOURCE, SILENT, Io.GO_ON);
            c.closeQuietly();
        } catch (Exception e) {
            threw = true;
        }
        server.join(4000);
        ss.close();

        check("phone refused to serve the stranger", !served.get().booleanValue());
        check("stranger's session failed", threw);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static final class MemTrust implements Session.Trust {
        final Map<String, String> map = new HashMap<String, String>();
        @Override public String nameFor(String fp) { return map.get(fp); }
        @Override public void remember(String fp, String name) { map.put(fp, name); }
    }

    static final class Identity {
        final String name;
        final PrivateKey key;
        final X509Certificate cert;
        final MemTrust trust = new MemTrust();

        Identity(String name) throws Exception {
            this.name = name;
            KeyPair kp = Certs.newKeyPair();
            this.cert = Certs.selfSign(kp, name);
            this.key = kp.getPrivate();
        }

        String fp() { return Certs.fingerprint(cert); }
    }

    static SSLServerSocket listen(Identity id) throws Exception {
        SSLContext ctx = Tls.context(id.key, id.cert);
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(PORT);
        ss.setNeedClientAuth(true);
        SERVER.set(ss);
        return ss;
    }

    static final AtomicReference<SSLServerSocket> SERVER = new AtomicReference<SSLServerSocket>();

    static java.net.Socket accept(int port) throws IOException {
        return SERVER.get().accept();
    }

        /** Records what the engine reported, so tests can assert on it. */
    static class Ev implements Io.Events {
        volatile long startedAt = -1;
        volatile String result = "none";
        volatile long pausedAt = -1;
        volatile boolean cancelled;

        @Override public void onStart(Item it, long from, boolean sending) { startedAt = from; }
        @Override public void onBytes(Item it, long d, long t, long bps) { }
        @Override public void onDone(Item it, boolean ok, String m) { result = ok ? "ok" : m; }
        @Override public void onPaused(Item it, long d, long t, boolean s) { pausedAt = d; result = "paused"; }
        @Override public void onCancelled(Item it) { cancelled = true; result = "cancelled"; }
        @Override public void log(String m) { }
    }

    static final Io.Approver ACCEPT_ALL = new Io.Approver() {
        @Override public boolean[] decide(List<Item> offered) {
            boolean[] a = new boolean[offered.size()];
            java.util.Arrays.fill(a, true);
            return a;
        }
    };

    static final Io.Source NO_SOURCE = new Io.Source() {
        @Override public java.io.InputStream open(Item it) throws IOException {
            throw new IOException("nothing to send");
        }
    };

    static final Ev SILENT = new Ev();

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    static void writeAll(File f, byte[] data, int len) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try { out.write(data, 0, len); } finally { out.close(); }
    }

    static String hashOf(File f) throws IOException {
        java.security.MessageDigest md = Hexes.sha256();
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return Hexes.hex(md.digest());
    }

    static String firstKey(Map<String, String> m) {
        for (String k : m.keySet()) return k;
        return null;
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static void section(String s) {
        System.out.println();
        System.out.println("== " + s);
    }

    static void check(String what, boolean ok) {
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }
}
