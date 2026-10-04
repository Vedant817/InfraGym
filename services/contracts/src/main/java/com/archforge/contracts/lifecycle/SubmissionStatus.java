package com.archforge.contracts.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Lifecycle of a single submission through the pipeline.
 *
 * <p>The user polls this, so it is a strict forward-only state machine: the
 * design-service is the only writer, it only advances on real events consumed
 * from Kafka, and a submission that reaches a terminal state never leaves it.
 * {@link #canTransitionTo(SubmissionStatus)} encodes the legal edges so an
 * out-of-order Kafka delivery cannot rewind a submission to
 * {@code COMPILING} and make the UI flap.
 */
public enum SubmissionStatus {

    /** Accepted by the gateway and written to the database. */
    RECEIVED("received", false),

    /** Graph compiler is validating the topology. */
    VALIDATING("validating", false),

    /** Topology failed validation; the user must fix the canvas. */
    REJECTED("rejected", true),

    /** Validation passed; building the DAG. */
    COMPILING("compiling", false),

    /** DAG built; waiting for the simulation engine to pick up the task. */
    COMPILED("compiled", false),

    /** Simulation engine is executing the scenario. */
    SIMULATING("simulating", false),

    /** Simulation finished; telemetry is being windowed and aggregated. */
    AGGREGATING("aggregating", false),

    /** AI evaluator is producing architectural feedback. */
    EVALUATING("evaluating", false),

    /** Evaluation published; results are queryable. */
    COMPLETED("completed", true),

    /** Unrecoverable failure at any stage. */
    FAILED("failed", true);

    private final String value;
    private final boolean terminal;

    SubmissionStatus(String value, boolean terminal) {
        this.value = value;
        this.terminal = terminal;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** Whether the user can usefully retry the same design after this state. */
    public boolean requiresUserAction() {
        return this == REJECTED || this == FAILED;
    }

    /**
     * Legal forward edges. Terminal states accept nothing, so a late duplicate
     * event is a no-op rather than a regression.
     */
    public boolean canTransitionTo(SubmissionStatus next) {
        if (next == null || next == this) {
            return false;
        }
        if (this.terminal) {
            return false;
        }
        return switch (this) {
            case RECEIVED -> Set.of(VALIDATING, REJECTED, COMPILING, FAILED).contains(next);
            case VALIDATING -> Set.of(COMPILING, REJECTED, FAILED).contains(next);
            case COMPILING -> Set.of(COMPILED, REJECTED, FAILED).contains(next);
            case COMPILED -> Set.of(SIMULATING, FAILED).contains(next);
            case SIMULATING -> Set.of(AGGREGATING, FAILED).contains(next);
            case AGGREGATING -> Set.of(EVALUATING, COMPLETED, FAILED).contains(next);
            case EVALUATING -> Set.of(COMPLETED, FAILED).contains(next);
            default -> false;
        };
    }

    @JsonCreator
    public static SubmissionStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return RECEIVED;
        }
        String needle = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(s -> s.value.equals(needle) || s.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown submission status '" + value + "'. Valid: " + String.join(", ", allValues())));
    }

    public static SubmissionStatus findByValue(String value) {
        try {
            return fromValue(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static String[] allValues() {
        return Arrays.stream(values()).map(SubmissionStatus::getValue).toArray(String[]::new);
    }
}
