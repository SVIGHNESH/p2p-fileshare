package com.p2p.core.crypto;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;

public class TLSHelper {

    private static SSLContext serverContext;
    private static SSLContext clientContext;

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
    }

    private static void initContexts(KeyStore ks) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS.toCharArray());

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

    public static SSLSocket createClientSocket(String host, int port) throws IOException {
        if (clientContext == null) throw new IllegalStateException("TLSHelper.init() not called");
        SSLSocket s = (SSLSocket) clientContext
                .getSocketFactory().createSocket(host, port);
        s.startHandshake();
        return s;
    }
}
