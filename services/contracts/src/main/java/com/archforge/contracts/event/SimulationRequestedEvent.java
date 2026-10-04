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

    /**
     * Tick granularity ceiling, in milliseconds.
     *
     * <p>Only to keep {@link #tickCount()} well-defined. A long tick makes a run
     * shorter rather than longer, so it is not a resource risk — but it does mean a
     * record could divide toward zero and ask for a run of no ticks at all.
     */
    public static final long MAX_TICK_MILLIS = 3_600_000L;

    /**
     * Ceiling on a requested run length, in seconds.
     *
     * <p>Two reasons, and the second is the important one. A real one: the simulator
     * bounds ticks per run anyway, so a longer request is only ever going to be
     * clamped. The arithmetic one: {@code durationSeconds * 1000} overflows
     * {@code long} above roughly 9.2e15 seconds, producing a <em>negative</em> tick
     * count that was published as {@code totalTicks} on the lifecycle event.
     */
    public static final long MAX_DURATION_SECONDS = 86_400L;

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
        // Clamped above as well as below. `tickCount()` computes
        // `durationSeconds * 1000 / tickMillis`, which overflows to a negative number
        // for a large declared duration -- and that negative value was then published
        // verbatim as `totalTicks` on the QUEUED and terminal lifecycle events, so a
        // consumer computing progress as tickCount/totalTicks divided by a negative
        // zero. The value arrives on the wire, so it is untrusted input.
        if (durationSeconds > MAX_DURATION_SECONDS) {
            durationSeconds = MAX_DURATION_SECONDS;
        }
        if (tickMillis < MIN_TICK_MILLIS) {
            tickMillis = MIN_TICK_MILLIS;
        }
        // Bounded as well as floored. An absurd tick length makes the run shorter rather than
        // longer, so it cannot be used to exhaust a worker; but the cap alone cannot keep
        // tickCount() above zero, which is what tickCount() itself now guarantees.
        if (tickMillis > MAX_TICK_MILLIS) {
            tickMillis = MAX_TICK_MILLIS;
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

    /**
     * How many ticks this task asks for.
     *
     * <p>Never zero. The duration and the tick length are both clamped in the constructor,
     * but clamping alone does not keep this non-zero: a legitimate 60-second run at a
     * 1-hour tick divides to {@code 60 * 1000 / 3600000 == 0}. That zero is published
     * verbatim as {@code totalTicks} on the QUEUED lifecycle event, so any consumer
     * computing progress as {@code tickCount / totalTicks} divided by a known zero —
     * exactly the defect the clamp was added to stop. The floor belongs here, on the
     * value itself, not on each of its callers.
     */
    public long tickCount() {
        return Math.max(1L, durationSeconds * 1000L / tickMillis);
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
