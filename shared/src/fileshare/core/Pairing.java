package fileshare.core;

import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One-time pairing over a short typed code.
 *
 * The laptop shows an 8-character code; you type it on the phone. That code is
 * the out-of-band channel that closes the man-in-the-middle window: an attacker
 * on the network can see the TLS handshake but cannot produce the proof without
 * the code, and cannot relay it because the proof is bound to both certificates.
 *
 * A typed code rather than a QR scan is a deliberate trade: it costs a few
 * seconds once, and it means the phone app never requests CAMERA permission.
 *
 * 40 bits of entropy is plenty here because the code is single-use, valid for 90
 * seconds, and the listener drops the connection after one failed attempt. There
 * is no online guessing to speak of.
 */
public final class Pairing {
    private Pairing() {}

    /** Crockford-ish: no 0/O or 1/I/L, so the code cannot be mistyped ambiguously. */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    public static final int CODE_LEN = 8;
    public static final long VALID_MS = 90_000L;

    private static final String CONTEXT = "fileshare-pair-v1";
    private static final int PBKDF2_ROUNDS = 120_000;

    public static String newCode() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(CODE_LEN + 1);
        for (int i = 0; i < CODE_LEN; i++) {
            if (i == 4) sb.append('-');
            sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Accepts the code with or without the dash, in any case, with stray spaces. */
    public static String normalize(String typed) {
        if (typed == null) return "";
        StringBuilder sb = new StringBuilder();
        String up = typed.toUpperCase(Locale.US);
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            // "1" is the one realistic misread: I and O are not in the alphabet
            // at all, so folding 1 to L is unambiguous. Anything else that is not
            // in the alphabet is dropped, which trips the length check and gives
            // the user a clear "code incomplete" rather than a silent mismatch.
            if (c == '1') c = 'L';
            for (char a : ALPHABET) {
                if (a == c) { sb.append(c); break; }
            }
        }
        return sb.toString();
    }

    public static boolean looksComplete(String typed) {
        return normalize(typed).length() == CODE_LEN;
    }

    /**
     * Canonical display form of whatever the user has typed so far.
     *
     * Folds case, drops anything outside the alphabet, caps the length, and puts
     * the dash back after the fourth character. The laptop runs every keystroke
     * through this so the field can only ever hold something valid, which means
     * nobody has to wonder whether to type the dash or whether case matters.
     */
    public static String format(String typed) {
        String clean = normalize(typed);
        if (clean.length() > CODE_LEN) clean = clean.substring(0, CODE_LEN);
        return clean.length() > 4
                ? clean.substring(0, 4) + "-" + clean.substring(4)
                : clean;
    }

    public static byte[] pskFromCode(String code) throws GeneralSecurityException {
        String norm = normalize(code);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(
                    norm.toCharArray(), CONTEXT.getBytes("UTF-8"), PBKDF2_ROUNDS, 256);
            SecretKey key = f.generateSecret(spec);
            return key.getEncoded();
        } catch (UnsupportedEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /**
     * Proof of code knowledge, bound to the exact TLS certificates in use.
     *
     * A relaying attacker necessarily terminates TLS on both sides and therefore
     * presents different certificates than the endpoints see, so the transcript
     * it can produce will not match either endpoint's expected proof.
     */
    public static byte[] proof(byte[] psk,
                               String role,
                               X509Certificate clientCert,
                               X509Certificate serverCert)
            throws GeneralSecurityException {
        try {
            String transcript = CONTEXT + "|" + role + "|"
                    + Hexes.hex(Hexes.sha256(clientCert.getEncoded())) + "|"
                    + Hexes.hex(Hexes.sha256(serverCert.getEncoded()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(psk, "HmacSHA256"));
            return mac.doFinal(transcript.getBytes("UTF-8"));
        } catch (CertificateEncodingException e) {
            throw new GeneralSecurityException(e);
        } catch (UnsupportedEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    public static final String ROLE_CLIENT = "client";
    public static final String ROLE_SERVER = "server";
}
