package dev.yoonpay.provider.support;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a TLS context for mutual TLS from PEM, as providers hand certificates out. */
public final class Tls {

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private Tls() {
    }

    /**
     * @param keyPem   the client's private key, PKCS#8 ({@code BEGIN PRIVATE KEY}), RSA or EC
     * @param certPem  the client certificate, optionally followed by its chain
     * @param caPem    certificates to trust for the server, or null for the JDK's default trust store
     */
    public static SSLContext fromPem(String keyPem, String certPem, String caPem) {
        try {
            List<Certificate> chain = certificates(certPem);
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("no certificate in the client certificate PEM");
            }
            PrivateKey key = privateKey(keyPem);
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            char[] none = new char[0];
            keys.setKeyEntry("client", key, none, chain.toArray(Certificate[]::new));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, none);

            TrustManagerFactory tmf = null;
            if (caPem != null && !caPem.isBlank()) {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                int i = 0;
                for (Certificate c : certificates(caPem)) {
                    trust.setCertificateEntry("ca" + i++, c);
                }
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trust);
            }
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
            return ssl;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot build the TLS context: " + e.getClass().getSimpleName(), e);
        }
    }

    /** A credential given either as PEM text or as the path of a PEM file. */
    public static String pemOrFile(String value) {
        if (value == null || value.isBlank() || value.contains("-----BEGIN")) {
            return value;
        }
        try {
            return Files.readString(Path.of(value.trim()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read PEM file " + value.trim(), e);
        }
    }

    private static List<Certificate> certificates(String pem) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> out = new ArrayList<>();
        for (byte[] der : blocks(pem, "CERTIFICATE")) {
            Collection<? extends Certificate> cs = cf.generateCertificates(new ByteArrayInputStream(der));
            for (Certificate c : cs) {
                out.add((X509Certificate) c);
            }
        }
        return out;
    }

    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        List<byte[]> ders = blocks(pem, "PRIVATE KEY");
        if (ders.isEmpty()) {
            throw new IllegalArgumentException("the private key must be PKCS#8 PEM (BEGIN PRIVATE KEY)");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(ders.getFirst());
        for (String alg : List.of("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (GeneralSecurityException ignored) {
                // try the next algorithm
            }
        }
        throw new IllegalArgumentException("unsupported private key algorithm");
    }

    private static List<byte[]> blocks(String pem, String type) {
        List<byte[]> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(pem == null ? "" : pem);
        while (m.find()) {
            if (m.group(1).equals(type)) {
                out.add(Base64.getMimeDecoder().decode(m.group(2)));
            }
        }
        return out;
    }
}
