package com.nexuslink.plugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Protocol-agnostic connection configuration bag.
 * Values use String representation; each connector casts to the required type.
 * Secrets are referenced by vault key, not stored inline.
 */
public final class ConnectionConfig {

    private final String profileId;
    private final String protocolId;
    private final Map<String, String> properties;   // plain config values
    private final Map<String, String> secretRefs;   // key → vault reference

    private ConnectionConfig(Builder builder) {
        this.profileId  = builder.profileId;
        this.protocolId = builder.protocolId;
        this.properties = Collections.unmodifiableMap(new HashMap<>(builder.properties));
        this.secretRefs = Collections.unmodifiableMap(new HashMap<>(builder.secretRefs));
    }

    public String profileId()  { return profileId; }
    public String protocolId() { return protocolId; }

    public String get(String key) { return properties.get(key); }
    public String get(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }
    public boolean has(String key) { return properties.containsKey(key); }

    /** Returns the vault reference key for a secret property. */
    public String secretRef(String key) { return secretRefs.get(key); }
    public boolean hasSecret(String key) { return secretRefs.containsKey(key); }

    public static Builder builder(String profileId, String protocolId) {
        return new Builder(profileId, protocolId);
    }

    public static final class Builder {
        private final String profileId;
        private final String protocolId;
        private final Map<String, String> properties = new HashMap<>();
        private final Map<String, String> secretRefs = new HashMap<>();

        private Builder(String profileId, String protocolId) {
            this.profileId  = profileId;
            this.protocolId = protocolId;
        }

        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        public Builder secret(String key, String vaultRef) {
            secretRefs.put(key, vaultRef);
            return this;
        }

        public ConnectionConfig build() {
            return new ConnectionConfig(this);
        }
    }
}
