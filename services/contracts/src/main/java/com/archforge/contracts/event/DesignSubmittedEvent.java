package com.archforge.contracts.event;

import com.archforge.contracts.canvas.Topology;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * A design submitted for evaluation. Produced by the API gateway on
 * {@code design.submissions}, consumed by the graph compiler and (to record the
 * submission row) by the design service.
 *
 * <p>Carries the scenario explicitly rather than letting the compiler guess:
 * "run this same topology against a flash sale" is a different job from
 * "compile this", and the previous design where {@code scenarioType} existed on
 * one side of the wire and not the other silently degraded every run to
 * {@code NORMAL}.
 *
 * <p>{@code correlationId} is threaded through compilation, simulation and
 * evaluation so one user action can be traced end to end across four services.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DesignSubmittedEvent(
        String eventId,
        String correlationId,
        String submissionId,
        String designId,
        String userId,
        String sessionId,
        String scenario,
        String scenarioNotes,
        Topology topology,
        Instant submittedAt) {

    /** Wire contract version. Bump on an incompatible change. */
    public static final String SCHEMA_VERSION = "1.0";

    public DesignSubmittedEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        if (correlationId == null) {
            correlationId = eventId;
        }
        if (submissionId == null) {
            submissionId = UUID.randomUUID().toString();
        }
        if (submittedAt == null) {
            submittedAt = Instant.now();
        }
        if (topology == null) {
            topology = Topology.empty();
        }
        if (scenario == null || scenario.isBlank()) {
            scenario = ScenarioType.NORMAL.getValue();
        }
    }

    /** Resolves {@code scenario}, falling back to {@code NORMAL} for junk values. */
    public ScenarioType scenarioType() {
        return ScenarioType.findByValue(scenario) == null
                ? ScenarioType.NORMAL
                : ScenarioType.fromValue(scenario);
    }

    public static DesignSubmittedEvent create(String userId,
                                              String sessionId,
                                              String designId,
                                              Topology topology,
                                              ScenarioType scenario,
                                              String scenarioNotes) {
        return new DesignSubmittedEvent(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                designId,
                userId,
                sessionId,
                scenario == null ? ScenarioType.NORMAL.getValue() : scenario.getValue(),
                scenarioNotes,
                topology,
                Instant.now());
    }
}
