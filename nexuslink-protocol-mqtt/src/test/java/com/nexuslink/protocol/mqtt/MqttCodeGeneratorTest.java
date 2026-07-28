package com.nexuslink.protocol.mqtt;

import com.nexuslink.plugin.codegen.CodeGenRegistry;
import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MqttCodeGeneratorTest {

    private final MqttCodeGenerator gen = new MqttCodeGenerator();

    private String render(String targetId, MqttCodeGenerator.Request request) {
        return gen.generate(gen.targetById(targetId), request);
    }

    @Test
    @DisplayName("the CLI snippet carries the parsed host, port, topic and QoS")
    void cliSnippet() {
        String code = render("mosquitto",
                new MqttCodeGenerator.Request("tcp://broker.example:1884", "sensors/temp", 2, true, ""));

        assertTrue(code.contains("mosquitto_pub -h broker.example -p 1884"), code);
        assertTrue(code.contains("-t 'sensors/temp' -q 2 -r"), code);
        assertTrue(code.contains("mosquitto_sub -h broker.example -p 1884"), code);
    }

    @Test
    @DisplayName("a scheme-default port is resolved rather than guessed by the snippet")
    void defaultPortResolved() {
        String code = render("mosquitto", MqttCodeGenerator.Request.of("ssl://broker.example", "a/b"));

        assertTrue(code.contains("-p 8883"), "ssl:// defaults to 8883: " + code);
        assertTrue(code.contains("--capath"), "a TLS endpoint gets a trust path: " + code);
    }

    @Test
    @DisplayName("a password in the broker URI never reaches a snippet")
    void passwordNeverLeaks() {
        MqttCodeGenerator.Request request =
                new MqttCodeGenerator.Request("tcp://user:hunter2@broker:1883", "a/b", 1, false, "user");

        for (CodeGenTarget target : gen.targets()) {
            String code = gen.generate(target, request);
            assertFalse(code.contains("hunter2"), target.id() + " leaked the password: " + code);
        }
    }

    @Test
    @DisplayName("a username produces a credential placeholder in every target")
    void credentialsPlaceholder() {
        MqttCodeGenerator.Request request =
                new MqttCodeGenerator.Request("tcp://broker:1883", "a/b", 0, false, "alice");

        for (CodeGenTarget target : gen.targets()) {
            String code = gen.generate(target, request);
            assertTrue(code.contains("alice"), target.id() + " dropped the username");
            assertTrue(code.toLowerCase().contains("<password>"), target.id() + " has no password placeholder");
        }
    }

    @Test
    @DisplayName("blank inputs fall back to runnable defaults and QoS is clamped")
    void requestDefaults() {
        MqttCodeGenerator.Request request = new MqttCodeGenerator.Request("", "", 7, false, null);

        assertEquals("tcp://localhost:1883", request.brokerUri());
        assertEquals("my/topic", request.topic());
        assertEquals(2, request.qos());
        assertEquals("", request.username());
    }

    @Test
    @DisplayName("every target renders non-empty code and an unknown target is rejected")
    void targetsAndGuards() {
        MqttCodeGenerator.Request request = MqttCodeGenerator.Request.of("tcp://h:1883", "t");

        assertEquals(4, gen.targets().size());
        for (CodeGenTarget target : gen.targets()) {
            assertFalse(gen.generate(target, request).isBlank(), target.id() + " rendered nothing");
        }
        assertThrows(IllegalArgumentException.class,
                () -> gen.generate(new CodeGenTarget("cobol", "COBOL", "text"), request));
        assertThrows(IllegalArgumentException.class,
                () -> gen.generate(gen.targets().get(0), "not an mqtt request"));
    }

    @Test
    @DisplayName("the generator is discoverable through the SPI registry")
    void discoverableViaServiceLoader() {
        CodeGenRegistry registry = CodeGenRegistry.load(MqttCodeGenerator.class.getClassLoader());

        CodeGenerator found = registry.byProtocol("mqtt").orElseThrow();
        assertEquals("MQTT", found.displayName());
        assertTrue(found.supports(MqttCodeGenerator.Request.of("tcp://h:1883", "t")));
        assertFalse(found.supports("a string"));
    }
}
