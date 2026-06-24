package com.nexuslink.protocol.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.HashSet;
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
     * Downloads a driver jar from Maven Central into {@link #DRIVER_DIR} and loads it.
     * {@code mavenCoords} is {@code group:artifact:version}.
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

    /** Resolves and downloads the jar for {@code group:artifact:version}; returns the cached path. */
    public static Path download(String mavenCoords) throws IOException, InterruptedException {
        String[] parts = mavenCoords.split(":");
        if (parts.length != 3) {
            throw new DriverLoadException("Maven coordinates must be group:artifact:version, got " + mavenCoords, null);
        }
        String group = parts[0], artifact = parts[1], version = parts[2];
        String path = group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".jar";
        URI url = URI.create("https://repo1.maven.org/maven2/" + path);

        Files.createDirectories(DRIVER_DIR);
        Path target = DRIVER_DIR.resolve(artifact + "-" + version + ".jar");
        if (Files.exists(target)) return target; // cached

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<InputStream> resp = http.send(
                HttpRequest.newBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new DriverLoadException("Download failed (HTTP " + resp.statusCode() + ") for " + url, null);
        }
        try (InputStream in = resp.body()) {
            Files.copy(in, target);
        }
        return target;
    }

    public static final class DriverLoadException extends RuntimeException {
        public DriverLoadException(String message, Throwable cause) { super(message, cause); }
    }
}
