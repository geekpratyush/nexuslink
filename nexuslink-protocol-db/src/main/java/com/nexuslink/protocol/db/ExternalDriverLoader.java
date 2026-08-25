package com.nexuslink.protocol.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Loads on-demand JDBC drivers from a local jar (or downloads one from Maven Central) and
 * registers them with {@link DriverManager} via a {@link DriverShim}.
 *
 * <p>This is what makes the "add Oracle / SQL Server / DB2 when you need it" flow work
 * without bloating the bundled app. See TASKS.md §8.1.1.
 */
public final class ExternalDriverLoader {

    /** Directory where downloaded driver jars are cached. */
    public static final Path DRIVER_DIR =
            Path.of(System.getProperty("user.home"), ".nexuslink", "drivers");

    private static final Set<String> registeredClasses = new HashSet<>();

    private ExternalDriverLoader() {}

    /**
     * Loads {@code driverClass} from {@code jar} and registers it. Idempotent per class.
     * @return true if newly registered, false if it was already available.
     */
    public static synchronized boolean loadFromJar(Path jar, String driverClass) {
        if (registeredClasses.contains(driverClass) || JdbcDriverRegistry.isDriverLoaded(driverClass)) {
            return false;
        }
        try {
            URL[] urls = {jar.toUri().toURL()};
            // Parent = this class's loader so the shim and driver share a visible hierarchy.
            URLClassLoader loader = new URLClassLoader(urls, ExternalDriverLoader.class.getClassLoader());
            Driver driver = (Driver) Class.forName(driverClass, true, loader)
                    .getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(driver));
            registeredClasses.add(driverClass);
            return true;
        } catch (Exception e) {
            throw new DriverLoadException("Failed to load driver " + driverClass
                    + " from " + jar + ": " + e.getMessage(), e);
        }
    }

    /**
     * Deregisters a driver loaded by {@link #loadFromJar} so it stops appearing as available after
     * the user removes it from the driver list. Bundled drivers, which nothing here registered,
     * are left alone.
     *
     * @return true if a shim was deregistered
     */
    public static synchronized boolean unload(String driverClass) {
        if (!registeredClasses.remove(driverClass)) return false;
        var drivers = DriverManager.drivers().toList();
        for (Driver d : drivers) {
            if (d instanceof DriverShim shim && shim.delegateClassName().equals(driverClass)) {
                try {
                    DriverManager.deregisterDriver(d);
                } catch (Exception ignored) {
                    // Nothing useful to do — the driver simply stays registered for this session.
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Makes {@code driver} usable without any user interaction if its jar is already on this
     * machine — a user driver's own jar, or a jar sitting in {@link #DRIVER_DIR} (downloaded
     * earlier, or placed there by the user running the {@code mvn} command from
     * {@link MavenCommandHelp}). Never touches the network.
     *
     * @return true if the driver is available afterwards
     */
    public static synchronized boolean ensureLoaded(DriverInfo driver) {
        if (JdbcDriverRegistry.isAvailable(driver)) return true;
        Optional<Path> jar = JdbcDriverRegistry.isUserDriver(driver.id())
                ? JdbcDriverRegistry.userDrivers().byId(driver.id())
                        .map(d -> Path.of(d.jarPath)).filter(Files::isReadable)
                : cachedJar(driver.mavenCoords());
        if (jar.isEmpty()) return false;
        try {
            loadFromJar(jar.get(), driver.driverClass());
            return true;
        } catch (DriverLoadException e) {
            return false; // a stale or corrupt jar shouldn't break driver selection
        }
    }

    /**
     * Finds a locally cached jar for {@code group:artifact:version} in {@link #DRIVER_DIR}. Falls
     * back to matching on artifact name alone, so a jar fetched at a slightly different version
     * than the catalog's default is still picked up.
     */
    public static Optional<Path> cachedJar(String mavenCoords) {
        if (mavenCoords == null) return Optional.empty();
        String[] parts = mavenCoords.split(":");
        if (parts.length != 3) return Optional.empty();
        String artifact = parts[1], version = parts[2];

        Path exact = DRIVER_DIR.resolve(artifact + "-" + version + ".jar");
        if (Files.isReadable(exact)) return Optional.of(exact);
        if (!Files.isDirectory(DRIVER_DIR)) return Optional.empty();
        try (var files = Files.list(DRIVER_DIR)) {
            return files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(artifact + "-") && name.endsWith(".jar");
                    })
                    .sorted() // deterministic when several versions are present
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Downloads a driver jar into {@link #DRIVER_DIR} and loads it. {@code mavenCoords} is
     * {@code group:artifact:version}. See {@link MavenRepositoryConfig} for how the source
     * repository (Maven Central, or an internal Artifactory/Nexus mirror) is chosen.
     */
    public static synchronized void downloadAndLoad(String mavenCoords, String driverClass) {
        try {
            Path jar = download(mavenCoords);
            loadFromJar(jar, driverClass);
        } catch (DriverLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new DriverLoadException("Failed to download " + mavenCoords + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the jar for {@code group:artifact:version} and returns its cached path, fetching it
     * only if it isn't already on disk. Resolution order:
     *
     * <ol>
     *   <li>{@link #DRIVER_DIR} — previously downloaded by NexusLink</li>
     *   <li>the local Maven repository ({@code ~/.m2/repository}) — so a machine whose {@code ~/.m2}
     *       is already primed needs no network access at all</li>
     *   <li>the configured remote repository (Maven Central or an internal mirror)</li>
     * </ol>
     */
    public static Path download(String mavenCoords) throws IOException, InterruptedException {
        String[] parts = mavenCoords.split(":");
        if (parts.length != 3) {
            throw new DriverLoadException("Maven coordinates must be group:artifact:version, got " + mavenCoords, null);
        }
        String group = parts[0], artifact = parts[1], version = parts[2];

        Files.createDirectories(DRIVER_DIR);
        Path target = DRIVER_DIR.resolve(artifact + "-" + version + ".jar");
        if (Files.exists(target)) return target; // cached

        Path local = MavenRepositoryConfig.localRepository()
                .resolve(MavenRepositoryConfig.artifactPath(group, artifact, version));
        if (Files.isReadable(local)) {
            Files.copy(local, target);
            return target;
        }

        MavenRepositoryConfig repo = MavenRepositoryConfig.resolve();
        URI url = URI.create(repo.artifactUrl(group, artifact, version));

        // ProxySelector.getDefault() honours -Dhttp.proxyHost/-Dhttps.proxyHost and, with
        // -Djava.net.useSystemProxies=true, the OS proxy configuration.
        HttpClient http = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(url).GET();
        repo.authorizationHeader().ifPresent(value -> request.header("Authorization", value));

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new DriverLoadException(downloadHelp(repo, url,
                    "Could not reach the repository: " + e.getMessage()), e);
        }
        if (resp.statusCode() != 200) {
            String detail = switch (resp.statusCode()) {
                case 401, 403 -> "The repository rejected the request (HTTP " + resp.statusCode()
                        + "). Credentials may be missing, expired, or lack access to this artifact.";
                case 404 -> "Not found in this repository (HTTP 404). The mirror may not proxy "
                        + "Maven Central, or may not carry this artifact.";
                default -> "Download failed (HTTP " + resp.statusCode() + ").";
            };
            throw new DriverLoadException(downloadHelp(repo, url, detail), null);
        }
        try (InputStream in = resp.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(target); // never leave a truncated jar behind to be "cached"
            throw e;
        }
        return target;
    }

    /** Builds a failure message that tells the user what to do next in a locked-down network. */
    private static String downloadHelp(MavenRepositoryConfig repo, URI url, String detail) {
        return detail + "\n\nRepository: " + repo.displayName() + "  (" + url + ")"
                + "\n\nIn an environment without direct internet access you can either:"
                + "\n  • point NexusLink at your internal Artifactory/Nexus — set nexuslink.maven.repoUrl"
                + " (plus .username/.password or .token), or NEXUSLINK_MAVEN_REPO_URL, or repoUrl in"
                + " ~/.nexuslink/maven.properties; a mirror in ~/.m2/settings.xml is picked up automatically;"
                + "\n  • place the driver jar in " + DRIVER_DIR + " and restart, or choose it with"
                + " \"Load driver from jar…\".";
    }

    public static final class DriverLoadException extends RuntimeException {
        public DriverLoadException(String message, Throwable cause) { super(message, cause); }
    }
}
