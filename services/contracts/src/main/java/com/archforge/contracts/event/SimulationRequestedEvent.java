package com.archforge.contracts.event;

import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A compiled DAG plus everything the simulation engine needs to run it
 * deterministically. Produced by the graph compiler on {@code simulation.tasks}.
 *
 * <p>The three fields that make a run reproducible live here rather than in
 * engine-side config:
 * <ul>
 *   <li>{@code seed} — seeds every random draw in workload sampling, service-time
 *       jitter and failure-site selection, so the same submission produces the
 *       same numbers on a re-run. Without this the platform reports a different
 *       P99 every time and the AI's feedback cannot be trusted.</li>
 *   <li>{@code durationSeconds} / {@code tickMillis} — the tick is the engine's
 *       integration step. Making it explicit (rather than hard-coded to one
 *       second) lets a scenario trade fidelity for wall-clock cost.</li>
 *   <li>{@code scenario} — resolved, not a string to be re-parsed at each hop.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulationRequestedEvent(
        String eventId,
        String correlationId,
        String simulationId,
        String submissionId,
        String userId,
        String sessionId,
        CompiledGraph graph,
        String scenario,
        long durationSeconds,
        long tickMillis,
        long seed,
        Map<String, Object> workloadOverrides,
        Instant createdAt) {

    public static final String SCHEMA_VERSION = "1.0";

    /** Tick granularity floor: finer than 100 ms only inflates cost, not fidelity. */
    public static final long MIN_TICK_MILLIS = 100L;

    public SimulationRequestedEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        if (correlationId == null) {
            correlationId = eventId;
        }
        if (simulationId == null) {
            simulationId = UUID.randomUUID().toString();
        }
        if (scenario == null || scenario.isBlank()) {
            scenario = ScenarioType.NORMAL.getValue();
        }
        if (durationSeconds <= 0) {
            durationSeconds = 60;
        }
        if (tickMillis < MIN_TICK_MILLIS) {
            tickMillis = MIN_TICK_MILLIS;
        }
        if (seed == 0) {
            seed = UUID.randomUUID().getMostSignificantBits();
        }
        if (workloadOverrides == null) {
            workloadOverrides = new LinkedHashMap<>();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public ScenarioType scenarioType() {
        ScenarioType resolved = ScenarioType.findByValue(scenario);
        return resolved == null ? ScenarioType.NORMAL : resolved;
    }

    public long tickCount() {
        return durationSeconds * 1000L / tickMillis;
    }

    public static SimulationRequestedEvent from(DesignSubmittedEvent submission,
                                                CompiledGraph graph,
                                                ScenarioType scenario,
                                                long durationSeconds,
                                                long tickMillis,
                                                long seed) {
        return new SimulationRequestedEvent(
                UUID.randomUUID().toString(),
                submission.correlationId(),
                UUID.randomUUID().toString(),
                submission.submissionId(),
                submission.userId(),
                submission.sessionId(),
                graph,
                (scenario == null ? ScenarioType.NORMAL : scenario).getValue(),
                durationSeconds,
                tickMillis,
                seed,
                Map.of(),
                Instant.now());
    }
}
