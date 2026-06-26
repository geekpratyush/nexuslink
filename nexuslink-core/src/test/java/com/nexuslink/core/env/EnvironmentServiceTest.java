package com.nexuslink.core.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentServiceTest {

    private EnvironmentService service(Path dir, Map<String, String> sysEnv) {
        return new EnvironmentService(dir.resolve("environments.json"), dir.resolve(".env"), sysEnv::get);
    }

    @Test
    void firstSavedEnvironmentBecomesActiveAndResolves(@TempDir Path dir) {
        var svc = service(dir, Map.of());
        svc.save(new Environment("dev").set("BASE_URL", "http://localhost:8080"));

        assertTrue(svc.active().isPresent());
        assertEquals("dev", svc.active().get().name);
        assertEquals("http://localhost:8080/api", svc.interpolate("${BASE_URL}/api"));
    }

    @Test
    void activeEnvironmentOverridesDotEnvOverridesSystemEnv(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "BASE_URL=http://from-dotenv\nTOKEN=abc\n");
        var svc = service(dir, Map.of("BASE_URL", "http://from-system", "REGION", "us-east"));

        // No active env yet → .env wins over system env; system env still falls through.
        assertEquals("http://from-dotenv", svc.resolve("BASE_URL"));
        assertEquals("us-east", svc.resolve("REGION"));

        // Active env overrides both .env and system env for the keys it defines.
        svc.save(new Environment("prod").set("BASE_URL", "http://from-env"));
        assertEquals("http://from-env", svc.resolve("BASE_URL"));
        assertEquals("abc", svc.resolve("TOKEN"));     // still from .env
        assertEquals("us-east", svc.resolve("REGION")); // still from system env
    }

    @Test
    void switchingActiveEnvironmentRepointsVariables(@TempDir Path dir) {
        var svc = service(dir, Map.of());
        var dev = svc.save(new Environment("dev").set("HOST", "dev.example.com"));
        var prod = svc.save(new Environment("prod").set("HOST", "prod.example.com"));

        svc.setActive(dev.id);
        assertEquals("https://dev.example.com", svc.interpolate("https://${HOST}"));
        svc.setActive(prod.id);
        assertEquals("https://prod.example.com", svc.interpolate("https://${HOST}"));
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        var svc = service(dir, Map.of());
        var prod = svc.save(new Environment("prod").set("KEY", "value", true));
        svc.setActive(prod.id);

        var reopened = service(dir, Map.of());
        assertEquals(1, reopened.environments().size());
        assertTrue(reopened.active().isPresent());
        assertEquals("value", reopened.resolve("KEY"));
        assertTrue(reopened.active().get().variables.get(0).secret);
    }

    @Test
    void deletingActiveEnvironmentFallsBackToAnother(@TempDir Path dir) {
        var svc = service(dir, Map.of());
        var dev = svc.save(new Environment("dev").set("X", "1"));
        svc.save(new Environment("prod").set("X", "2"));
        svc.setActive(dev.id);

        svc.delete(dev.id);
        assertTrue(svc.active().isPresent(), "deleting the active env should pick a remaining one");
        assertEquals("prod", svc.active().get().name);
    }

    @Test
    void maskerScrubsActiveSecretsFromText(@TempDir Path dir) {
        var svc = service(dir, Map.of());
        var prod = svc.save(new Environment("prod")
                .set("USER", "alice")
                .set("PASSWORD", "s3cr3t", true));
        svc.setActive(prod.id);

        String rendered = svc.interpolate("user=${USER} pass=${PASSWORD}");
        assertEquals("user=alice pass=s3cr3t", rendered);
        assertEquals("user=alice pass=" + SecretMaskingFilter.MASK, svc.masker().scrub(rendered));
    }
}
