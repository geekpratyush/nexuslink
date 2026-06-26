package com.nexuslink.core.env;

/**
 * A single named variable inside an {@link Environment}. {@link #secret} marks values that must be
 * masked in logs and the UI (passwords, tokens, keys); see {@link SecretMaskingFilter}.
 *
 * <p>Plain public fields keep JSON (de)serialization trivial, matching the rest of the core models.
 */
public final class EnvVariable {

    public String name = "";
    public String value = "";
    public boolean secret = false;

    public EnvVariable() {}

    public EnvVariable(String name, String value) {
        this(name, value, false);
    }

    public EnvVariable(String name, String value, boolean secret) {
        this.name = name;
        this.value = value;
        this.secret = secret;
    }
}
