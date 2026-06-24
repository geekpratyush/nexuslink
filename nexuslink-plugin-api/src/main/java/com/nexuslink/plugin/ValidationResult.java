package com.nexuslink.plugin;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors) {

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult fail(String field, String message) {
        return new ValidationResult(false, List.of(new ValidationError(field, message)));
    }

    public static ValidationResult fail(List<ValidationError> errors) {
        return new ValidationResult(false, errors);
    }

    public record ValidationError(String field, String message) {}
}
