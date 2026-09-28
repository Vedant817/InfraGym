package com.archforge.graphcompiler.validation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ValidationResult {

    private final List<ValidationError> errors = new ArrayList<>();

    public void addError(ValidationError error) {
        errors.add(error);
    }

    public void addError(String field, String code, String message, ValidationError.Severity severity) {
        errors.add(ValidationError.builder()
                .field(field)
                .code(code)
                .message(message)
                .severity(severity)
                .build());
    }

    public boolean hasErrors() {
        return errors.stream().anyMatch(e -> e.getSeverity() == ValidationError.Severity.ERROR);
    }

    public boolean hasWarnings() {
        return errors.stream().anyMatch(e -> e.getSeverity() == ValidationError.Severity.WARNING);
    }

    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }

    public List<String> getErrorMessages() {
        return errors.stream()
                .filter(e -> e.getSeverity() == ValidationError.Severity.ERROR)
                .map(ValidationError::getMessage)
                .toList();
    }

    public static ValidationResult success() {
        return new ValidationResult();
    }
}
