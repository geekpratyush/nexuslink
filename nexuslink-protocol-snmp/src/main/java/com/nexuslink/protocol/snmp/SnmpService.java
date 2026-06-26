package com.nexuslink.protocol.snmp;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SNMP browser client over SNMP4J — community-based v1 / v2c GET and WALK (GETNEXT) over UDP.
 * Returns decoded variable bindings (OID, SMI type, value) for display. Blocking I/O; the UI drives
 * it on a background task.
 *
 * <p>The pure helpers — {@link #versionOf}, {@link #normalizeAddress}, {@link #isValidOid} and
 * {@link #toVarBind} — are unit-tested offline; live GET/WALK needs a reachable SNMP agent.
 * (SNMPv3 / USM is a roadmap item.)
 */
public final class SnmpService implements AutoCloseable {

    /** A decoded SNMP variable binding. */
    public record VarBind(String oid, String type, String value) {}

    private volatile Snmp snmp;
    private volatile CommunityTarget<UdpAddress> target;

    /**
     * Opens an SNMP session to {@code host:port} using {@code community} and an SNMP {@code version}
     * ({@code "1"}, {@code "2c"}). Default port is 161; default community {@code public}.
     */
    public void open(String host, int port, String community, String version) throws IOException {
        close();
        Snmp s = new Snmp(new DefaultUdpTransportMapping());
        s.listen();

        CommunityTarget<UdpAddress> t = new CommunityTarget<>();
        t.setCommunity(new OctetString(community == null || community.isBlank() ? "public" : community));
        t.setAddress((UdpAddress) GenericAddress.parse(normalizeAddress(host, port)));
        t.setVersion(versionOf(version));
        t.setRetries(1);
        t.setTimeout(3_000);

        this.snmp = s;
        this.target = t;
    }

    public boolean isOpen() {
        return snmp != null && target != null;
    }

    /** Performs an SNMP GET for one or more OIDs and returns the decoded bindings. */
    public List<VarBind> get(String... oids) throws IOException {
        PDU pdu = new PDU();
        pdu.setType(PDU.GET);
        for (String oid : oids) pdu.add(new VariableBinding(new OID(oid)));

        ResponseEvent<UdpAddress> event = require().get(pdu, target);
        PDU response = event == null ? null : event.getResponse();
        if (response == null) throw new IOException("No response from agent (timeout) for " + String.join(", ", oids));
        if (response.getErrorStatus() != PDU.noError) {
            throw new IOException("SNMP error: " + response.getErrorStatusText());
        }
        List<VarBind> out = new ArrayList<>();
        for (VariableBinding vb : response.getVariableBindings()) out.add(toVarBind(vb));
        return out;
    }

    /**
     * Walks the subtree under {@code rootOid} with repeated GETNEXT, stopping at the end of the
     * subtree / MIB or when {@code maxRows} is reached (0 ⇒ a 10 000-row safety cap).
     */
    public List<VarBind> walk(String rootOid, int maxRows) throws IOException {
        OID root = new OID(rootOid);
        OID current = root;
        int cap = maxRows > 0 ? maxRows : 10_000;
        List<VarBind> out = new ArrayList<>();

        while (out.size() < cap) {
            PDU pdu = new PDU();
            pdu.setType(PDU.GETNEXT);
            pdu.add(new VariableBinding(current));

            ResponseEvent<UdpAddress> event = require().getNext(pdu, target);
            PDU response = event == null ? null : event.getResponse();
            if (response == null) throw new IOException("No response from agent (timeout) during walk");
            if (response.getErrorStatus() != PDU.noError) {
                throw new IOException("SNMP error: " + response.getErrorStatusText());
            }
            VariableBinding vb = response.get(0);
            OID next = vb.getOid();
            // Stop at end-of-MIB / noSuchObject / noSuchInstance, or once we leave the subtree.
            if (Null.isExceptionSyntax(vb.getVariable().getSyntax())) break;
            if (!next.startsWith(root)) break;
            if (next.compareTo(current) <= 0) break;   // guard against non-advancing agents
            out.add(toVarBind(vb));
            current = next;
        }
        return out;
    }

    // ---- pure helpers (unit-tested) ----

    /** Maps a textual SNMP version to the SNMP4J constant; defaults to v2c. */
    public static int versionOf(String version) {
        if (version == null) return SnmpConstants.version2c;
        return switch (version.trim().toLowerCase(Locale.ROOT)) {
            case "1", "v1" -> SnmpConstants.version1;
            case "3", "v3" -> SnmpConstants.version3;
            default -> SnmpConstants.version2c;   // "2", "2c", "v2c", anything else
        };
    }

    /** Builds the SNMP4J UDP address string {@code udp:host/port} (default port 161). */
    public static String normalizeAddress(String host, int port) {
        String h = (host == null || host.isBlank()) ? "127.0.0.1" : host.trim();
        int p = port > 0 ? port : 161;
        return "udp:" + h + "/" + p;
    }

    /** True if {@code oid} is a well-formed numeric object identifier. */
    public static boolean isValidOid(String oid) {
        if (oid == null) return false;
        String s = oid.startsWith(".") ? oid.substring(1) : oid;
        if (s.isBlank()) return false;
        for (String part : s.split("\\.")) {
            if (part.isEmpty()) return false;
            try { if (Long.parseLong(part) < 0) return false; }
            catch (NumberFormatException e) { return false; }
        }
        return true;
    }

    /** Decodes an SNMP4J {@link VariableBinding} into a display record. */
    public static VarBind toVarBind(VariableBinding vb) {
        return new VarBind(
                vb.getOid().toDottedString(),
                vb.getVariable().getSyntaxString(),
                vb.getVariable().toString());
    }

    private Snmp require() {
        Snmp s = snmp;
        if (s == null || target == null) throw new IllegalStateException("SNMP session is not open");
        return s;
    }

    @Override
    public void close() {
        Snmp s = snmp;
        snmp = null;
        target = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { /* best-effort */ }
        }
    }
}
