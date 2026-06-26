package com.nexuslink.core.env;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Owns the user's named environments (dev / staging / prod, …), the currently active one, and an
 * optional {@code .env} file, and resolves {@code ${VAR}} references against them. Persists the
 * environments to {@code ~/.nexuslink/environments.json}.
 *
 * <p>Resolution precedence, most specific first:
 * <ol>
 *   <li>the active environment's variables,</li>
 *   <li>the {@code .env} file ({@code ~/.nexuslink/.env} by default),</li>
 *   <li>the process's system environment ({@link System#getenv}).</li>
 * </ol>
 * So an environment can override {@code ${BASE_URL}} while {@code ${HOME}} still falls through to the
 * OS. Unknown names resolve to {@code null} (left as a literal {@code ${NAME}} by the interpolator).
 */
public final class EnvironmentService {

    /** On-disk shape: the saved environments + the id of the active one. */
    public static final class Data {
        public List<Environment> environments = new ArrayList<>();
        public String activeId = null;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private final Path dotEnvFile;
    private final Function<String, String> systemEnv;

    private Data data;
    private Map<String, String> dotEnv;     // lazily loaded, cached

    public EnvironmentService() {
        this(Path.of(System.getProperty("user.home"), ".nexuslink", "environments.json"),
             Path.of(System.getProperty("user.home"), ".nexuslink", ".env"),
             System::getenv);
    }

    public EnvironmentService(Path file, Path dotEnvFile, Function<String, String> systemEnv) {
        this.file = file;
        this.dotEnvFile = dotEnvFile;
        this.systemEnv = systemEnv == null ? n -> null : systemEnv;
        this.data = read();
    }

    // ---- environments -------------------------------------------------------

    public synchronized List<Environment> environments() {
        return new ArrayList<>(data.environments);
    }

    public synchronized Optional<Environment> active() {
        if (data.activeId == null) return Optional.empty();
        return data.environments.stream().filter(e -> e.id.equals(data.activeId)).findFirst();
    }

    public synchronized Optional<Environment> byId(String id) {
        return data.environments.stream().filter(e -> e.id.equals(id)).findFirst();
    }

    /** Adds (or replaces by id) an environment and persists. The first one added becomes active. */
    public synchronized Environment save(Environment env) {
        data.environments.removeIf(e -> e.id.equals(env.id));
        data.environments.add(env);
        if (data.activeId == null) data.activeId = env.id;
        write();
        return env;
    }

    public synchronized void delete(String id) {
        data.environments.removeIf(e -> e.id.equals(id));
        if (id.equals(data.activeId)) {
            data.activeId = data.environments.isEmpty() ? null : data.environments.get(0).id;
        }
        write();
    }

    /** Switches the active environment; {@code null} clears it (only system + .env then resolve). */
    public synchronized void setActive(String id) {
        if (id == null || data.environments.stream().anyMatch(e -> e.id.equals(id))) {
            data.activeId = id;
            write();
        }
    }

    // ---- resolution ---------------------------------------------------------

    /** Resolves a single variable name with the documented precedence, or {@code null} if unknown. */
    public synchronized String resolve(String name) {
        Optional<Environment> env = active();
        if (env.isPresent()) {
            String v = env.get().asMap().get(name);
            if (v != null) return v;
        }
        String fromDotEnv = dotEnv().get(name);
        if (fromDotEnv != null) return fromDotEnv;
        return systemEnv.apply(name);
    }

    /** A resolver function suitable for {@link VariableInterpolator}. */
    public Function<String, String> resolver() {
        return this::resolve;
    }

    /** Interpolates {@code ${VAR}} references in {@code template} against the active environment. */
    public String interpolate(String template) {
        return VariableInterpolator.interpolate(template, resolver());
    }

    /** Applies {@link #interpolate(String)} to every value of {@code fields}, preserving order. */
    public Map<String, String> interpolateAll(Map<String, String> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fields != null) {
            Function<String, String> r = resolver();
            fields.forEach((k, v) -> out.put(k, VariableInterpolator.interpolate(v, r)));
        }
        return out;
    }

    /** Convenience for views: interpolate each field via {@code mapper} (e.g. to rebuild a record). */
    public String apply(String template, UnaryOperator<String> mapper) {
        return mapper.apply(interpolate(template));
    }

    /** A masker for the active environment's secret values, for scrubbing logs/rendered requests. */
    public synchronized SecretMaskingFilter masker() {
        return SecretMaskingFilter.forEnvironment(active().orElse(null));
    }

    // ---- .env file ----------------------------------------------------------

    /** Forces the {@code .env} file to be re-read on the next resolve (after the user edits it). */
    public synchronized void reloadDotEnv() {
        dotEnv = null;
    }

    private Map<String, String> dotEnv() {
        if (dotEnv == null) dotEnv = readDotEnv();
        return dotEnv;
    }

    private Map<String, String> readDotEnv() {
        Map<String, String> map = new LinkedHashMap<>();
        if (dotEnvFile == null || !Files.exists(dotEnvFile)) return map;
        try {
            for (String raw : Files.readAllLines(dotEnvFile)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).strip();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String value = unquote(line.substring(eq + 1).strip());
                if (!key.isEmpty()) map.put(key, value);
            }
        } catch (IOException e) {
            return map;     // unreadable .env — behave as if absent rather than failing the app
        }
        return map;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- persistence --------------------------------------------------------

    private Data read() {
        if (!Files.exists(file)) return new Data();
        try {
            Data d = MAPPER.readValue(Files.readAllBytes(file), Data.class);
            return d == null ? new Data() : d;
        } catch (IOException e) {
            return new Data();      // corrupt/old file — start fresh rather than fail the app
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, MAPPER.writeValueAsBytes(data));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save environments to " + file, e);
        }
    }
}
