package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarDriverInspectorTest {

    /** Writes a jar whose entries are {@code name → content} (empty content for class stubs). */
    private static Path jarWith(Path dir, String name, Map<String, String> entries) throws IOException {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void readsTheServiceDeclarationEveryModernDriverShips(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "d.jar", Map.of(
                "META-INF/services/java.sql.Driver", "com.example.ExampleDriver\n",
                "com/example/ExampleDriver.class", ""));
        assertEquals(List.of("com.example.ExampleDriver"), JarDriverInspector.findDriverClasses(jar));
    }

    @Test
    void commentsAndBlankLinesInTheServiceFileAreIgnored(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "d.jar", Map.of(
                "META-INF/services/java.sql.Driver",
                "# the driver\n\ncom.example.ExampleDriver  # inline\n"));
        assertEquals(List.of("com.example.ExampleDriver"), JarDriverInspector.findDriverClasses(jar));
    }

    @Test
    void fallsBackToClassNamesWhenTheDeclarationIsMissing(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "legacy.jar", Map.of(
                "com/corp/LegacyDriver.class", "",
                "com/corp/LegacyDriver$Inner.class", "",
                "com/corp/Helper.class", ""));
        assertEquals(List.of("com.corp.LegacyDriver"), JarDriverInspector.findDriverClasses(jar),
                "inner classes and non-driver classes are excluded");
    }

    @Test
    void aJarThatIsNotADriverYieldsNothing(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "notes.jar", Map.of("readme.txt", "hello"));
        assertTrue(JarDriverInspector.findDriverClasses(jar).isEmpty());
    }
}
