package com.nexuslink.protocol.solace;

import java.util.List;

/**
 * Coordinates for a Solace PubSub+ event broker connection over the SMF wire (JCSMP): which
 * Message VPN to enter, which host(s) to reach the broker on, and who to authenticate as.
 *
 * <p>Solace supports a <em>host list</em> — several {@code host:port} entries the client tries in
 * order — for HA redundancy, so {@link #hosts} is a list rather than a single string. JCSMP wants
 * them comma-joined, which {@link #hostList()} produces.</p>
 *
 * @param hosts    ordered broker hosts, each {@code host} or {@code host:port} or a {@code tcp(s)://}
 *                 URL; the first that answers wins. At least one is required.
 * @param vpn      the Message VPN name, e.g. {@code default}
 * @param username client username on that VPN
 * @param password password for {@code username}; may be blank when the VPN allows it
 */
public record SolaceConnectionProfile(List<String> hosts, String vpn, String username, String password) {

    /** The SMF plaintext port; {@code tcps://} TLS connections conventionally use 55443. */
    public static final int DEFAULT_SMF_PORT = 55555;

    public SolaceConnectionProfile {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("at least one host is required");
        }
        hosts = List.copyOf(hosts);
        for (String host : hosts) {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host entries must be non-blank");
            }
        }
        if (vpn == null || vpn.isBlank()) {
            throw new IllegalArgumentException("vpn is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
    }

    /** A single-host profile — the common case. */
    public static SolaceConnectionProfile single(String host, String vpn, String username, String password) {
        return new SolaceConnectionProfile(List.of(host), vpn, username, password);
    }

    /** The hosts joined the way JCSMP's {@code HOST} property wants them: comma-separated. */
    public String hostList() {
        return String.join(",", hosts);
    }

    /** A rendering safe for logs and UI: never contains the password. */
    public String redacted() {
        return "SolaceConnectionProfile[hosts=" + hosts + ", vpn=" + vpn + ", user=" + username + "]";
    }

    @Override
    public String toString() {
        return redacted();
    }
}
