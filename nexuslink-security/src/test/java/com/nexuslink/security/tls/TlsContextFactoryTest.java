package com.nexuslink.security.tls;

import com.nexuslink.security.cert.CertificateExporter;
import com.nexuslink.security.cert.CertificateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link TlsContextFactory} with real loopback TLS and mutual-TLS handshakes built from
 * generated keystores — so the key-manager / trust-manager wiring is verified with no live server.
 */
class TlsContextFactoryTest {

    private static final char[] PW = "changeit".toCharArray();

    private record Identity(X509Certificate cert, java.security.PrivateKey key) {}

    private static Identity newIdentity(String cn) throws Exception {
        var gen = CertificateGenerator.generate(cn, "Test", CertificateGenerator.KeyType.RSA_2048,
                Duration.ofDays(1), List.of("DNS:" + cn));
        return new Identity(gen.certificate(), gen.privateKey());
    }

    private static Path keyStore(Path dir, String name, Identity id) throws Exception {
        Path p = dir.resolve(name + ".p12");
        Files.write(p, CertificateExporter.toPkcs12(name, id.key(), PW, List.of(id.cert())));
        return p;
    }

    private static Path trustStore(Path dir, String name, X509Certificate cert) throws Exception {
        Path p = dir.resolve(name + "-trust.p12");
        Files.write(p, CertificateExporter.toPkcs12TrustStore(name, PW, List.of(cert)));
        return p;
    }

    @Test
    void systemDefaultWhenNothingConfigured() throws Exception {
        assertNotNull(TlsContextFactory.create(TlsConfig.systemDefault()));
        assertNotNull(TlsContextFactory.create(null));
    }

    @Test
    void typeForAutodetectsJksVsPkcs12() {
        assertEquals("JKS", TlsContextFactory.typeFor(null, "store.jks"));
        assertEquals("PKCS12", TlsContextFactory.typeFor(null, "store.p12"));
        assertEquals("JCEKS", TlsContextFactory.typeFor("jceks", "store.p12"));
    }

    @Test
    void serverTrustStoreHandshakeSucceeds(@TempDir Path dir) throws Exception {
        Identity server = newIdentity("localhost");
        Path serverKs = keyStore(dir, "server", server);
        Path clientTs = trustStore(dir, "client", server.cert());

        SSLContext serverCtx = TlsContextFactory.create(new TlsConfig(
                null, null, null, serverKs.toString(), PW, "PKCS12", false));
        SSLContext clientCtx = TlsContextFactory.create(new TlsConfig(
                clientTs.toString(), PW, "PKCS12", null, null, null, false));

        assertDoesNotThrow(() -> handshake(serverCtx, clientCtx, false));
    }

    @Test
    void trustAllAcceptsAnUntrustedSelfSignedServer(@TempDir Path dir) throws Exception {
        Identity server = newIdentity("localhost");
        Path serverKs = keyStore(dir, "server", server);

        SSLContext serverCtx = TlsContextFactory.create(new TlsConfig(
                null, null, null, serverKs.toString(), PW, "PKCS12", false));
        SSLContext clientCtx = TlsContextFactory.create(TlsConfig.trustingAll());

        assertDoesNotThrow(() -> handshake(serverCtx, clientCtx, false));
    }

    @Test
    void untrustedServerIsRejectedWithoutTrustMaterial(@TempDir Path dir) throws Exception {
        Identity server = newIdentity("localhost");
        Path serverKs = keyStore(dir, "server", server);
        // Client trusts an unrelated CA, so the server's cert is not trusted.
        Identity other = newIdentity("other");
        Path clientTs = trustStore(dir, "client", other.cert());

        SSLContext serverCtx = TlsContextFactory.create(new TlsConfig(
                null, null, null, serverKs.toString(), PW, "PKCS12", false));
        SSLContext clientCtx = TlsContextFactory.create(new TlsConfig(
                clientTs.toString(), PW, "PKCS12", null, null, null, false));

        assertThrows(Exception.class, () -> handshake(serverCtx, clientCtx, false));
    }

    @Test
    void mutualTlsHandshakeSucceeds(@TempDir Path dir) throws Exception {
        Identity server = newIdentity("localhost");
        Identity client = newIdentity("client");
        Path serverKs = keyStore(dir, "server", server);
        Path serverTs = trustStore(dir, "server", client.cert());   // server trusts the client cert
        Path clientKs = keyStore(dir, "client", client);
        Path clientTs = trustStore(dir, "client", server.cert());   // client trusts the server cert

        SSLContext serverCtx = TlsContextFactory.create(new TlsConfig(
                serverTs.toString(), PW, "PKCS12", serverKs.toString(), PW, "PKCS12", false));
        SSLContext clientCtx = TlsContextFactory.create(new TlsConfig(
                clientTs.toString(), PW, "PKCS12", clientKs.toString(), PW, "PKCS12", false));

        assertTrue(TlsConfig.systemDefault().isCustom() == false);
        assertDoesNotThrow(() -> handshake(serverCtx, clientCtx, true));
    }

    /** Runs a one-byte echo over a loopback TLS connection; throws if the handshake fails. */
    private static void handshake(SSLContext serverCtx, SSLContext clientCtx, boolean mtls) throws Exception {
        SSLServerSocket server = (SSLServerSocket) serverCtx.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        if (mtls) server.setNeedClientAuth(true);
        int port = server.getLocalPort();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> serverTask = pool.submit(() -> {
            try (SSLSocket s = (SSLSocket) server.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                int b = in.read();
                out.write(b);
                out.flush();
            }
            return null;
        });

        try (SSLSocket client = (SSLSocket) clientCtx.getSocketFactory()
                .createSocket(InetAddress.getLoopbackAddress(), port)) {
            client.setSoTimeout(5000);
            client.getOutputStream().write(123);
            client.getOutputStream().flush();
            int echoed = client.getInputStream().read();
            assertEquals(123, echoed);
        } finally {
            try { serverTask.get(5, TimeUnit.SECONDS); }
            finally { pool.shutdownNow(); server.close(); }
        }
    }
}
