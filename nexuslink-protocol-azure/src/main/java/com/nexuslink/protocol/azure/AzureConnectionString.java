package com.nexuslink.protocol.azure;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, dependency-free parser for an Azure Storage connection string. Azure connection strings are
 * a sequence of {@code Key=Value;} pairs with case-insensitive keys, e.g.
 * <pre>DefaultEndpointsProtocol=https;AccountName=acc;AccountKey=abc123==;EndpointSuffix=core.windows.net</pre>
 *
 * <p>This does no network I/O and pulls in no SDK; it just parses the string and exposes the
 * recognised fields plus the derived service endpoints. It mirrors the well-known shape used by the
 * Azure Storage SDK, including the {@code UseDevelopmentStorage=true} shortcut for the local Azurite
 * emulator.
 */
public final class AzureConnectionString {

    /** Well-known Azurite development storage account name. */
    public static final String DEV_ACCOUNT_NAME = "devstoreaccount1";

    /** Well-known Azurite development storage account key (a fixed, public value). */
    public static final String DEV_ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private static final String DEFAULT_ENDPOINT_SUFFIX = "core.windows.net";

    private final boolean development;
    private final String protocol;
    private final String accountName;
    private final String accountKey;
    private final String endpointSuffix;
    private final String sas;
    private final String blobEndpoint;
    private final String queueEndpoint;
    private final String tableEndpoint;
    private final String fileEndpoint;

    private AzureConnectionString(boolean development, String protocol, String accountName,
                                  String accountKey, String endpointSuffix, String sas,
                                  String blobEndpoint, String queueEndpoint,
                                  String tableEndpoint, String fileEndpoint) {
        this.development = development;
        this.protocol = protocol;
        this.accountName = accountName;
        this.accountKey = accountKey;
        this.endpointSuffix = endpointSuffix;
        this.sas = sas;
        this.blobEndpoint = blobEndpoint;
        this.queueEndpoint = queueEndpoint;
        this.tableEndpoint = tableEndpoint;
        this.fileEndpoint = fileEndpoint;
    }

    /** Raised when a connection string is empty, malformed, or missing required fields. */
    public static final class MalformedConnectionStringException extends IllegalArgumentException {
        public MalformedConnectionStringException(String message) {
            super(message);
        }
    }

    /**
     * Parses an Azure Storage connection string.
     *
     * @throws MalformedConnectionStringException if the input is empty, has a malformed pair,
     *         a bad protocol, or identifies no account (and is neither dev-storage nor SAS-based)
     */
    public static AzureConnectionString parse(String connectionString) {
        if (connectionString == null || connectionString.trim().isEmpty()) {
            throw new MalformedConnectionStringException("Connection string is empty");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String segment : connectionString.split(";")) {
            String pair = segment.trim();
            if (pair.isEmpty()) {
                continue; // tolerate trailing/duplicate separators
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new MalformedConnectionStringException("Malformed pair (missing '='): " + pair);
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty()) {
                throw new MalformedConnectionStringException("Malformed pair (empty key): " + pair);
            }
            // Later occurrences win; keys are matched case-insensitively via the canonical lookup.
            values.put(key.toLowerCase(Locale.ROOT), value);
        }
        if (values.isEmpty()) {
            throw new MalformedConnectionStringException("Connection string has no key/value pairs");
        }

        String useDev = values.get("usedevelopmentstorage");
        if (useDev != null) {
            if (!useDev.equalsIgnoreCase("true")) {
                throw new MalformedConnectionStringException(
                        "UseDevelopmentStorage must be 'true' if present, was: " + useDev);
            }
            return development();
        }

        String protocolRaw = values.get("defaultendpointsprotocol");
        String protocol = "https";
        if (protocolRaw != null && !protocolRaw.isEmpty()) {
            if (!protocolRaw.equalsIgnoreCase("http") && !protocolRaw.equalsIgnoreCase("https")) {
                throw new MalformedConnectionStringException(
                        "DefaultEndpointsProtocol must be 'http' or 'https', was: " + protocolRaw);
            }
            protocol = protocolRaw.toLowerCase(Locale.ROOT);
        }

        String accountName = emptyToNull(values.get("accountname"));
        String accountKey = emptyToNull(values.get("accountkey"));
        String sas = emptyToNull(values.get("sharedaccesssignature"));
        String suffixRaw = emptyToNull(values.get("endpointsuffix"));
        String endpointSuffix = suffixRaw != null ? suffixRaw : DEFAULT_ENDPOINT_SUFFIX;

        String blobEndpoint = emptyToNull(values.get("blobendpoint"));
        String queueEndpoint = emptyToNull(values.get("queueendpoint"));
        String tableEndpoint = emptyToNull(values.get("tableendpoint"));
        String fileEndpoint = emptyToNull(values.get("fileendpoint"));

        boolean hasExplicitEndpoint =
                blobEndpoint != null || queueEndpoint != null
                        || tableEndpoint != null || fileEndpoint != null;
        if (accountName == null && sas == null && !hasExplicitEndpoint) {
            throw new MalformedConnectionStringException(
                    "Connection string must define AccountName, a service endpoint, "
                            + "SharedAccessSignature, or UseDevelopmentStorage");
        }

        return new AzureConnectionString(false, protocol, accountName, accountKey, endpointSuffix,
                sas, blobEndpoint, queueEndpoint, tableEndpoint, fileEndpoint);
    }

