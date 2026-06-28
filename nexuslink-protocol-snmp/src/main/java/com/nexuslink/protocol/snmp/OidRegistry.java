package com.nexuslink.protocol.snmp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small bundled registry that resolves numeric SNMP object identifiers to symbolic MIB names and
 * back. It ships the SNMPv2-MIB {@code system} group and the {@code interfaces} / {@code ifTable}
 * basics so the browser can show {@code sysDescr.0} instead of {@code 1.3.6.1.2.1.1.1.0}.
 *
 * <p>Everything here is pure and side-effect free, so it is fully unit-testable offline (no live
 * agent). {@link #nameFor} does a longest-prefix match and keeps the trailing instance suffix
 * ({@code 1.3.6.1.2.1.1.1.0} ⇒ {@code sysDescr.0}); an unknown OID is returned as its plain dotted
 * form. {@link #oidFor} is the inverse and understands an instance suffix ({@code sysName.0} ⇒
 * {@code 1.3.6.1.2.1.1.5.0}).
 */
public final class OidRegistry {

    /** Numeric OID ⇒ symbolic name. Insertion order is the natural OID order for stable listings. */
    private static final Map<String, String> OID_TO_NAME = new LinkedHashMap<>();
    /** Symbolic name ⇒ numeric OID (the inverse of {@link #OID_TO_NAME}). */
    private static final Map<String, String> NAME_TO_OID = new LinkedHashMap<>();

    static {
        // Branch labels — useful as longest-prefix fallbacks for OIDs we do not name individually.
        register("1.3.6.1", "internet");
        register("1.3.6.1.2.1", "mib-2");

        // SNMPv2-MIB system group (1.3.6.1.2.1.1).
        register("1.3.6.1.2.1.1", "system");
        register("1.3.6.1.2.1.1.1", "sysDescr");
        register("1.3.6.1.2.1.1.2", "sysObjectID");
        register("1.3.6.1.2.1.1.3", "sysUpTime");
        register("1.3.6.1.2.1.1.4", "sysContact");
        register("1.3.6.1.2.1.1.5", "sysName");
        register("1.3.6.1.2.1.1.6", "sysLocation");
        register("1.3.6.1.2.1.1.7", "sysServices");

        // interfaces group (1.3.6.1.2.1.2).
        register("1.3.6.1.2.1.2", "interfaces");
        register("1.3.6.1.2.1.2.1", "ifNumber");
        register("1.3.6.1.2.1.2.2", "ifTable");
        register("1.3.6.1.2.1.2.2.1", "ifEntry");
        register("1.3.6.1.2.1.2.2.1.1", "ifIndex");
        register("1.3.6.1.2.1.2.2.1.2", "ifDescr");
        register("1.3.6.1.2.1.2.2.1.3", "ifType");
        register("1.3.6.1.2.1.2.2.1.4", "ifMtu");
        register("1.3.6.1.2.1.2.2.1.5", "ifSpeed");
        register("1.3.6.1.2.1.2.2.1.6", "ifPhysAddress");
        register("1.3.6.1.2.1.2.2.1.7", "ifAdminStatus");
        register("1.3.6.1.2.1.2.2.1.8", "ifOperStatus");
        register("1.3.6.1.2.1.2.2.1.9", "ifLastChange");
        register("1.3.6.1.2.1.2.2.1.10", "ifInOctets");
        register("1.3.6.1.2.1.2.2.1.11", "ifInUcastPkts");
        register("1.3.6.1.2.1.2.2.1.13", "ifInDiscards");
        register("1.3.6.1.2.1.2.2.1.14", "ifInErrors");
        register("1.3.6.1.2.1.2.2.1.16", "ifOutOctets");
        register("1.3.6.1.2.1.2.2.1.17", "ifOutUcastPkts");
        register("1.3.6.1.2.1.2.2.1.19", "ifOutDiscards");
        register("1.3.6.1.2.1.2.2.1.20", "ifOutErrors");
    }

    private OidRegistry() {}

    private static void register(String oid, String name) {
        OID_TO_NAME.put(oid, name);
        NAME_TO_OID.put(name, oid);
    }

    /**
     * Resolves {@code oid} to a symbolic name using a longest-prefix match, appending any trailing
     * instance components (e.g. {@code 1.3.6.1.2.1.1.1.0} ⇒ {@code sysDescr.0}). An OID with no known
     * prefix is returned as its plain dotted form (without a leading dot); {@code null}/blank input
     * yields {@code null}.
     */
    public static String nameFor(String oid) {
        if (oid == null) return null;
        String norm = oid.startsWith(".") ? oid.substring(1) : oid;
        if (norm.isBlank()) return null;
        String[] comps = norm.split("\\.");

        String bestName = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : OID_TO_NAME.entrySet()) {
            String[] prefix = e.getKey().split("\\.");
            if (prefix.length > comps.length || prefix.length <= bestLen) continue;
            if (isPrefix(prefix, comps)) {
                bestName = e.getValue();
                bestLen = prefix.length;
            }
        }
        if (bestName == null) return norm;   // unknown OID ⇒ numeric fallback

        StringBuilder suffix = new StringBuilder();
        for (int i = bestLen; i < comps.length; i++) {
            suffix.append(suffix.length() == 0 ? "" : ".").append(comps[i]);
        }
        return suffix.length() == 0 ? bestName : bestName + "." + suffix;
    }

    /**
     * Resolves a symbolic name back to its numeric OID, honouring an instance suffix
     * ({@code sysName.0} ⇒ {@code 1.3.6.1.2.1.1.5.0}, {@code ifDescr.2} ⇒
     * {@code 1.3.6.1.2.1.2.2.1.2.2}). Returns empty when the symbolic part is unknown.
     */
    public static Optional<String> oidFor(String name) {
        if (name == null) return Optional.empty();
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return Optional.empty();

        int dot = trimmed.indexOf('.');
        String base = dot < 0 ? trimmed : trimmed.substring(0, dot);
        String suffix = dot < 0 ? "" : trimmed.substring(dot + 1);

        String oid = NAME_TO_OID.get(base);
        if (oid == null) return Optional.empty();
        return Optional.of(suffix.isEmpty() ? oid : oid + "." + suffix);
    }

    /** True when {@code oid} (or one of its prefixes) is a registered, named object. */
    public static boolean isKnown(String oid) {
        if (oid == null) return false;
        String norm = oid.startsWith(".") ? oid.substring(1) : oid;
        String name = nameFor(oid);
        // Unknown OIDs fall back to their own numeric form; a real name never equals that.
        return name != null && !name.equals(norm);
    }

    /** The registered symbolic names, in OID order — handy for an autocomplete / picker in the UI. */
    public static List<String> knownNames() {
        return new ArrayList<>(NAME_TO_OID.keySet());
    }

    /** An immutable snapshot of the bundled name ⇒ OID table, in OID order. */
    public static Map<String, String> table() {
        return Map.copyOf(NAME_TO_OID);
    }

    private static boolean isPrefix(String[] prefix, String[] full) {
        for (int i = 0; i < prefix.length; i++) {
            if (!prefix[i].equals(full[i])) return false;
        }
        return true;
    }
}
