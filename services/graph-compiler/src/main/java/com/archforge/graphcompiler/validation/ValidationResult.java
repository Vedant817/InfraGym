package com.archforge.graphcompiler.validation;

import lombok.Getter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Accumulates validation findings.
 *
 * <p>Only ever added to from the validating thread, so a plain list with a
 * synchronised accessor is sufficient and does not pay the copy-on-write cost on
 * every append. The previous version exposed its internal {@link CopyOnWriteArrayList}
 * through a Lombok {@code @Getter}, so a caller holding the returned reference saw it
 * grow underneath them as validation continued.
 */
@Getter
public class ValidationResult {

    private final List<ValidationError> errors = new CopyOnWriteArrayList<>();

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

    /** Any finding that should block compilation. */
    public boolean hasErrors() {
        return errors.stream().anyMatch(e -> e.getSeverity() == ValidationError.Severity.ERROR);
    }

    public boolean hasWarnings() {
        return errors.stream().anyMatch(e -> e.getSeverity() == ValidationError.Severity.WARNING);
    }

    /**
     * A snapshot of the blocking findings.
     *
     * <p>Returns an immutable copy rather than a live view: the exception that
     * carries these messages is constructed and then passed around, and a live list
     * could grow after validation had nominally finished.
     */
    public List<String> getErrorMessages() {
        return errors.stream()
                .filter(e -> e.getSeverity() == ValidationError.Severity.ERROR)
                .map(ValidationError::getMessage)
                .toList();
    }

    /** A snapshot of the non-blocking findings. */
    public List<String> getWarningMessages() {
        return errors.stream()
                .filter(e -> e.getSeverity() == ValidationError.Severity.WARNING)
                .map(ValidationError::getMessage)
                .toList();
    }

    /** Immutable snapshot of every finding. */
    public List<ValidationError> getFindings() {
        return List.copyOf(errors);
    }

    public int errorCount() {
        return (int) errors.stream().filter(e -> e.getSeverity() == ValidationError.Severity.ERROR).count();
    }

    public int warningCount() {
        return (int) errors.stream().filter(e -> e.getSeverity() == ValidationError.Severity.WARNING).count();
    }

    public static ValidationResult success() {
        return new ValidationResult();
    }
}
