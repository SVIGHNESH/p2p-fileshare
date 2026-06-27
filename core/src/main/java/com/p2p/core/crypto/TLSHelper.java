package com.p2p.core.crypto;

import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;

public class TLSHelper {

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private static final String KEYSTORE_PASS = "p2pshare";

    // Call this once at startup with a writable directory (app files dir on Android,
    // ~/.p2pshare on desktop). Must be called before getServerContext/getClientContext.
    public static synchronized void init(File storageDir) throws Exception {
        // storageDir is ignored — the keystore is bundled as a classpath resource so that
        // desktop and Android share one identical cert. (The trust manager accepts any cert,
        // so a single shared self-signed cert is equivalent to per-peer generation but works
        // identically on both platforms, with no JDK-only sun.security.x509 dependency.)
        init();
    }

    public static synchronized void init() throws Exception {
        if (serverContext != null) return;

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = TLSHelper.class.getResourceAsStream("/p2pshare.p12")) {
            if (is == null) throw new IllegalStateException("Bundled keystore /p2pshare.p12 not found on classpath");
            ks.load(is, KEYSTORE_PASS.toCharArray());
        }

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
