package com.archforge.contracts.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

/** Lifecycle of one simulation run inside the simulation engine. */
public enum SimulationStatus {

    /** Task consumed, waiting for a worker slot. */
    QUEUED("queued", false),

    /** Executing ticks. */
    RUNNING("running", false),

    /** All ticks executed and telemetry flushed. */
    COMPLETED("completed", true),

    /** Aborted by an unrecoverable error. */
    FAILED("failed", true),

    /** Exceeded its wall-clock or tick budget. */
    TIMED_OUT("timed_out", true),

    /** Rejected as malformed before any tick ran. */
    REJECTED("rejected", true);

    private final String value;
    private final boolean terminal;

    SimulationStatus(String value, boolean terminal) {
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

    @JsonCreator
    public static SimulationStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return QUEUED;
        }
        String needle = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(s -> s.value.equals(needle) || s.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulation status '" + value + "'"));
    }

    public static SimulationStatus findByValue(String value) {
        try {
            return fromValue(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