    /** The local Azurite emulator configuration (well-known account, key, and blob endpoint). */
    public static AzureConnectionString development() {
        return new AzureConnectionString(true, "http", DEV_ACCOUNT_NAME, DEV_ACCOUNT_KEY,
                DEFAULT_ENDPOINT_SUFFIX, null,
                "http://127.0.0.1:10000/" + DEV_ACCOUNT_NAME,
                "http://127.0.0.1:10001/" + DEV_ACCOUNT_NAME,
                "http://127.0.0.1:10002/" + DEV_ACCOUNT_NAME,
                null);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** {@code true} if this string targets the local Azurite development storage emulator. */
    public boolean isDevelopment() {
        return development;
    }

    /** {@code http} or {@code https}; defaults to {@code https} when unspecified. */
    public String protocol() {
        return protocol;
    }

    /** The storage account name, or {@code null} for a SAS-only string with no account. */
    public String accountName() {
        return accountName;
    }

    /** The shared-key account key, or {@code null} when the string is SAS-based. */
    public String accountKey() {
        return accountKey;
    }

    /** The endpoint suffix; defaults to {@code core.windows.net}. */
    public String endpointSuffix() {
        return endpointSuffix;
    }

    /** The shared access signature (without the leading {@code ?}), or {@code null} for shared-key. */
    public String sharedAccessSignature() {
        return sas;
    }

    /** The explicit {@code BlobEndpoint} if present, else derived from account, protocol, and suffix. */
    public String blobEndpoint() {
        return serviceEndpoint(blobEndpoint, "blob");
    }

    /** The explicit {@code QueueEndpoint} if present, else derived. */
    public String queueEndpoint() {
        return serviceEndpoint(queueEndpoint, "queue");
    }

    /** The explicit {@code TableEndpoint} if present, else derived. */
    public String tableEndpoint() {
        return serviceEndpoint(tableEndpoint, "table");
    }

    /** The explicit {@code FileEndpoint} if present, else derived. */
    public String fileEndpoint() {
        return serviceEndpoint(fileEndpoint, "file");
    }

    private String serviceEndpoint(String explicit, String service) {
        if (explicit != null) {
            return explicit;
        }
        if (accountName == null) {
            return null; // cannot derive without an account
        }
        return protocol + "://" + accountName + "." + service + "." + endpointSuffix;
    }

    /** A redacted view of this connection string with the account key and SAS masked. */
    public String redacted() {
        return render(true);
    }

    @Override
    public String toString() {
        return render(true);
    }

    private String render(boolean redact) {
        if (development) {
            return "AzureConnectionString{development=true, account=" + DEV_ACCOUNT_NAME + "}";
        }
        StringBuilder sb = new StringBuilder("AzureConnectionString{");
        sb.append("protocol=").append(protocol);
        sb.append(", accountName=").append(accountName);
        sb.append(", accountKey=").append(mask(accountKey, redact));
        sb.append(", endpointSuffix=").append(endpointSuffix);
        sb.append(", sas=").append(mask(sas, redact));
        sb.append(", blobEndpoint=").append(blobEndpoint());
        sb.append('}');
        return sb.toString();
    }

    private static String mask(String value, boolean redact) {
        if (value == null) {
            return "null";
        }
        return redact ? "***" : value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AzureConnectionString other)) {
            return false;
        }
        return development == other.development
                && Objects.equals(protocol, other.protocol)
                && Objects.equals(accountName, other.accountName)
                && Objects.equals(accountKey, other.accountKey)
                && Objects.equals(endpointSuffix, other.endpointSuffix)
                && Objects.equals(sas, other.sas)
                && Objects.equals(blobEndpoint, other.blobEndpoint)
                && Objects.equals(queueEndpoint, other.queueEndpoint)
                && Objects.equals(tableEndpoint, other.tableEndpoint)
                && Objects.equals(fileEndpoint, other.fileEndpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(development, protocol, accountName, accountKey, endpointSuffix, sas,
                blobEndpoint, queueEndpoint, tableEndpoint, fileEndpoint);
    }
}
