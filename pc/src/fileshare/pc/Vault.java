package fileshare.pc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import fileshare.core.Certs;
import fileshare.core.Hexes;
import fileshare.core.Session;

/**
 * Everything persistent on the laptop: this device's TLS identity, the pinned
 * fingerprints of paired phones, and user settings.
 *
 * Storage is %LOCALAPPDATA%\FileShare, locked to the current user with icacls.
 * That protects the key from other accounts on the machine. It does not protect
 * it from code running as you -- neither would DPAPI, honestly -- so the real
 * mitigation for a compromised laptop is revoking the pairing from the phone.
 */
public final class Vault implements Session.Trust {

    private final File dir;
    private final File p12;
    private final File pwFile;
    private final File peersFile;
    private final File settingsFile;

    private PrivateKey key;
    private X509Certificate cert;
    private String fingerprint;

    private final Properties peers = new Properties();
    private final Properties settings = new Properties();

    public Vault() throws Exception {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isEmpty()) base = System.getProperty("user.home");
        dir = new File(base, "FileShare");
        boolean fresh = !dir.exists();
        if (fresh && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        if (fresh) lockDown(dir);

        p12 = new File(dir, "identity.p12");
        pwFile = new File(dir, "identity.pw");
        peersFile = new File(dir, "peers.properties");
        settingsFile = new File(dir, "settings.properties");

        load(peers, peersFile);
        load(settings, settingsFile);
        loadOrCreateIdentity();
    }

    private static void lockDown(File d) {
        try {
            String user = System.getProperty("user.name");
            new ProcessBuilder("icacls", d.getAbsolutePath(),
                    "/inheritance:r", "/grant:r", user + ":(OI)(CI)F")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception e) {
            // Not fatal: the directory is still under the user profile, which is
            // already inaccessible to other non-admin accounts by default.
        }
    }

    private void loadOrCreateIdentity() throws Exception {
        char[] pw = loadOrCreatePassword();
        KeyStore ks = KeyStore.getInstance("PKCS12");

        if (p12.exists()) {
            InputStream in = new FileInputStream(p12);
            try {
                ks.load(in, pw);
            } finally {
                in.close();
            }
            key = (PrivateKey) ks.getKey("id", pw);
            cert = (X509Certificate) ks.getCertificate("id");
        }

        if (key == null || cert == null) {
            KeyPair kp = Certs.newKeyPair();
            cert = Certs.selfSign(kp, "FileShare-PC");
            key = kp.getPrivate();
            ks.load(null, pw);
            ks.setKeyEntry("id", key, pw, new Certificate[]{cert});
            OutputStream out = new FileOutputStream(p12);
            try {
                ks.store(out, pw);
            } finally {
                out.close();
            }
        }
        fingerprint = Certs.fingerprint(cert);
    }

    private char[] loadOrCreatePassword() throws IOException {
        if (pwFile.exists()) {
            byte[] b = new byte[(int) pwFile.length()];
            InputStream in = new FileInputStream(pwFile);
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(b, "US-ASCII").trim().toCharArray();
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String pw = Hexes.hex(raw);
        OutputStream out = new FileOutputStream(pwFile);
        try {
            out.write(pw.getBytes("US-ASCII"));
        } finally {
            out.close();
        }
        return pw.toCharArray();
    }

    public PrivateKey key() { return key; }

    public X509Certificate cert() { return cert; }

    public String fingerprint() { return fingerprint; }

    public File dir() { return dir; }

    // ---- paired phones -------------------------------------------------

    @Override
    public String nameFor(String fp) {
        return peers.getProperty(fp);
    }

    @Override
    public void remember(String fp, String name) {
        peers.setProperty(fp, name == null ? "phone" : name);
        save(peers, peersFile, "paired phones");
    }

    public void forget(String fp) {
        peers.remove(fp);
        save(peers, peersFile, "paired phones");
    }

    public List<String[]> pairedPhones() {
        List<String[]> out = new ArrayList<String[]>();
        for (String fp : peers.stringPropertyNames()) {
            out.add(new String[]{fp, peers.getProperty(fp)});
        }
        return out;
    }

    public boolean hasPairing() {
        return !peers.isEmpty();
    }

    /** This build supports one phone; the first pinned fingerprint is it. */
    public String phoneFingerprint() {
        for (String fp : peers.stringPropertyNames()) return fp;
        return null;
    }

    public String phoneName() {
        String fp = phoneFingerprint();
        return fp == null ? null : peers.getProperty(fp);
    }

    // ---- settings ------------------------------------------------------

    public String deviceName() {
        String n = settings.getProperty("deviceName");
        if (n != null && !n.trim().isEmpty()) return n.trim();
        String host = System.getenv("COMPUTERNAME");
        return (host == null || host.isEmpty()) ? "Laptop" : host;
    }

    public void setDeviceName(String n) {
        settings.setProperty("deviceName", n);
        save(settings, settingsFile, "settings");
    }

    public File downloadDir() {
        String d = settings.getProperty("downloadDir");
        File f = (d == null || d.isEmpty())
                ? new File(System.getProperty("user.home"), "Downloads" + File.separator + "FileShare")
                : new File(d);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public void setDownloadDir(File f) {
        settings.setProperty("downloadDir", f.getAbsolutePath());
        save(settings, settingsFile, "settings");
    }

    public String manualHost() {
        return settings.getProperty("manualHost", "");
    }

    public void setManualHost(String h) {
        settings.setProperty("manualHost", h == null ? "" : h.trim());
        save(settings, settingsFile, "settings");
    }

    // ---- plumbing ------------------------------------------------------

    private static void load(Properties p, File f) {
        if (!f.exists()) return;
        try {
            InputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            System.err.println("could not read " + f + ": " + e.getMessage());
        }
    }

    private static void save(Properties p, File f, String comment) {
        try {
            OutputStream out = new FileOutputStream(f);
            try {
                p.store(out, comment);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            System.err.println("could not write " + f + ": " + e.getMessage());
        }
    }
}
