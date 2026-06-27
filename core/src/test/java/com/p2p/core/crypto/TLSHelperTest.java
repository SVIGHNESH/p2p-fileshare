package com.p2p.core.crypto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
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
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static X509Certificate loadCert(File keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(keystore.toPath())) {
            ks.load(is, PASS);
        }
        return (X509Certificate) ks.getCertificate("p2pshare");
    }
}
