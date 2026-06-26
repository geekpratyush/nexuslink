package com.nexuslink.core.env;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A named set of variables the user can switch between — typically {@code dev} / {@code staging} /
 * {@code prod}. Switching the active {@link Environment} re-points every {@code ${VAR}} reference at
 * a different set of values without editing the requests themselves.
 *
 * <p>Plain public fields keep JSON (de)serialization trivial. Persisted by {@link EnvironmentService}.
 */
public final class Environment {

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public List<EnvVariable> variables = new ArrayList<>();

    public Environment() {}

    public Environment(String name) {
        this.name = name;
    }

    public Environment set(String key, String value) {
        return set(key, value, false);
    }

    public Environment set(String key, String value, boolean secret) {
        for (EnvVariable v : variables) {
            if (v.name.equals(key)) {
                v.value = value;
                v.secret = secret;
                return this;
            }
        }
        variables.add(new EnvVariable(key, value, secret));
        return this;
    }

    /** The variables as an insertion-ordered map (later duplicates win, mirroring the editor). */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (EnvVariable v : variables) {
            if (v.name != null && !v.name.isBlank()) m.put(v.name, v.value == null ? "" : v.value);
        }
        return m;
    }

    /** The names flagged as secret, so their values can be masked everywhere they surface. */
    public List<String> secretNames() {
        List<String> names = new ArrayList<>();
        for (EnvVariable v : variables) {
            if (v.secret && v.name != null && !v.name.isBlank()) names.add(v.name);
        }
        return names;
    }

    @Override public String toString() { return name; }
}
