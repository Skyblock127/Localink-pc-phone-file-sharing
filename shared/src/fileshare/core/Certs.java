package fileshare.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Self-signed EC P-256 certificates, built by hand in DER.
 *
 * We only need a certificate because TLS requires one to carry a public key.
 * Nothing in this system trusts a CA, a hostname, or an expiry date: identity is
 * the SHA-256 of the SubjectPublicKeyInfo, pinned at pairing time. Rolling the
 * DER by hand avoids a BouncyCastle dependency and keeps this file byte-identical
 * on the laptop and the phone.
 */
public final class Certs {
    private Certs() {}

    private static final SecureRandom RNG = new SecureRandom();

    // 1.2.840.10045.4.3.2 -- ecdsa-with-SHA256
    private static final byte[] OID_ECDSA_SHA256 =
            {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
    // 2.5.4.3 -- commonName
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
        return g.generateKeyPair();
    }

    public static X509Certificate selfSign(KeyPair kp, String commonName) throws GeneralSecurityException {
        try {
            byte[] tbs = tbsCertificate(kp.getPublic(), commonName);

            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(kp.getPrivate(), RNG);
            sig.update(tbs);
            byte[] signature = sig.sign();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(tbs);
            body.write(seq(OID_ECDSA_SHA256));
            body.write(bitString(signature));

            byte[] der = tlv(0x30, body.toByteArray());

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static byte[] tbsCertificate(PublicKey pub, String commonName) throws IOException {
        ByteArrayOutputStream t = new ByteArrayOutputStream();

        // [0] EXPLICIT version, v3 == 2
        t.write(tlv(0xA0, tlv(0x02, new byte[]{0x02})));

        // serialNumber: random, positive
        byte[] serial = new byte[16];
        RNG.nextBytes(serial);
        serial[0] &= 0x7F;
        if (serial[0] == 0) serial[0] = 0x01;
        t.write(tlv(0x02, serial));

        // signature algorithm (must match the outer one)
        t.write(seq(OID_ECDSA_SHA256));

        // issuer == subject, since this is self-signed
        byte[] name = name(commonName);
        t.write(name);

        // validity: backdate a day for clock skew, then 20 years.
        // UTCTime is valid through 2049, so this stays in range.
        long now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        v.write(tlv(0x17, f.format(new Date(now - 86400000L)).getBytes("US-ASCII")));
        v.write(tlv(0x17, f.format(new Date(now + 20L * 365 * 86400000L)).getBytes("US-ASCII")));
        t.write(tlv(0x30, v.toByteArray()));

        t.write(name);

        // SubjectPublicKeyInfo: getEncoded() on a PublicKey is already SPKI DER.
        t.write(pub.getEncoded());

        return tlv(0x30, t.toByteArray());
    }

    private static byte[] name(String cn) throws IOException {
        byte[] value = tlv(0x0C, cn.getBytes("UTF-8"));           // UTF8String
        ByteArrayOutputStream atv = new ByteArrayOutputStream();
        atv.write(OID_CN);
        atv.write(value);
        byte[] rdn = tlv(0x31, tlv(0x30, atv.toByteArray()));      // SET OF SEQUENCE
        return tlv(0x30, rdn);
    }

    private static byte[] seq(byte[] inner) {
        return tlv(0x30, inner);
    }

    private static byte[] bitString(byte[] content) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x00); // unused bits
        b.write(content);
        return tlv(0x03, b.toByteArray());
    }

    /** DER tag-length-value with long-form length when needed. */
    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int n = 0;
            for (int t = len; t > 0; t >>>= 8) n++;
            out.write(0x80 | n);
            for (int i = n - 1; i >= 0; i--) out.write((len >>> (8 * i)) & 0xff);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    /**
     * The device's permanent identity: SHA-256 over the SubjectPublicKeyInfo.
     * This is what gets pinned at pairing and checked on every later connection.
     */
    public static String fingerprint(X509Certificate cert) {
        return Hexes.hex(Hexes.sha256(cert.getPublicKey().getEncoded()));
    }

    /** Short form for showing the user, e.g. "a1b2 c3d4 e5f6". */
    public static String shortFingerprint(String full) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12 && i < full.length(); i += 4) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(full, i, Math.min(i + 4, full.length()));
        }
        return sb.toString();
    }
}
