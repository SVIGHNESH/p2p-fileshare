package com.p2p.core.crypto;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;

/**
 * Generates a self-signed TLS certificate at runtime using keytool (ships with every JDK).
 * Works on Java 11–26+. No internal JDK classes needed.
 * Users never touch this — it just works behind the scenes.
 */
public class TLSHelper {

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private static final String KEYSTORE_PASS = "p2pshare";
    private static final Path KEYSTORE_PATH = Path.of(
            System.getProperty("user.home"), ".p2pshare", "keystore.p12");

    static {
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException("TLS init failed", e);
        }
    }

    private static void init() throws Exception {
        // Generate keystore once using keytool if it doesn't exist
        if (!Files.exists(KEYSTORE_PATH)) {
            Files.createDirectories(KEYSTORE_PATH.getParent());
            generateKeystore();
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(KEYSTORE_PATH)) {
            ks.load(is, KEYSTORE_PASS.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS.toCharArray());

        // Trust all peers — self-signed certs, no CA chain needed for LAN P2P
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };

        serverContext = SSLContext.getInstance("TLSv1.3");
        serverContext.init(kmf.getKeyManagers(), trustAll, new SecureRandom());

        clientContext = SSLContext.getInstance("TLSv1.3");
        clientContext.init(null, trustAll, new SecureRandom());
    }

    private static void generateKeystore() throws Exception {
        String keytool = ProcessHandle.current().info().command()
                .map(cmd -> cmd.replace("java", "keytool"))
                .filter(k -> new File(k).exists())
                .orElse("keytool");

        ProcessBuilder pb = new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias", "p2pshare",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-keystore", KEYSTORE_PATH.toString(),
                "-storepass", KEYSTORE_PASS,
                "-keypass", KEYSTORE_PASS,
                "-dname", "CN=P2PShareNode, O=P2PShare, C=IN",
                "-storetype", "PKCS12",
                "-noprompt"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("keytool failed (exit " + code + "): " + output);
    }

    public static SSLServerSocket createServerSocket(int port) throws IOException {
        SSLServerSocket ss = (SSLServerSocket) serverContext
                .getServerSocketFactory().createServerSocket(port);
        ss.setNeedClientAuth(false);
        return ss;
    }

    public static SSLSocket createClientSocket(String host, int port) throws IOException {
        SSLSocket s = (SSLSocket) clientContext
                .getSocketFactory().createSocket(host, port);
        s.startHandshake();
        return s;
    }
}
