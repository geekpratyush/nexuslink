package com.nexuslink.protocol.grpc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pure, dependency-free registry of the 17 canonical gRPC status codes (0..16) — the same set
 * carried in the {@code grpc-status} response trailer. This class deliberately does <b>not</b>
 * depend on the grpc-java {@code io.grpc.Status} types so it can serve as a standalone reference
 * (for example, to populate a UI table that explains a {@code grpc-status} value) in modules that
 * do not otherwise link against grpc-java.
 *
 * <p>Each {@link Code} pairs the canonical number and name with a short human description and the
 * conventional HTTP status used by grpc-gateway / the gRPC HTTP mapping.
 */
public final class GrpcStatusCodes {

    /**
     * The canonical gRPC status codes, declared in numeric order 0..16. The enum ordinal equals
     * the wire number for every constant, which the registry relies on for {@link #byNumber(int)}.
     */
    public enum Code {
        OK(200, "Not an error; returned on success."),
        CANCELLED(499, "The operation was cancelled, typically by the caller."),
        UNKNOWN(500, "Unknown error, e.g. a status value received from an unknown error space."),
        INVALID_ARGUMENT(400, "The client specified an invalid argument, independent of system state."),
        DEADLINE_EXCEEDED(504, "The deadline expired before the operation could complete."),
        NOT_FOUND(404, "A requested entity (e.g. file or directory) was not found."),
        ALREADY_EXISTS(409, "The entity a client attempted to create already exists."),
        PERMISSION_DENIED(403, "The caller does not have permission to execute the operation."),
        RESOURCE_EXHAUSTED(429, "A resource has been exhausted, e.g. a quota or the file system is out of space."),
        FAILED_PRECONDITION(400, "The operation was rejected because the system is not in the required state."),
        ABORTED(409, "The operation was aborted, typically due to a concurrency issue such as a conflict."),
        OUT_OF_RANGE(400, "The operation was attempted past the valid range."),
        UNIMPLEMENTED(501, "The operation is not implemented or not supported/enabled in this service."),
        INTERNAL(500, "Internal error; an invariant expected by the underlying system was broken."),
        UNAVAILABLE(503, "The service is currently unavailable; the caller may retry with backoff."),
        DATA_LOSS(500, "Unrecoverable data loss or corruption."),
        UNAUTHENTICATED(401, "The request does not have valid authentication credentials for the operation.");

        private final int number;
        private final int httpStatus;
        private final String description;

        Code(int httpStatus, String description) {
            this.number = ordinal();
            this.httpStatus = httpStatus;
            this.description = description;
        }

        /** The canonical gRPC status number (0..16), equal to the enum ordinal. */
        public int number() { return number; }

        /** The canonical status name, e.g. {@code UNAVAILABLE}. */
        public String statusName() { return name(); }

        /** The conventional HTTP status for this code per the gRPC HTTP mapping. */
        public int httpStatus() { return httpStatus; }

        /** A short, non-empty human description of what this code means. */
        public String description() { return description; }
    }

    /** All codes in numeric order 0..16 as an immutable list. */
    public static final List<Code> ALL = List.of(Code.values());

    /** Index from lower-cased name to code for case-insensitive lookup. */
    private static final Map<String, Code> BY_NAME = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(c -> c.name().toLowerCase(Locale.ROOT), c -> c));

    /** The lowest valid status number. */
    public static final int MIN_NUMBER = 0;
    /** The highest valid status number. */
    public static final int MAX_NUMBER = Code.values().length - 1;

    private GrpcStatusCodes() {
    }

    /** All 17 codes in numeric order 0..16. */
    public static List<Code> all() {
        return ALL;
    }

    /**
     * Returns the code for a wire number.
     *
     * @param number a status number
     * @return the matching {@link Code}
     * @throws IllegalArgumentException if {@code number} is outside 0..16
     */
    public static Code byNumber(int number) {
        if (number < MIN_NUMBER || number > MAX_NUMBER) {
            throw new IllegalArgumentException("gRPC status number out of range [0.." + MAX_NUMBER + "]: " + number);
        }
        return ALL.get(number);
    }

    /**
     * Returns the code for a wire number, or empty if out of range — the non-throwing companion to
     * {@link #byNumber(int)}.
     */
    public static Optional<Code> findByNumber(int number) {
        if (number < MIN_NUMBER || number > MAX_NUMBER) {
            return Optional.empty();
        }
        return Optional.of(ALL.get(number));
    }

    /**
     * Looks up a code by name, case-insensitively (e.g. {@code "UNAVAILABLE"}, {@code "unavailable"}).
     *
     * @param name a status name; may be {@code null}
     * @return the matching code, or empty if the name is null/unknown
     */
    public static Optional<Code> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns the canonical name for a wire number.
     *
     * @throws IllegalArgumentException if {@code number} is outside 0..16
     */
    public static String name(int number) {
        return byNumber(number).name();
    }

    /**
     * Returns the wire number for a status name (case-insensitive).
     *
     * @throws IllegalArgumentException if the name is null or unknown
     */
    public static int number(String name) {
        return byName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gRPC status name: " + name))
                .number();
    }

    /**
     * Returns the short human description for a wire number.
     *
     * @throws IllegalArgumentException if {@code number} is outside 0..16
     */
    public static String description(int number) {
        return byNumber(number).description();
    }

    /**
     * Returns the conventional HTTP status for a wire number per the gRPC HTTP mapping.
     *
     * @throws IllegalArgumentException if {@code number} is outside 0..16
     */
    public static int httpStatus(int number) {
        return byNumber(number).httpStatus();
    }
}
