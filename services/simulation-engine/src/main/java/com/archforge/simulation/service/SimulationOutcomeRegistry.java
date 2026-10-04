package com.archforge.simulation.service;

import com.archforge.contracts.lifecycle.SimulationStatus;
import com.archforge.simulation.config.SimulationProperties;
import com.archforge.simulation.engine.SimulationOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded, in-memory index of recent runs, for the REST status endpoint.
 *
 * <p><b>Not a store of record.</b> The design service holds the durable submission and
 * simulation rows. This exists so an operator can ask "what happened to simulation X"
 * without a database round trip, and so a test can assert on a run it just executed.
 * Retention is bounded by count and evicted least-recently-started first, so a
 * long-lived process cannot accumulate outcomes forever — each of which holds a full
 * latency distribution per node.
 *
 * <p>Bounded by count rather than by time on purpose: eviction by age needs a clock,
 * and a clock is exactly what makes a cache test flaky.
 */
@Slf4j
@Component
public class SimulationOutcomeRegistry {

    /** What is known about a run that did not complete. */
    public record Failure(String simulationId,
                          String submissionId,
                          SimulationStatus status,
                          String message,
                          Instant failedAt) {
    }

    private final Map<String, SimulationOutcome> outcomes = new LinkedHashMap<>();
    private final Map<String, Failure> failures = new LinkedHashMap<>();
    private final SimulationProperties properties;

    public SimulationOutcomeRegistry(SimulationProperties properties) {
        this.properties = properties;
    }

    /**
     * Records a completed run.
     *
     * <p>Synchronised because runs finish on worker threads and REST reads arrive on
     * request threads. The critical section is a map put, so the contention is
     * irrelevant next to the simulation itself — this is here for correctness, not for
     * throughput.
     */
    public synchronized void record(SimulationOutcome outcome) {
        if (outcome == null || outcome.simulationId() == null) {
            return;
        }
        failures.remove(outcome.simulationId());
        outcomes.put(outcome.simulationId(), outcome);
        evict();
    }

    public synchronized void recordFailure(String simulationId,
                                           String submissionId,
                                           SimulationStatus status,
                                           String message) {
        if (simulationId == null) {
            return;
        }
        outcomes.remove(simulationId);
        failures.put(simulationId, new Failure(simulationId, submissionId, status, message, Instant.now()));
        evict();
    }

    public synchronized Optional<SimulationOutcome> find(String simulationId) {
        return Optional.ofNullable(outcomes.get(simulationId));
    }

    public synchronized Optional<Failure> findFailure(String simulationId) {
        return Optional.ofNullable(failures.get(simulationId));
    }

    /** Newest first. */
    public synchronized List<SimulationOutcome> recent(int limit) {
        List<SimulationOutcome> all = new java.util.ArrayList<>(outcomes.values());
        java.util.Collections.reverse(all);
        int bounded = Math.max(1, Math.min(limit, all.size()));
        return List.copyOf(all.subList(0, bounded));
    }

    public synchronized int size() {
        return outcomes.size() + failures.size();
    }

    /**
     * Drops the oldest entries until the registry is within its bound.
     *
     * <p>{@link LinkedHashMap} iterates in insertion order, so the first key is the
     * oldest — which for a run is the one that started earliest, and is the one least
     * likely to still be being asked about.
     */
    private void evict() {
        int limit = Math.max(1, properties.getRetainedRuns());
        while (size() > limit) {
            String oldest = outcomes.isEmpty()
                    ? failures.keySet().iterator().next()
                    : outcomes.keySet().iterator().next();
            outcomes.remove(oldest);
            failures.remove(oldest);
        }
    }
}
