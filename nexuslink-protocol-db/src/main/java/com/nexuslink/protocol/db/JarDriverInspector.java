package com.nexuslink.protocol.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Looks inside a JDBC driver jar to work out which class implements {@link java.sql.Driver},
 * so the user can add a driver by picking a file and nothing else.
 *
 * <p>Every JDBC 4.0+ driver declares its implementation in
 * {@code META-INF/services/java.sql.Driver}, which is why that is the primary source. Some older
 * or repackaged corporate drivers omit it, so we fall back to scanning class names for the
 * conventional {@code *Driver} shape. Neither step loads any class — a jar the user selected by
 * mistake should produce an error message, not arbitrary code execution.
 */
public final class JarDriverInspector {

    private JarDriverInspector() {}

    /**
     * Returns the JDBC driver classes declared by {@code jar}, best candidate first, or an empty
     * list if it doesn't look like a JDBC driver.
     */
    public static List<String> findDriverClasses(Path jar) throws IOException {
        Set<String> found = new LinkedHashSet<>(); // declared first, guessed after
        try (JarFile jf = new JarFile(jar.toFile())) {
            found.addAll(readServiceDeclaration(jf));
            if (found.isEmpty()) found.addAll(guessFromClassNames(jf));
        }
        return List.copyOf(found);
    }

    /** Reads {@code META-INF/services/java.sql.Driver}, the declaration every modern driver ships. */
    private static List<String> readServiceDeclaration(JarFile jf) throws IOException {
        List<String> classes = new ArrayList<>();
        ZipEntry entry = jf.getEntry("META-INF/services/java.sql.Driver");
        if (entry == null) return classes;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(jf.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int hash = line.indexOf('#'); // the service-file format allows trailing comments
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (!line.isEmpty()) classes.add(line);
            }
        }
        return classes;
    }

    /**
     * Fallback for jars with no service declaration: any top-level class named {@code *Driver}.
     * Inner classes are skipped — a driver's entry point is never one.
     */
    private static List<String> guessFromClassNames(JarFile jf) {
        return jf.stream()
                .map(ZipEntry::getName)
                .filter(n -> n.endsWith("Driver.class") && !n.contains("$"))
                .map(n -> n.substring(0, n.length() - ".class".length()).replace('/', '.'))
                .sorted()
                .toList();
    }
}
