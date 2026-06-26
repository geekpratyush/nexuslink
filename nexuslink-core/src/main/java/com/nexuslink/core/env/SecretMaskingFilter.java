package com.nexuslink.core.env;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Keeps secret values from leaking into logs and the UI. Two jobs:
 *
 * <ul>
 *   <li>{@link #looksSecret(String)} — a name heuristic used to default a variable's "secret" flag
 *       so passwords/tokens/keys are masked without the user having to remember to tick the box.</li>
 *   <li>{@link #scrub(String)} — replaces every occurrence of a known secret value in arbitrary text
 *       (a log line, a rendered request) with {@link #MASK}, so a copied URL or logged body never
 *       reveals the real secret. The UI keeps the plaintext for its own reveal toggle.</li>
 * </ul>
 */
public final class SecretMaskingFilter {

    /** The placeholder shown in place of a secret value. */
    public static final String MASK = "••••••";

    private static final String[] SECRET_HINTS = {
            "password", "passwd", "secret", "token", "apikey", "api_key", "accesskey",
            "access_key", "credential", "private", "passphrase", "pwd", "auth"
    };

    private final List<String> secrets;     // sorted longest-first so overlapping values mask cleanly

    public SecretMaskingFilter(Collection<String> secretValues) {
        this.secrets = new ArrayList<>();
        if (secretValues != null) {
            for (String v : secretValues) {
                if (v != null && !v.isEmpty()) this.secrets.add(v);
            }
            this.secrets.sort((a, b) -> Integer.compare(b.length(), a.length()));
        }
    }

    /** A masker for the secret values currently defined in {@code env} (empty values are ignored). */
    public static SecretMaskingFilter forEnvironment(Environment env) {
        List<String> values = new ArrayList<>();
        if (env != null) {
            for (EnvVariable v : env.variables) {
                if (v.secret && v.value != null && !v.value.isEmpty()) values.add(v.value);
            }
        }
        return new SecretMaskingFilter(values);
    }

    /** True if a variable name looks like it holds a secret, used to pre-tick the "secret" flag. */
    public static boolean looksSecret(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String hint : SECRET_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    /** Masks a single value outright (for a UI field whose reveal toggle is off). */
    public static String maskValue(String value) {
        return (value == null || value.isEmpty()) ? "" : MASK;
    }

    /** Replaces every known secret value found in {@code text} with {@link #MASK}. */
    public String scrub(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) return text;
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, MASK);
        }
        return result;
    }

    public boolean isEmpty() {
        return secrets.isEmpty();
    }
}
