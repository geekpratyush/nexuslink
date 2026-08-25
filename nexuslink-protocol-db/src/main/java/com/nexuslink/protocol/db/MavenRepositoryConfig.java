package com.nexuslink.protocol.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where on-demand JDBC driver jars are fetched from, and with what credentials.
 *
 * <p>Most organisations do not allow desktop tools to reach {@code repo1.maven.org} directly —
 * they run an internal Artifactory/Nexus mirror instead, usually behind basic auth or an API
 * token, and often behind an HTTP proxy. This resolves that configuration from, in order of
 * precedence:
 *
 * <ol>
 *   <li>System properties: {@code nexuslink.maven.repoUrl}, {@code .username}, {@code .password},
 *       {@code .token}</li>
 *   <li>Environment: {@code NEXUSLINK_MAVEN_REPO_URL}, {@code NEXUSLINK_MAVEN_USERNAME},
 *       {@code NEXUSLINK_MAVEN_PASSWORD}, {@code NEXUSLINK_MAVEN_TOKEN}</li>
 *   <li>{@code ~/.nexuslink/maven.properties} — keys {@code repoUrl}, {@code username},
 *       {@code password}, {@code token}</li>
 *   <li>{@code ~/.m2/settings.xml} — the first {@code <mirror>} URL plus the matching
 *       {@code <server>} credentials, so a machine already set up for Maven needs no extra
 *       NexusLink configuration</li>
 *   <li>Maven Central, unauthenticated</li>
 * </ol>
 *
 * <p>Note that {@code settings.xml} passwords encrypted with {@code settings-security.xml} are
 * <em>not</em> decrypted; configure such setups explicitly via one of the higher-precedence
 * sources.
 */
public record MavenRepositoryConfig(String baseUrl, String username, String password, String token) {

    public static final String CENTRAL = "https://repo1.maven.org/maven2";

    private static final Path PROPERTIES_FILE =
            Path.of(System.getProperty("user.home"), ".nexuslink", "maven.properties");
    private static final Path SETTINGS_XML =
            Path.of(System.getProperty("user.home"), ".m2", "settings.xml");

    /** Local Maven repository, checked before any download so an already-primed {@code ~/.m2} works offline. */
    public static Path localRepository() {
        String override = first(System.getProperty("maven.repo.local"), System.getenv("MAVEN_REPO_LOCAL"));
        return override != null ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /** Resolves the effective configuration from the sources documented on this class. */
    public static MavenRepositoryConfig resolve() {
        Properties file = readProperties();
        MavenSettings settings = MavenSettings.read(SETTINGS_XML);

        String url = first(
                System.getProperty("nexuslink.maven.repoUrl"),
                System.getenv("NEXUSLINK_MAVEN_REPO_URL"),
                file.getProperty("repoUrl"),
                settings.mirrorUrl());
        String user = first(
                System.getProperty("nexuslink.maven.username"),
                System.getenv("NEXUSLINK_MAVEN_USERNAME"),
                file.getProperty("username"),
                settings.username());
        String pass = first(
                System.getProperty("nexuslink.maven.password"),
                System.getenv("NEXUSLINK_MAVEN_PASSWORD"),
                file.getProperty("password"),
                settings.password());
        String token = first(
                System.getProperty("nexuslink.maven.token"),
                System.getenv("NEXUSLINK_MAVEN_TOKEN"),
                file.getProperty("token"));

        return new MavenRepositoryConfig(url != null ? stripTrailingSlash(url) : CENTRAL, user, pass, token);
    }

    /** True when this points somewhere other than Maven Central — used for user-facing wording. */
    public boolean isCustomRepository() {
        return !CENTRAL.equals(baseUrl);
    }

    /** Short label for the repository, e.g. {@code "Maven Central"} or the mirror's host. */
    public String displayName() {
        if (!isCustomRepository()) return "Maven Central";
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            return host != null ? host : baseUrl;
        } catch (IllegalArgumentException e) {
            return baseUrl;
        }
    }

    /**
     * The {@code Authorization} header value for this repository, if any: a bearer token when one
     * is configured, otherwise basic auth when a username is set.
     */
    public Optional<String> authorizationHeader() {
        if (notBlank(token)) return Optional.of("Bearer " + token);
        if (notBlank(username)) {
            String raw = username + ":" + (password == null ? "" : password);
            return Optional.of("Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        return Optional.empty();
    }

    /** The repository-relative path for {@code group:artifact:version}, e.g. {@code org/x/x-1.0.jar}. */
    public static String artifactPath(String group, String artifact, String version) {
        return group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".jar";
    }

    /** Absolute download URL for {@code group:artifact:version} against this repository. */
    public String artifactUrl(String group, String artifact, String version) {
        return baseUrl + "/" + artifactPath(group, artifact, version);
    }

    // ---- sources ------------------------------------------------------------

    private static Properties readProperties() {
        Properties props = new Properties();
        if (Files.isReadable(PROPERTIES_FILE)) {
            try (var in = Files.newInputStream(PROPERTIES_FILE)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + PROPERTIES_FILE, e);
            }
        }
        return props;
    }

    /**
     * The scraps of {@code settings.xml} we care about. Parsed with regexes rather than a full
     * XML model on purpose: we only need the first mirror and its server credentials, and this
     * must never fail hard on a settings file with constructs we don't understand.
     */
    record MavenSettings(String mirrorUrl, String username, String password) {

        private static final MavenSettings EMPTY = new MavenSettings(null, null, null);

        static MavenSettings read(Path settingsXml) {
            if (!Files.isReadable(settingsXml)) return EMPTY;
            try {
                return parse(Files.readString(settingsXml));
            } catch (IOException | RuntimeException e) {
                return EMPTY; // a malformed settings.xml just means "no mirror configured"
            }
        }

        static MavenSettings parse(String xml) {
            String stripped = xml.replaceAll("(?s)<!--.*?-->", "");
            Matcher mirror = Pattern.compile("(?s)<mirror>(.*?)</mirror>").matcher(stripped);
            if (!mirror.find()) return EMPTY;
            String block = mirror.group(1);
            String url = tag(block, "url");
            if (url == null) return EMPTY;
            String id = tag(block, "id");

            String user = null, pass = null;
            if (id != null) {
                Matcher servers = Pattern.compile("(?s)<server>(.*?)</server>").matcher(stripped);
                while (servers.find()) {
                    String server = servers.group(1);
                    if (id.equals(tag(server, "id"))) {
                        user = tag(server, "username");
                        String p = tag(server, "password");
                        // {…} marks a value encrypted with settings-security.xml, which we can't read.
                        pass = (p != null && p.startsWith("{") && p.endsWith("}")) ? null : p;
                        break;
                    }
                }
            }
            return new MavenSettings(stripTrailingSlash(url), user, pass);
        }

        private static String tag(String block, String name) {
            Matcher m = Pattern.compile("(?s)<" + name + ">(.*?)</" + name + ">").matcher(block);
            return m.find() ? m.group(1).trim() : null;
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static String first(String... values) {
        for (String v : values) {
            if (notBlank(v)) return v.trim();
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
