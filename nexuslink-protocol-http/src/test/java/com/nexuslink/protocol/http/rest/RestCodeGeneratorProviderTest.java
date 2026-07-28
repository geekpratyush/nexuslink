package com.nexuslink.protocol.http.rest;

import com.nexuslink.plugin.codegen.CodeGenRegistry;
import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestCodeGeneratorProviderTest {

    private final RestCodeGeneratorProvider provider = new RestCodeGeneratorProvider();

    private static RestRequest request() {
        RestRequest r = new RestRequest();
        r.setUrl("https://api.example.com/v1/things");
        r.setMethod("GET");
        return r;
    }

    @Test
    @DisplayName("every RestCodeGenerator language is exposed as a target")
    void allLanguagesExposed() {
        assertEquals(RestCodeGenerator.Language.values().length, provider.targets().size());
        for (RestCodeGenerator.Language lang : RestCodeGenerator.Language.values()) {
            CodeGenTarget target = provider.targetById(lang.name().toLowerCase());
            assertEquals(lang.label(), target.label());
        }
    }

    @Test
    @DisplayName("a target renders exactly what RestCodeGenerator renders")
    void delegatesToRestCodeGenerator() {
        RestRequest r = request();

        String viaProvider = provider.generate(provider.targetById("curl"), r);

        assertEquals(RestCodeGenerator.generate(RestCodeGenerator.Language.CURL, r), viaProvider);
        assertTrue(viaProvider.contains("https://api.example.com/v1/things"));
    }

    @Test
    @DisplayName("only a RestRequest is claimed, and a bad target is rejected")
    void guards() {
        assertTrue(provider.supports(request()));
        assertFalse(provider.supports("https://example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> provider.generate(provider.targets().get(0), "not a request"));
        assertThrows(IllegalArgumentException.class,
                () -> provider.generate(new CodeGenTarget("fortran", "Fortran", "text"), request()));
    }

    @Test
    @DisplayName("the provider is discoverable through the SPI registry")
    void discoverableViaServiceLoader() {
        CodeGenRegistry registry = CodeGenRegistry.load(RestCodeGeneratorProvider.class.getClassLoader());

        CodeGenerator found = registry.firstFor(request()).orElseThrow();
        assertEquals("rest", found.protocolId());
        assertEquals("REST", found.displayName());
    }
}
