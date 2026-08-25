package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDriverStoreTest {

    private static Path fakeJar(Path dir, String name) throws IOException {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("dummy.txt"));
        }
        return jar;
    }

    @Test
    void addedDriversSurviveAReload(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("user-drivers.json");
        Path jar = fakeJar(tmp, "sybase.jar");

        var store = new UserDriverStore(file);
        store.add(new UserDriver(null, "Sybase ASE", "com.sybase.jdbc4.jdbc.SybDriver",
                jar.toString(), "jdbc:sybase:Tds:host:5000"));

        var reloaded = new UserDriverStore(file);
        assertEquals(1, reloaded.all().size());
        UserDriver d = reloaded.all().get(0);
        assertEquals("Sybase ASE", d.displayName);
        assertEquals("user:sybase-ase", d.id, "ids are slugged and namespaced away from built-ins");
    }

    @Test
    void idsAreMadeUniqueWhenNamesCollide(@TempDir Path tmp) throws IOException {
        var store = new UserDriverStore(tmp.resolve("d.json"));
        Path jar = fakeJar(tmp, "a.jar");
        store.add(new UserDriver(null, "Custom DB", "a.Driver", jar.toString(), null));
        store.add(new UserDriver(null, "Custom DB", "b.Driver", jar.toString(), null));
        assertEquals(java.util.List.of("user:custom-db", "user:custom-db-2"),
                store.all().stream().map(d -> d.id).toList());
    }

    @Test
    void removingADriverDropsItFromDisk(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("d.json");
        Path jar = fakeJar(tmp, "a.jar");
        var store = new UserDriverStore(file);
        var added = store.add(new UserDriver(null, "Custom DB", "a.Driver", jar.toString(), null));

        assertTrue(store.remove(added.id));
        assertFalse(store.remove(added.id), "removing twice is a no-op");
        assertTrue(new UserDriverStore(file).all().isEmpty());
        assertTrue(Files.exists(jar), "the user's own jar is never deleted");
    }

    @Test
    void aMissingJarIsRejectedUpFront(@TempDir Path tmp) {
        var store = new UserDriverStore(tmp.resolve("d.json"));
        var e = assertThrows(IllegalArgumentException.class, () -> store.add(
                new UserDriver(null, "Ghost", "a.Driver", tmp.resolve("nope.jar").toString(), null)));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void nameAndDriverClassAreRequired(@TempDir Path tmp) throws IOException {
        var store = new UserDriverStore(tmp.resolve("d.json"));
        Path jar = fakeJar(tmp, "a.jar");
        assertThrows(IllegalArgumentException.class,
                () -> store.add(new UserDriver(null, "  ", "a.Driver", jar.toString(), null)));
        assertThrows(IllegalArgumentException.class,
                () -> store.add(new UserDriver(null, "Custom", null, jar.toString(), null)));
    }

    @Test
    void aCorruptFileDegradesToAnEmptyListRatherThanFailingStartup(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("d.json");
        Files.writeString(file, "{ this is not json");
        assertTrue(new UserDriverStore(file).all().isEmpty());
    }

    @Test
    void userDriversAdaptToTheCatalogShape(@TempDir Path tmp) throws IOException {
        Path jar = fakeJar(tmp, "a.jar");
        var store = new UserDriverStore(tmp.resolve("d.json"));
        var added = store.add(new UserDriver(null, "Custom DB", "a.Driver", jar.toString(), "jdbc:custom://host"));

        DriverInfo info = added.toDriverInfo();
        assertEquals("Custom DB", info.displayName());
        assertEquals("jdbc:custom://host", info.sampleUrl());
        assertFalse(info.bundled());
        assertEquals(null, info.mavenCoords(), "nothing to download — the jar is already local");
        assertTrue(JdbcDriverRegistry.isUserDriver(info.id()));
    }
}
