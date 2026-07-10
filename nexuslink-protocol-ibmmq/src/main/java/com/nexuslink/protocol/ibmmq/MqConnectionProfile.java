package com.nexuslink.protocol.ibmmq;

/**
 * Everything needed to open a client-mode ({@code CLIENT} binding) connection to an IBM MQ queue
 * manager: where it lives, which server-connection channel to ride, who we are, and how the channel
 * is protected.
 *
 * <p>Two protection layers are modelled and they are independent:</p>
 * <ul>
 *   <li><b>TLS</b> — encrypts the <em>channel</em>. Requires {@code cipherSuite} to name the same
 *       CipherSpec the SVRCONN channel is defined with, and a JSSE trust store holding the queue
 *       manager's certificate.</li>
 *   <li><b>AMS</b> (Advanced Message Security) — encrypts/signs the <em>message payload</em>
 *       end-to-end, independent of the channel. Requires a {@code keystore.conf} pointing at a key
 *       repository, and message-protection policies defined on the queue manager itself
 *       ({@code setmqspl}). The client only supplies the keystore; if the queue manager has no
 *       policy for a queue, AMS is a no-op for it.</li>
 * </ul>
 *
 * <p>Use {@link #plain} for the common dev case and {@code with*} to layer security on.</p>
 *
 * @param queueManager  queue manager name, e.g. {@code QM1}
 * @param channel       server-connection channel, e.g. {@code DEV.APP.SVRCONN}
 * @param host          queue manager host
 * @param port          listener port, conventionally 1414
 * @param user          MQ user id, may be {@code null}/blank for no authentication
 * @param password      password for {@code user}
 * @param tls           whether to negotiate TLS on the channel
 * @param cipherSuite   JSSE CipherSuite matching the channel's CipherSpec; required when {@code tls}
 * @param trustStore    path to a JKS/PKCS12 trust store holding the QM certificate; may be {@code null}
 *                      to use the JVM default
 * @param trustStorePassword password for {@code trustStore}
 * @param ams           whether to enable Advanced Message Security interception
 * @param amsKeystoreConf path to the AMS {@code keystore.conf}; required when {@code ams}
 */
public record MqConnectionProfile(
        String queueManager,
        String channel,
        String host,
        int port,
        String user,
        String password,
        boolean tls,
        String cipherSuite,
        String trustStore,
        String trustStorePassword,
        boolean ams,
        String amsKeystoreConf) {

    /** Conventional IBM MQ listener port. */
    public static final int DEFAULT_PORT = 1414;

    public MqConnectionProfile {
        if (queueManager == null || queueManager.isBlank()) {
            throw new IllegalArgumentException("queueManager is required");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel is required");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (tls && (cipherSuite == null || cipherSuite.isBlank())) {
            throw new IllegalArgumentException("cipherSuite is required when tls is enabled");
        }
        if (ams && (amsKeystoreConf == null || amsKeystoreConf.isBlank())) {
            throw new IllegalArgumentException("amsKeystoreConf is required when ams is enabled");
        }
    }

    /** An unencrypted connection — the usual shape for a local/dev queue manager. */
    public static MqConnectionProfile plain(String queueManager, String channel, String host, int port,
                                            String user, String password) {
        return new MqConnectionProfile(queueManager, channel, host, port, user, password,
                false, null, null, null, false, null);
    }

    /** This profile with TLS enabled on the channel. */
    public MqConnectionProfile withTls(String cipherSuite, String trustStore, String trustStorePassword) {
        return new MqConnectionProfile(queueManager, channel, host, port, user, password,
                true, cipherSuite, trustStore, trustStorePassword, ams, amsKeystoreConf);
    }

    /** This profile with AMS payload protection enabled. */
    public MqConnectionProfile withAms(String amsKeystoreConf) {
        return new MqConnectionProfile(queueManager, channel, host, port, user, password,
                tls, cipherSuite, trustStore, trustStorePassword, true, amsKeystoreConf);
    }

    /** True when a user id was supplied, i.e. the channel expects authentication. */
    public boolean hasCredentials() {
        return user != null && !user.isBlank();
    }

    /** {@code host(port)} — IBM's own connection-name notation, as used by {@code CCDT} and {@code runmqsc}. */
    public String connectionName() {
        return host + "(" + port + ")";
    }

    /** A rendering safe for logs and UI: never contains a password. */
    public String redacted() {
        return "MqConnectionProfile[qmgr=" + queueManager + ", channel=" + channel
                + ", conn=" + connectionName()
                + ", user=" + (hasCredentials() ? user : "<none>")
                + ", tls=" + (tls ? cipherSuite : "off")
                + ", ams=" + (ams ? "on" : "off") + "]";
    }

    @Override
    public String toString() {
        return redacted();
    }
}
