package com.ravi.fileshare;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import fileshare.core.Certs;
import fileshare.core.Session;

/**
 * This phone's identity and the laptop it trusts.
 *
 * The TLS private key is stored encrypted with an AES key that lives in the
 * hardware-backed Android Keystore and is marked as requiring user
 * authentication. Gating only the user interface would be cosmetic -- a modified
 * build could walk straight past it. Gating the key means that without your
 * fingerprint or PIN the identity cannot be decrypted at all, so the phone
 * cannot authenticate to the laptop and no transfer can happen.
 */
public final class Vault implements Session.Trust {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_ALIAS = "fileshare-master-v1";
    private static final String IDENTITY_FILE = "identity.bin";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    /**
     * One authentication covers a whole transfer session. Prompting per file
     * would make a thirty-file batch unusable, and an app people turn off is
     * worse than one that asks once.
     */
    private static final int AUTH_WINDOW_SECONDS = 300;

    private static volatile Vault instance;

    /**
     * One instance per process: the unlocked private key lives in memory here,
     * and both the user interface and the transfer service need the same one.
     */
    public static synchronized Vault get(Context c) {
        if (instance == null) instance = new Vault(c);
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;

    private PrivateKey key;
    private X509Certificate cert;
    private String fingerprint;

    public Vault(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences("fileshare", Context.MODE_PRIVATE);
    }

    /** True when the device has a PIN, pattern, password or biometric set up. */
    public boolean deviceSecure() {
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    public boolean isUnlocked() {
        return key != null && cert != null;
    }

    /**
     * Decrypt the stored identity, creating one on first run.
     *
     * Must be called after a successful biometric or device-credential prompt,
     * because the master key only becomes usable inside the authentication window.
     */
    public void unlock() throws Exception {
        File f = new File(ctx.getFilesDir(), IDENTITY_FILE);
        SecretKey master = masterKey();

        if (f.isFile()) {
            byte[] blob = readAll(f);
            byte[] plain = decrypt(master, blob);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
            byte[] pkcs8 = new byte[in.readInt()];
            in.readFully(pkcs8);
            byte[] der = new byte[in.readInt()];
            in.readFully(der);

            key = java.security.KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } else {
            KeyPair kp = Certs.newKeyPair();
            cert = Certs.selfSign(kp, "FileShare-Phone");
            key = kp.getPrivate();

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            byte[] pkcs8 = key.getEncoded();
            out.writeInt(pkcs8.length);
            out.write(pkcs8);
            byte[] der = cert.getEncoded();
            out.writeInt(der.length);
            out.write(der);
            out.flush();

            writeAll(f, encrypt(master, bytes.toByteArray()));
        }
        fingerprint = Certs.fingerprint(cert);
    }

    public void lock() {
        key = null;
        cert = null;
    }

    public PrivateKey key() { return key; }

    public X509Certificate cert() { return cert; }

    public String fingerprint() { return fingerprint; }

    // ------------------------------------------------------------------
    // Keystore
    // ------------------------------------------------------------------

    private SecretKey masterKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(MASTER_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }

        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);

        if (deviceSecure()) {
            b.setUserAuthenticationRequired(true);
            b.setUserAuthenticationParameters(AUTH_WINDOW_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            // Enrolling a new fingerprint would otherwise destroy this key and
            // silently break the pairing. Adding a fingerprint already requires
            // the device PIN, which is one of the factors that unlocks this key
            // anyway, so keeping it valid costs nothing real and saves a
            // confusing re-pair every time you re-enrol.
            b.setInvalidatedByBiometricEnrollment(false);
        }

        gen.init(b.build());
        return gen.generateKey();
    }

    private static byte[] encrypt(SecretKey k, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] decrypt(SecretKey k, byte[] blob) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES));
        return c.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
    }

    /** Thrown up to the UI so it can explain that a re-pair is needed. */
    public static boolean isKeyInvalidated(Throwable t) {
        while (t != null) {
            if (t.getClass().getName().contains("KeyPermanentlyInvalidated")) return true;
            t = t.getCause();
        }
        return false;
    }

    public void wipeIdentity() {
        lock();
        new File(ctx.getFilesDir(), IDENTITY_FILE).delete();
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            ks.deleteEntry(MASTER_ALIAS);
        } catch (Exception ignored) {
            // Nothing depends on the old key surviving.
        }
        forgetLaptop();
    }

    // ------------------------------------------------------------------
    // Trust store
    // ------------------------------------------------------------------

    @Override
    public String nameFor(String fp) {
        String known = prefs.getString("laptopFp", null);
        if (known == null || !known.equals(fp)) return null;
        return prefs.getString("laptopName", "Laptop");
    }

    @Override
    public void remember(String fp, String name) {
        prefs.edit()
                .putString("laptopFp", fp)
                .putString("laptopName", name == null || name.isEmpty() ? "Laptop" : name)
                .apply();
    }

    public String laptopFingerprint() { return prefs.getString("laptopFp", null); }

    public String laptopName() { return prefs.getString("laptopName", null); }

    public boolean isPaired() { return laptopFingerprint() != null; }

    public void forgetLaptop() {
        prefs.edit().remove("laptopFp").remove("laptopName").apply();
    }

    public String deviceName() {
        String n = prefs.getString("deviceName", null);
        if (n != null && !n.trim().isEmpty()) return n;
        String model = android.os.Build.MODEL;
        return (model == null || model.isEmpty()) ? "Phone" : model;
    }

    // ------------------------------------------------------------------

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
    }
}
