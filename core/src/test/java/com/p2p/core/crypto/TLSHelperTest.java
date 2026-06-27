package com.p2p.core.crypto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the keystore-on-clean-checkout fix: {@link TLSHelper#init(File)} must generate a
 * self-signed keystore when none exists (previously it threw "Bundled keystore not found",
 * leaving the whole transfer layer dead E2E), persist it for a stable identity across
 * restarts, and produce certs that complete a real TLS handshake.
 *
 * <p>The certificate is built by hand-rolled DER ({@link SelfSignedCert}); the
 * round-trip + signature checks here localize any DER bug away from the socket tests.
 */
class TLSHelperTest {

    // Mirrors TLSHelper's private constant; the password is not a secret (a clean checkout
    // self-signs anew), so a test may legitimately reload the keystore with it.
    private static final char[] PASS = "p2pshare".toCharArray();

    @BeforeEach
    @AfterEach
    void reset() {
        TLSHelper.resetForTest();
    }

    @Test
    void generatedCertIsSelfConsistentAndValid() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate cert = SelfSignedCert.generateCertificate("CN=P2PShare", kp);

        // verify() throws on a bad self-signature; checkValidity() throws if the window is wrong.
        cert.verify(kp.getPublic());
        cert.checkValidity();
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal(),
                "a self-signed cert's subject and issuer must match");
        assertEquals("CN=P2PShare", cert.getSubjectX500Principal().getName());
    }

    @Test
    void eachKeyStoreGetsAFreshKey() throws Exception {
        KeyStore a = SelfSignedCert.generateKeyStore("p2pshare", PASS);
        KeyStore b = SelfSignedCert.generateKeyStore("p2pshare", PASS);
        PublicKey ka = ((X509Certificate) a.getCertificate("p2pshare")).getPublicKey();
        PublicKey kb = ((X509Certificate) b.getCertificate("p2pshare")).getPublicKey();
        assertNotEquals(ka, kb, "two fresh keystores must not share a key");
    }

    @Test
    void initGeneratesKeystoreWhenAbsent(@TempDir Path dir) throws Exception {
        File keystore = dir.resolve("keystore.p12").toFile();
        assertTrue(!keystore.exists(), "precondition: no keystore on a clean checkout");

        TLSHelper.init(dir.toFile());

        assertTrue(keystore.isFile(), "init() must create the keystore when absent");
        assertNotNull(loadCert(keystore), "the generated keystore must hold our key entry");
    }

    @Test
    void initReusesPersistedKeystoreAcrossRestart(@TempDir Path dir) throws Exception {
        TLSHelper.init(dir.toFile());
        X509Certificate first = loadCert(dir.resolve("keystore.p12").toFile());

        TLSHelper.resetForTest();
        TLSHelper.init(dir.toFile()); // second "boot" against the same dir
        X509Certificate second = loadCert(dir.resolve("keystore.p12").toFile());

        assertEquals(first.getSerialNumber(), second.getSerialNumber(),
                "a persisted keystore must be reloaded, not regenerated, across restarts");
    }

    @Test
    void serverAndClientCompleteARealHandshake(@TempDir Path dir) throws Exception {
        TLSHelper.init(dir.toFile());

        try (SSLServerSocket server = TLSHelper.createServerSocket(0)) {
            int port = server.getLocalPort();
            byte[] payload = "tls-round-trip".getBytes();
            byte[] echoed = new byte[payload.length];

            Thread serverThread = new Thread(() -> {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    byte[] buf = new DataInputStream(s.getInputStream()).readNBytes(payload.length);
                    s.getOutputStream().write(buf); // echo back over the encrypted channel
                    s.getOutputStream().flush();
                } catch (Exception ignored) {}
            });
            serverThread.start();

            try (SSLSocket client = TLSHelper.createClientSocket("127.0.0.1", port)) {
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                out.write(payload);
                out.flush();
                new DataInputStream(client.getInputStream()).readFully(echoed);
            }
            serverThread.join(5000);
            assertArrayEquals(payload, echoed, "bytes must survive the TLS round-trip intact");
        }
    }

    // ── T0.5: per-peer public-key identity + transfer-connection pinning ──────

    @Test
    void localFingerprintIsAStablePerInstallIdentity(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        TLSHelper.init(dirA.toFile());
        String a = TLSHelper.getLocalFingerprint();
        assertNotNull(a, "init() must derive a pinning identity from the keystore");
        assertTrue(a.matches("[0-9a-f]{64}"), "identity is a 64-char hex SHA-256, was: " + a);

        // Same install (persisted keystore) → same identity across a restart, so a peer's pin is stable.
        TLSHelper.resetForTest();
        TLSHelper.init(dirA.toFile());
        assertEquals(a, TLSHelper.getLocalFingerprint(),
                "a reloaded keystore must yield the same pinning identity across restarts");

        // A separate install has its own keypair, hence its own identity.
        TLSHelper.resetForTest();
        TLSHelper.init(dirB.toFile());
        assertNotEquals(a, TLSHelper.getLocalFingerprint(),
                "a different install must have a different key fingerprint");
    }

    @Test
    void pinnedClientAcceptsTheServersAdvertisedKey(@TempDir Path dir) throws Exception {
        TLSHelper.init(dir.toFile());
        String fingerprint = TLSHelper.getLocalFingerprint(); // the server here IS our own identity

        try (SSLServerSocket server = TLSHelper.createServerSocket(0)) {
            int port = server.getLocalPort();
            byte[] payload = "pinned-ok".getBytes();
            byte[] echoed = new byte[payload.length];

            Thread serverThread = new Thread(() -> {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    byte[] buf = new DataInputStream(s.getInputStream()).readNBytes(payload.length);
                    s.getOutputStream().write(buf);
                    s.getOutputStream().flush();
                } catch (Exception ignored) {}
            });
            serverThread.start();

            // Pinning the correct fingerprint must complete the handshake AND leave the data path intact.
            try (SSLSocket client = TLSHelper.createClientSocket("127.0.0.1", port, fingerprint)) {
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                out.write(payload);
                out.flush();
                new DataInputStream(client.getInputStream()).readFully(echoed);
            }
            serverThread.join(5000);
            assertArrayEquals(payload, echoed, "pinning the right key must not disturb the round-trip");
        }
    }

    @Test
    void pinnedClientRejectsAPeerPresentingADifferentKey(@TempDir Path dir) throws Exception {
        TLSHelper.init(dir.toFile());
        String expected = TLSHelper.getLocalFingerprint(); // the identity we INTEND to reach

        // An impostor serves a wholly different keypair. The trust-all transport would happily finish the
        // handshake — only the post-handshake pin check stops it (this is the MITM the doc names in T0.5).
        try (SSLServerSocket impostor = impostorServerSocket()) {
            int port = impostor.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (SSLSocket s = (SSLSocket) impostor.accept()) {
                    s.getInputStream().read(); // drive the handshake to completion; ignore the ensuing close
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // Without the pin check this call would return a connected socket; with it, it must throw.
            assertThrows(SSLPeerUnverifiedException.class,
                    () -> TLSHelper.createClientSocket("127.0.0.1", port, expected),
                    "a peer whose key fingerprint differs from the pin must be rejected");
            serverThread.join(5000);
        }
    }

    /** A TLS server backed by a fresh, unrelated keypair — i.e. a peer/MITM presenting the "wrong" cert. */
    private static SSLServerSocket impostorServerSocket() throws Exception {
        KeyStore ks = SelfSignedCert.generateKeyStore("p2pshare", PASS);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        TrustManager[] trustAll = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trustAll, new SecureRandom());
        return (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0);
    }

    private static X509Certificate loadCert(File keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(keystore.toPath())) {
            ks.load(is, PASS);
        }
        return (X509Certificate) ks.getCertificate("p2pshare");
    }
}
