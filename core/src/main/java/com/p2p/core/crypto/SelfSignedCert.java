package com.p2p.core.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Generates a self-signed X.509 certificate and an RSA key pair, then wraps them in a
 * PKCS#12 {@link KeyStore}, using only portable, public JDK + Android APIs.
 *
 * <p>There is no public API on either the JDK or Android to <em>build</em> an
 * {@link X509Certificate} ({@code CertificateFactory} only parses). The usual recipes
 * reach for {@code sun.security.x509.*} (absent on Android), {@code keytool} (absent on
 * Android), or BouncyCastle (an extra dependency). This class instead hand-encodes the
 * certificate's DER, which works identically on desktop and Android (minSdk 26), keeping
 * one code path for both platforms.
 *
 * <p>The only structural shortcut taken is that {@link PublicKey#getEncoded()} already
 * returns the X.509 {@code SubjectPublicKeyInfo} DER, so it is dropped into the
 * TBSCertificate verbatim.
 */
final class SelfSignedCert {

    private SelfSignedCert() {}

    /** RSA over EC: the PKCS#1 signature is fixed-format, which keeps the hand-rolled DER simple. */
    private static final int KEY_BITS = 2048;
    /** sha256WithRSAEncryption: 1.2.840.113549.1.1.11 */
    private static final int[] OID_SHA256_RSA = {1, 2, 840, 113549, 1, 1, 11};
    /** id-at-commonName: 2.5.4.3 */
    private static final int[] OID_COMMON_NAME = {2, 5, 4, 3};
    private static final long VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000; // ~10 years

    /**
     * Builds a fresh PKCS#12 keystore containing one RSA key pair under {@code alias},
     * protected with {@code password}, certified by a self-signed certificate.
     */
    static KeyStore generateKeyStore(String alias, char[] password) throws GeneralSecurityException, IOException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(KEY_BITS, new SecureRandom());
        KeyPair keyPair = kpg.generateKeyPair();

        X509Certificate cert = generateCertificate("CN=P2PShare", keyPair);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, keyPair.getPrivate(), password, new Certificate[]{cert});
        return ks;
    }

    /** Builds and parses a self-signed certificate, verifying its own signature before returning. */
    static X509Certificate generateCertificate(String distinguishedName, KeyPair keyPair)
            throws GeneralSecurityException {
        Date notBefore = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000); // 1d skew slack
        Date notAfter = new Date(notBefore.getTime() + VALIDITY_MILLIS);
        String cn = distinguishedName.startsWith("CN=") ? distinguishedName.substring(3) : distinguishedName;

        byte[] algId = algorithmIdentifier();
        byte[] name = name(cn);
        // X.509 v1 (the DEFAULT, so the version field is omitted): with no extensions to carry,
        // v1 is the maximally-compatible shape across TLS stacks (JDK, Android Conscrypt/BoringSSL).
        byte[] tbs = seq(
                integer(BigInteger.valueOf(System.nanoTime() & 0x7fffffffffffffffL)), // serialNumber (positive)
                algId,                                 // signature alg
                name,                                  // issuer
                seq(time(notBefore), time(notAfter)), // validity
                name,                                  // subject (== issuer for self-signed)
                keyPair.getPublic().getEncoded());     // SubjectPublicKeyInfo, already DER

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        byte[] certDer = seq(tbs, algId, bitString(signature));

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
        // Localizes "DER is malformed" / "self-signature is wrong" here, before any TLS handshake.
        cert.verify(keyPair.getPublic());
        cert.checkValidity();
        return cert;
    }

    // ---- minimal DER encoding ----------------------------------------------------------------

    private static byte[] seq(byte[]... items) {
        return der(0x30, concat(items));
    }

    private static byte[] integer(BigInteger value) {
        // BigInteger.toByteArray() already yields minimal two's-complement big-endian bytes,
        // including a leading 0x00 when the high bit would otherwise make it look negative.
        return der(0x02, value.toByteArray());
    }

    private static byte[] bitString(byte[] bytes) {
        byte[] withUnusedBits = new byte[bytes.length + 1];
        withUnusedBits[0] = 0; // 0 unused bits
        System.arraycopy(bytes, 0, withUnusedBits, 1, bytes.length);
        return der(0x03, withUnusedBits);
    }

    /** AlgorithmIdentifier for sha256WithRSAEncryption: SEQUENCE { OID, NULL }. */
    private static byte[] algorithmIdentifier() {
        return seq(oid(OID_SHA256_RSA), der(0x05, new byte[0]));
    }

    /** A minimal X.500 Name carrying a single CN attribute. */
    private static byte[] name(String cn) {
        byte[] attribute = seq(oid(OID_COMMON_NAME), der(0x0C, cn.getBytes(StandardCharsets.UTF_8))); // UTF8String
        return seq(der(0x31, attribute)); // SEQUENCE OF (SET OF AttributeTypeAndValue)
    }

    private static byte[] time(Date date) {
        ZonedDateTime z = date.toInstant().atZone(ZoneOffset.UTC);
        if (z.getYear() < 2050) {
            // RFC 5280: UTCTime ("YYMMDDHHMMSSZ") for years through 2049.
            String s = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(z);
            return der(0x17, s.getBytes(StandardCharsets.US_ASCII));
        }
        // GeneralizedTime ("YYYYMMDDHHMMSSZ") for 2050 and later.
        String s = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").format(z);
        return der(0x18, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(arcs[0] * 40 + arcs[1]); // first two arcs share one byte
        for (int i = 2; i < arcs.length; i++) writeBase128(content, arcs[i]);
        return der(0x06, content.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, int value) {
        int shift = 28; // ints fit in five 7-bit groups
        while (shift > 0 && (value >>> shift) == 0) shift -= 7;
        for (; shift > 0; shift -= 7) out.write(0x80 | ((value >>> shift) & 0x7F));
        out.write(value & 0x7F);
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length); // short form
            return;
        }
        // Long form: 0x80 | number-of-length-bytes, then the big-endian length.
        byte[] lenBytes = BigInteger.valueOf(length).toByteArray();
        int start = (lenBytes.length > 1 && lenBytes[0] == 0) ? 1 : 0; // strip sign padding
        int n = lenBytes.length - start;
        out.write(0x80 | n);
        out.write(lenBytes, start, n);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
