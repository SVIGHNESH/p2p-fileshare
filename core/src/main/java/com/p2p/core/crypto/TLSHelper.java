package com.p2p.core.crypto;

import com.p2p.core.chunking.FileChunker;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

public class TLSHelper {

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    /**
     * T0.5: SHA-256 (hex) of this install's TLS public key — its stable peer identity. Computed
     * once at {@link #init} time from the keystore and advertised to the tracker (via
     * {@link com.p2p.core.tracker.TrackerClient}) so downloaders can pin transfer connections to it.
     */
    private static volatile String localFingerprint;

    private static final String KEYSTORE_PASS = "p2pshare";
    private static final String KEYSTORE_FILE = "keystore.p12";
    private static final String KEY_ALIAS = "p2pshare";

    /**
     * Initialize TLS with a per-install keystore under {@code storageDir} (the app files dir
     * on Android, {@code ~/.p2pshare} on desktop). The keystore is loaded if present and
     * generated (self-signed, persisted) if absent, so a clean checkout works with no
     * pre-bundled secret. Must be called before {@link #createServerSocket}/{@link #createClientSocket}.
     *
     * <p>Persisting the keystore keeps a stable identity across restarts, which the planned
     * trust-on-first-use peer pinning (T0.5) will build on.
     */
    public static synchronized void init(File storageDir) throws Exception {
        if (serverContext != null) return;
        KeyStore ks = loadOrCreateKeyStore(new File(storageDir, KEYSTORE_FILE));
        initContexts(ks);
    }

    /** Convenience for desktop: keystore lives under {@code ~/.p2pshare}. */
    public static synchronized void init() throws Exception {
        init(new File(System.getProperty("user.home"), ".p2pshare"));
    }

    private static KeyStore loadOrCreateKeyStore(File keystoreFile) throws Exception {
        if (keystoreFile.isFile()) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream is = new FileInputStream(keystoreFile)) {
                ks.load(is, KEYSTORE_PASS.toCharArray());
            }
            return ks;
        }

        KeyStore ks = SelfSignedCert.generateKeyStore(KEY_ALIAS, KEYSTORE_PASS.toCharArray());
        File parent = keystoreFile.getParentFile();
        if (parent != null) parent.mkdirs();
        // Write to a temp file then rename, so a crash mid-write never leaves a half-written
        // keystore that a later run would fail to load.
        File tmp = new File(parent, keystoreFile.getName() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            ks.store(os, KEYSTORE_PASS.toCharArray());
        }
        try {
            Files.move(tmp.toPath(), keystoreFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), keystoreFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return ks;
    }

    /** Test seam: drop the cached contexts so a later {@link #init(File)} re-reads a fresh keystore dir. */
    static synchronized void resetForTest() {
        serverContext = null;
        clientContext = null;
        localFingerprint = null;
    }

    private static void initContexts(KeyStore ks) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS.toCharArray());

        // T0.5: derive this install's stable identity from its own public key. Hashing the
        // SubjectPublicKeyInfo (getPublicKey().getEncoded()) rather than the whole certificate keeps
        // the identity stable even if the cert were ever regenerated around the same key.
        localFingerprint = fingerprintOf(ks.getCertificate(KEY_ALIAS));

        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };

        // "TLS" negotiates the best mutually-supported version (TLS 1.3 was only added
        // on Android API 29; minSdk here is 26, so don't pin TLSv1.3).
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), trustAll, new SecureRandom());

        clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustAll, new SecureRandom());
    }

    public static SSLServerSocket createServerSocket(int port) throws IOException {
        if (serverContext == null) throw new IllegalStateException("TLSHelper.init() not called");
        SSLServerSocket ss = (SSLServerSocket) serverContext
                .getServerSocketFactory().createServerSocket(port);
        ss.setNeedClientAuth(false);
        return ss;
    }

    /**
     * This install's stable public-key identity — SHA-256 (hex) of its TLS public key (T0.5), or
     * {@code null} if {@link #init} has not run / the keystore holds no key entry. A peer advertises
     * this to the tracker so downloaders can pin transfer connections to it.
     */
    public static String getLocalFingerprint() {
        return localFingerprint;
    }

    public static SSLSocket createClientSocket(String host, int port) throws IOException {
        return createClientSocket(host, port, null);
    }

    /**
     * Opens a TLS client socket and, when {@code expectedFingerprint} is non-blank, <b>pins</b> the
     * peer's identity to it (T0.5): after the handshake the peer's leaf certificate public-key
     * fingerprint must equal {@code expectedFingerprint}, otherwise the socket is closed and an
     * {@link SSLPeerUnverifiedException} is thrown. This turns the trust-all transport into one that
     * actually authenticates the peer against the key the tracker advertised, so a LAN man-in-the-middle
     * presenting any other certificate is rejected before a single application byte is sent.
     *
     * <p>A {@code null}/blank fingerprint keeps the legacy behaviour (encryption only, no pinning) for
     * peers that advertise no key. <b>Note:</b> the advertised fingerprint is only as trustworthy as the
     * channel that carried it; the tracker is still plaintext (TR.6), so a tracker-path attacker could
     * substitute the fingerprint. This slice closes the peer-connection MITM; TR.6 closes the rest.
     */
    public static SSLSocket createClientSocket(String host, int port, String expectedFingerprint)
            throws IOException {
        if (clientContext == null) throw new IllegalStateException("TLSHelper.init() not called");
        SSLSocket s = (SSLSocket) clientContext
                .getSocketFactory().createSocket(host, port);
        s.startHandshake();
        if (expectedFingerprint != null && !expectedFingerprint.isBlank()) {
            verifyPinnedIdentity(s, expectedFingerprint);
        }
        return s;
    }

    /** Closes {@code s} and throws if the peer's key fingerprint does not match the pin. */
    private static void verifyPinnedIdentity(SSLSocket s, String expectedFingerprint) throws IOException {
        String actual;
        try {
            Certificate[] chain = s.getSession().getPeerCertificates();
            if (chain == null || chain.length == 0) {
                throw new SSLPeerUnverifiedException("peer presented no certificate");
            }
            actual = fingerprintOf(chain[0]);
        } catch (SSLPeerUnverifiedException e) {
            closeQuietly(s);
            throw e;
        }
        if (actual == null || !expectedFingerprint.equalsIgnoreCase(actual)) {
            closeQuietly(s);
            throw new SSLPeerUnverifiedException(
                    "peer key fingerprint mismatch (expected " + expectedFingerprint + ", got " + actual + ")");
        }
    }

    /** SHA-256 (hex) of a certificate's public key (SubjectPublicKeyInfo), or {@code null}. */
    private static String fingerprintOf(Certificate cert) {
        if (cert == null) return null;
        return FileChunker.sha256(cert.getPublicKey().getEncoded());
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }
}
