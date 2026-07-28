package com.nexuslink.plugin.codegen;

import java.util.List;

/**
 * Turns a protocol's request/connection object into a ready-to-run client snippet — the
 * cross-protocol code-generation SPI.
 *
 * <p>Each protocol module contributes one implementation and registers it in
 * {@code META-INF/services/com.nexuslink.plugin.codegen.CodeGenerator}; the UI discovers them
 * through {@link CodeGenRegistry} and never names a protocol directly. Implementations are stateless
 * and must be safe to call from any thread.
 *
 * <p>{@link #supports(Object)} is what keeps the SPI type-safe without generics leaking into the
 * registry: a generator claims only the request type it understands, so the registry can offer the
 * user exactly the generators that apply to the object in hand.
 */
public interface CodeGenerator {

    /** Stable protocol id, e.g. {@code "rest"}, {@code "kafka"} — unique across generators. */
    String protocolId();

    /** Human-readable protocol name for the UI, e.g. {@code "REST"}. */
    String displayName();

    /** {@code true} if {@code request} is a request object this generator can render. */
    boolean supports(Object request);

    /** The output targets this generator offers, in display order (never empty). */
    List<CodeGenTarget> targets();

    /**
     * Renders {@code request} as a snippet for {@code target}.
     *
     * @throws IllegalArgumentException if the target is not one of {@link #targets()} or the request
     *                                  is not {@linkplain #supports(Object) supported}
     */
    String generate(CodeGenTarget target, Object request);

    /** The target with {@code id}, or the first target when no such id exists. */
    default CodeGenTarget targetById(String id) {
        return targets().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseGet(() -> targets().get(0));
    }
}
