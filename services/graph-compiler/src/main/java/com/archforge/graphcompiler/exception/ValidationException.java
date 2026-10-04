package com.archforge.graphcompiler.exception;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when a topology cannot be compiled.
 *
 * <p>Carries structured findings rather than a single message, because the frontend
 * needs to attribute each message to the field that caused it. A flat string forces
 * the client to parse prose to work out which node is at fault — which is why the
 * previous response could only say "3 error(s)".
 *
 * <p>Findings are defensively copied. The exception outlives the validation pass and
 * travels to an HTTP response; holding a live reference would let a validator still
 * running on another thread mutate a list a caller is reading.
 */
@Getter
public class ValidationException extends RuntimeException {

    private final transient List<String> errors;

    /** Wraps plain messages, for callers that have no field attribution. */
    public ValidationException(List<String> messages) {
        super("Topology validation failed with " + messages.size() + " error(s)");
        this.findings = messages.stream()
                .map(message -> new ValidationFinding(null, null, message))
                .toList();
        this.errors = List.copyOf(messages);
    }

    /**
     * Wraps structured findings, preserving field and code.
     *
     * <p>Named {@code fromFindings} rather than offering an overload taking
     * {@code List<ValidationFinding>}: both parameter types erase to {@code List},
     * so two such constructors are a compile error, and a caller passing an empty
     * {@code List.of()} would silently get whichever overload the compiler picked.
     */
    public static ValidationException fromFindings(List<ValidationFinding> structured) {
        ValidationException exception = new ValidationException(structured.isEmpty()
                ? List.of("Validation produced no findings")
                : structured.stream().map(ValidationFinding::message).toList());
        exception.findings = List.copyOf(structured);
        return exception;
    }

    /** Mutable only so {@link #fromFindings} can install the structured list. */
    private transient List<ValidationFinding> findings;

    public ValidationException(String message) {
        super(message);
        this.findings = List.of(new ValidationFinding(null, null, message));
        this.errors = List.of(message);
    }

    /**
     * One problem, attributed to the part of the payload that caused it.
     *
     * @param field   dotted or indexed path, e.g. {@code nodes[3].type}; may be null
     * @param code    stable machine-readable code, e.g. {@code CYCLE_DETECTED}; may be null
     * @param message human-readable explanation
     */
    public record ValidationFinding(String field, String code, String message) {
    }

    public List<ValidationFinding> blockingFindings() {
        return findings;
    }
}
