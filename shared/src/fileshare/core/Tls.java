package fileshare.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 1.3 with mutual authentication, where trust is decided by us and not by
 * the TLS layer.
 *
 * The TrustManager here deliberately accepts any certificate. That is not a hole:
 * no data crosses this connection until the peer's fingerprint has been checked
 * against the value pinned during pairing. Doing it this way avoids fighting JSSE
 * over CA chains, hostnames and expiry dates -- none of which mean anything for
 * two devices that already know each other's exact public key.
 */
public final class Tls {
    private Tls() {}

    /** Last key type the TLS stack asked the key manager for. Diagnostics only. */
    static final AtomicReference<String> LAST_ASKED = new AtomicReference<String>("(never asked)");

    /** What the self test actually negotiated. Diagnostics only. */
    static final AtomicReference<String> LAST_SESSION = new AtomicReference<String>("(none)");

    public static SSLContext context(PrivateKey key, X509Certificate cert)
            throws GeneralSecurityException, IOException {
        if (key == null || cert == null) {
            throw new GeneralSecurityException("no identity loaded - the app is still locked");
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{new FixedIdentity(key, cert)},
                new TrustManager[]{ACCEPT_ALL},
                new java.security.SecureRandom());
        return ctx;
    }

    // ------------------------------------------------------------------
    // Our single identity
    // ------------------------------------------------------------------

    /**
     * Hands the one key and certificate we have to the TLS stack directly.
     *
     * The obvious route -- stuff them into an in-memory PKCS12 and feed that to
     * KeyManagerFactory -- works on the JVM and silently produces a KeyManager
     * with no entries on Android, where BoringSSL then aborts with
     * NO_CERTIFICATE_SET. There is exactly one identity here, so there is nothing
     * to look up and no keystore, password or provider to get wrong.
     */
    private static final class FixedIdentity extends X509ExtendedKeyManager {
        private static final String ALIAS = "fileshare";

        private final PrivateKey key;
        private final X509Certificate[] chain;

        FixedIdentity(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.chain = new X509Certificate[]{cert};
        }

        /**
         * Always offer the one identity we have.
         *
         * Filtering on key type looks tidy and is a trap: the names vary between
         * JSSE and Conscrypt ("EC", "ECDSA", "EC_EC", "EC_RSA", signature-scheme
         * names under TLS 1.3), and returning null for one we failed to anticipate
         * leaves the server with no certificate at all.
         */
        private static String offer(Object askedFor) {
            LAST_ASKED.set(askedFor instanceof String[]
                    ? Arrays.toString((String[]) askedFor)
                    : String.valueOf(askedFor));
            return ALIAS;
        }

        @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
            offer(keyType);
            return new String[]{ALIAS};
        }

