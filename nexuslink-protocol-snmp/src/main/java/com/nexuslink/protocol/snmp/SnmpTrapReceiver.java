package com.nexuslink.protocol.snmp;

import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.Snmp;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Listens on a UDP port (default 162) for incoming SNMP v1 / v2c traps and notifications over
 * SNMP4J, decoding each into an immutable {@link Trap} record and delivering it to a registered
 * listener. The transport/dispatcher is community-based (the same single-argument {@link Snmp}
 * constructor the browser uses, which registers the v1/v2c message-processing models).
 *
 * <p>The decoding helpers — {@link #isTrap}, {@link #trapOidOf} and {@link #decode(PDU, String,
 * String, Instant)} — are pure and unit-tested offline by building a PDU in-process; the full
 * {@link #start(int)} ⇒ receive path is exercised against a loopback ephemeral port.
 */
public final class SnmpTrapReceiver implements AutoCloseable {

    /** The IANA-assigned SNMP trap port. */
    public static final int DEFAULT_PORT = 162;

    /** snmpTrapOID.0 — carries the notification OID in v2c/v3 traps. */
    private static final String SNMP_TRAP_OID_0 = "1.3.6.1.6.3.1.1.4.1.0";
    /** snmpTraps — base for the standard generic-v1 trap mappings (RFC 3584 §3.1). */
    private static final String SNMP_TRAPS = "1.3.6.1.6.3.1.1.5";

    /**
     * A decoded SNMP trap / notification. {@code trapOid} is numeric and {@code trapName} is its
     * symbolic MIB resolution (via {@link OidRegistry}); {@code varBinds} reuses the browser's
     * {@link SnmpService.VarBind} decode (OID, SMI type, value) and is an immutable copy.
     */
    public record Trap(
            Instant timestamp,
            String source,
            String version,
            String community,
            String trapOid,
            String trapName,
            List<SnmpService.VarBind> varBinds) {

        public Trap {
            varBinds = varBinds == null ? List.of() : List.copyOf(varBinds);
        }
    }

    private final Consumer<Trap> listener;
    private volatile Snmp snmp;
    private volatile DefaultUdpTransportMapping transport;

    /** Creates a receiver that delivers each decoded trap to {@code listener} (may be {@code null}). */
    public SnmpTrapReceiver(Consumer<Trap> listener) {
        this.listener = listener == null ? t -> {} : listener;
    }

    /**
     * Binds to {@code 0.0.0.0:port} (port {@code <= 0} ⇒ {@link #DEFAULT_PORT}; pass {@code 0} for an
     * OS-chosen ephemeral port) and starts listening. Any previous session is stopped first.
     */
    public synchronized void start(int port) throws IOException {
        stop();
        int p = port > 0 ? port : (port == 0 ? 0 : DEFAULT_PORT);
        DefaultUdpTransportMapping tm = new DefaultUdpTransportMapping(new UdpAddress("0.0.0.0/" + p));
        Snmp s = new Snmp(tm);
        s.addCommandResponder(new CommandResponder() {
            @Override
            public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
                Trap trap = decode(event);
                if (trap != null) {
                    event.setProcessed(true);
                    listener.accept(trap);
                }
            }
        });
        s.listen();
        this.transport = tm;
        this.snmp = s;
    }

    public boolean isListening() {
        return snmp != null;
    }

    /** The UDP port actually bound (resolves an ephemeral {@code 0} to its assigned port), or -1. */
    public int boundPort() {
        DefaultUdpTransportMapping tm = transport;
        if (tm == null) return -1;
        UdpAddress addr = (UdpAddress) tm.getListenAddress();
        return addr == null ? -1 : addr.getPort();
    }

    /** Stops listening and releases the socket. Safe to call when not started. */
    public synchronized void stop() {
        Snmp s = snmp;
        snmp = null;
        transport = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { /* best-effort */ }
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ---- decoding (pure helpers, unit-tested) ----

    /**
     * Decodes a received command-responder event into a {@link Trap}, or {@code null} when the PDU is
     * not a trap / notification. The community is read from the v1/v2c security name.
     */
    public static Trap decode(CommandResponderEvent<?> event) {
        PDU pdu = event.getPDU();
        if (pdu == null || !isTrap(pdu.getType())) return null;
        String source = event.getPeerAddress() == null ? "?" : event.getPeerAddress().toString();
        byte[] name = event.getSecurityName();
        String community = name == null ? "" : new String(name, StandardCharsets.UTF_8);
        return decode(pdu, source, community, Instant.now());
    }

    /** Builds a {@link Trap} from an already-decoded PDU — the offline-testable core of {@link #decode}. */
    public static Trap decode(PDU pdu, String source, String community, Instant timestamp) {
        String version = pdu.getType() == PDU.V1TRAP ? "v1" : "v2c";
        String trapOid = trapOidOf(pdu);
        List<SnmpService.VarBind> binds = new ArrayList<>();
        for (VariableBinding vb : pdu.getVariableBindings()) {
            binds.add(SnmpService.toVarBind(vb));
        }
        return new Trap(timestamp, source, version, community, trapOid,
                OidRegistry.nameFor(trapOid), binds);
    }

    /** True for the trap / notification / inform PDU types. */
    public static boolean isTrap(int pduType) {
        return pduType == PDU.V1TRAP || pduType == PDU.TRAP
                || pduType == PDU.NOTIFICATION || pduType == PDU.INFORM;
    }

    /**
     * Extracts the notification OID: for v2c/v3 it is the value of {@code snmpTrapOID.0}; for v1 it is
     * derived from the generic / specific trap numbers and the enterprise OID per RFC 3584 §3.1
     * (generic ⇒ {@code 1.3.6.1.6.3.1.1.5.(generic+1)}; enterprise-specific ⇒
     * {@code <enterprise>.0.<specific>}). Returns {@code ""} when no notification OID is present.
     */
    public static String trapOidOf(PDU pdu) {
        if (pdu instanceof PDUv1 v1) {
            int generic = v1.getGenericTrap();
            if (generic == PDUv1.ENTERPRISE_SPECIFIC) {
                OID enterprise = v1.getEnterprise();
                String base = enterprise == null ? "" : enterprise.toDottedString();
                return base + ".0." + v1.getSpecificTrap();
            }
            return SNMP_TRAPS + "." + (generic + 1);
        }
        for (VariableBinding vb : pdu.getVariableBindings()) {
            if (SNMP_TRAP_OID_0.equals(vb.getOid().toDottedString())) {
                return vb.getVariable().toString();
            }
        }
        return "";
    }
}