        @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
            offer(keyType);
            return new String[]{ALIAS};
        }

        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket s) {
            return offer(keyType);
        }

        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket s) {
            return offer(keyType);
        }

        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine e) {
            return offer(keyType);
        }

        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine e) {
            return offer(keyType);
        }

        @Override public X509Certificate[] getCertificateChain(String alias) {
            return chain.clone();
        }

        @Override public PrivateKey getPrivateKey(String alias) {
            return key;
        }
    }

    private static final X509TrustManager ACCEPT_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no peer certificate");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no peer certificate");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    // ------------------------------------------------------------------
    // Socket setup
    // ------------------------------------------------------------------

    /**
     * Socket tuning for bulk transfer over a phone hotspot.
     *
     * Big socket buffers matter more than anything else here: at tens of MB/s
     * with a few milliseconds of Wi-Fi latency, the default buffer is small
     * enough to stall the sender waiting for ACKs.
     */
    public static void tune(Socket s) throws IOException {
        s.setTcpNoDelay(true);
        s.setSendBufferSize(4 * 1024 * 1024);
        s.setReceiveBufferSize(4 * 1024 * 1024);
        s.setKeepAlive(true);
        s.setSoTimeout(30_000);
    }

    /**
     * Ask for TLS 1.3, without insisting on a specific cipher suite list.
     *
     * Conscrypt treats the TLS 1.3 suites as always-on and not configurable, so
     * handing it an explicit list of only those names is a good way to end up
     * with a socket that cannot negotiate anything. Every platform we run on
     * prefers AES-GCM on hardware AES by default anyway, so there is nothing to
     * gain by overriding it.
     */
    public static void restrict(SSLSocket s) {
        List<String> want = new ArrayList<String>();
        for (String p : s.getSupportedProtocols()) {
            if ("TLSv1.3".equals(p)) want.add(p);
        }
        if (!want.isEmpty()) {
            s.setEnabledProtocols(want.toArray(new String[0]));
        }
    }

    public static X509Certificate peerCert(SSLSocket s) throws IOException {
        try {
            java.security.cert.Certificate[] chain = s.getSession().getPeerCertificates();
            if (chain == null || chain.length == 0) throw new IOException("peer sent no certificate");
            return (X509Certificate) chain[0];
        } catch (SSLPeerUnverifiedException e) {
            throw new IOException("peer did not authenticate", e);
        }
    }

    // ------------------------------------------------------------------
    // Self test
    // ------------------------------------------------------------------

    /**
     * Runs a complete TLS handshake against ourselves over loopback.
     *
     * Worth its keep because this layer behaves differently on the JVM and on
     * Android, and a broken setup otherwise shows up as an opaque BoringSSL error
     * only once the other device is already trying to connect. This answers "is
     * our own TLS sound" locally, in milliseconds, with both sides' errors.
     *
     * @return null on success, otherwise what went wrong.
     */
    public static String selfTest(PrivateKey key, X509Certificate cert) {
        return handshake(key, cert);
    }

    private static String handshake(PrivateKey key, X509Certificate cert) {
        SSLServerSocket server = null;
        try {
            final SSLContext ctx = context(key, cert);
            server = (SSLServerSocket) ctx.getServerSocketFactory()
                    .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setNeedClientAuth(true);

            final SSLServerSocket ss = server;
            final AtomicReference<String> serverProblem = new AtomicReference<String>(null);

            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    SSLSocket s = null;
                    try {
                        s = (SSLSocket) ss.accept();
                        restrict(s);
                        s.startHandshake();
                        if (s.getSession().getLocalCertificates() == null) {
                            serverProblem.set("listener presented no certificate");
                        }
                        s.getOutputStream().write(7);
                        s.getOutputStream().flush();
                    } catch (Throwable e) {
                        serverProblem.set("listener: " + describe(e));
                    } finally {
                        if (s != null) try { s.close(); } catch (IOException ignored) { }
                    }
                }
            }, "tls-selftest-listener");
            t.setDaemon(true);
            t.start();

            String clientProblem = null;
            SSLSocket c = null;
            try {
                c = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", ss.getLocalPort());
                c.setUseClientMode(true);
                restrict(c);
                c.setSoTimeout(8000);
                c.startHandshake();
                if (c.getSession().getLocalCertificates() == null) {
                    clientProblem = "dialler presented no certificate";
                } else if (c.getInputStream().read() != 7) {
                    clientProblem = "no data came back";
                }
                LAST_SESSION.set(c.getSession().getProtocol() + " "
                        + c.getSession().getCipherSuite());
            } catch (Throwable e) {
                clientProblem = "dialler: " + describe(e);
            } finally {
                if (c != null) try { c.close(); } catch (IOException ignored) { }
            }

            t.join(8000);

            // The listener's error is the useful one: a failure there is what the
            // dialler sees second-hand as an opaque alert.
            if (serverProblem.get() != null) return serverProblem.get();
            return clientProblem;
        } catch (Throwable e) {
            return describe(e);
        } finally {
            if (server != null) try { server.close(); } catch (IOException ignored) { }
        }
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 5) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                String m = t.getMessage().replace('\n', ' ');
                sb.append(": ").append(m.length() > 220 ? m.substring(0, 220) : m);
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    public static String lastKeyTypeAsked() {
        return LAST_ASKED.get();
    }

    /** Protocol and cipher suite the self test negotiated. */
    public static String lastSession() {
        return LAST_SESSION.get();
    }
}
