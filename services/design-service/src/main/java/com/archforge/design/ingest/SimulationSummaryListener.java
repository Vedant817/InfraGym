package com.archforge.design.ingest;

import com.archforge.contracts.event.AggregatedMetricEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.design.domain.NodeMetric;
import com.archforge.design.domain.SimulationEntity;
import com.archforge.design.repository.NodeMetricRepository;
import com.archforge.design.repository.SimulationRepository;
import com.archforge.design.repository.SubmissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records a completed run: its totals, and every component's and window's figures.
 *
 * <p>This is where the measurements a reader will judge the design on are stored, so the
 * two things that matter most here are that nothing is lost and nothing is counted twice.
 *
 * <p>Nothing is lost: the engine's own caveats are persisted rather than logged. A summary
 * computed from an incomplete trace, or one whose totals do not reconcile, is stored with the
 * fact attached -- because a stored 56/100 with no record that 40% of the trace was missing
 * is a number nobody can safely act on, however true it happened to be.
 *
 * <p>Nothing is counted twice: every metric row is an {@code ON CONFLICT} upsert, so a
 * redelivered summary replaces rather than appends. A chart that counted each component
 * twice would look like a design with more of everything, which is not a wrong number
 * anyone would catch.
 */
@Slf4j
@Component
public class SimulationSummaryListener {

    private final SimulationRepository simulations;
    private final SubmissionRepository submissions;
    private final NodeMetricRepository metrics;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SimulationSummaryListener(SimulationRepository simulations,
                                     SubmissionRepository submissions,
                                     NodeMetricRepository metrics,
                                     ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry) {
        this.simulations = simulations;
        this.submissions = submissions;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${archforge.kafka.topics.aggregated-summary:aggregated.summary}",
            groupId = "${archforge.design.consumer-group:design-service-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory"
    )
    @Transactional
    public void onSummary(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        SimulationSummaryEvent summary;
        try {
            summary = objectMapper.readValue(payload, SimulationSummaryEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("design.summary.unreadable").increment();
            throw new IllegalArgumentException(
                    "Unreadable simulation summary: " + e.getMessage(), e);
        }
        if (summary == null || summary.simulationId() == null) {
            meterRegistry.counter("design.summary.unidentified").increment();
            throw new IllegalArgumentException("Simulation summary carries no simulationId");
        }
        apply(summary);
    }

    void apply(SimulationSummaryEvent summary) {
        UUID simulationId = parseUuid(summary.simulationId());
        UUID submissionId = parseUuid(summary.submissionId());
        var submission = submissionId == null ? null
                : submissions.findByIdForUpdate(submissionId).orElse(null);
        if (submission == null || simulationId == null) {
            meterRegistry.counter("design.summary.orphan").increment();
            log.warn("Simulation summary {} has no submission to attach to", simulationId);
            return;
        }

        if (!submission.attachSimulation(simulationId)) {
            throw new IllegalArgumentException("Summary simulation does not match the submission's simulation");
        }
        SimulationEntity simulation = simulations.findById(simulationId)
                .orElseGet(() -> SimulationEntity.of(simulationId, submission.getId(), summary.scenario()));
        SimulationSummaryEvent.Totals totals = summary.totals();
        double offered = totals == null ? 0 : totals.totalRequests();
        double failed = totals == null ? 0 : totals.totalErrors();
        double queued = totals == null ? 0 : totals.backloggedRequests();

        simulation.apply(
                statusOf(summary), summary.tickCount(), summary.totalTicks(), summary.tickMillis(),
                offered,
                // Served is what the identity leaves over. Stored rather than trusted from a
                // separate field, because a summary that published offered, failed and queued
                // without a consistent served is a summary whose own arithmetic disagrees,
                // and reconciling here makes that visible rather than propagating it.
                Math.max(0, offered - failed - queued),
                failed, queued,
                json(summary.warnings()), json(summary.graphMetrics()),
                summary.startedAt(), summary.completedAt());
        // JDBC metric batches use this transaction's connection but do not cause
        // Hibernate to flush. Insert the parent before their foreign-key checks.
        simulations.saveAndFlush(simulation);
        submission.transitionTo(com.archforge.contracts.lifecycle.SubmissionStatus.AGGREGATING);
        submissions.save(submission);

        List<NodeMetric> nodeRows = summary.nodeSummaries().stream()
                .map(node -> NodeMetric.of(simulationId, node))
                .toList();
        metrics.upsertAll(nodeRows);

        List<NodeMetric.WindowMetric> windows = summary.windowAggregates().stream()
                .map(aggregate -> NodeMetric.WindowMetric.of(simulationId, aggregate))
                .toList();
        metrics.upsertWindows(windows);

        meterRegistry.counter("design.summary.recorded").increment();
        meterRegistry.counter("design.summary.windows", "count",
                windows.size() > 10_000 ? "large" : "normal").increment(windows.size());

        if (!simulation.reconciles() && offered > 0) {
            // Not fatal, and deliberately not a rejection. A run whose totals disagree has
            // still been measured, and discarding it would leave the user with nothing at all
            // -- which is a worse answer than a flagged one. The discrepancy is recorded so
            // the reader knows the completion rate cannot be trusted.
            meterRegistry.counter("design.summary.non_reconciling").increment();
            log.warn("Simulation {} does not reconcile: offered {} served {} failed {} queued {}. "
                            + "Its figures are stored, but the completion rate is unreliable.",
                    simulationId, (long) offered,
                    (long) Math.max(0, offered - failed - queued), (long) failed, (long) queued);
        }
        if (simulation.truncated()) {
            meterRegistry.counter("design.summary.truncated").increment();
            log.warn("Simulation {} executed {} of {} ticks; the later workload phases never ran",
                    simulationId, simulation.getTickCount(), simulation.getTotalTicks());
        }
        if (!simulation.getWarnings().isBlank()
                && !List.of("[]", "null").contains(simulation.getWarnings())) {
            meterRegistry.counter("design.summary.incomplete_trace").increment();
        }
    }

    /**
     * The run's status as the reader sees it.
     *
     * <p>Derived from the summary rather than taken from a field, because the summary has no
     * status field and the honest answer is "completed" with the caveats attached -- which is
     * a different statement from "completed cleanly", and the reader needs to be able to make
     * that distinction from what is stored.
     */
    private static String statusOf(SimulationSummaryEvent summary) {
        boolean truncated = summary.totalTicks() > 0 && summary.tickCount() < summary.totalTicks();
        boolean incomplete = summary.warnings().stream().anyMatch(warning ->
                warning != null && (warning.toLowerCase(java.util.Locale.ROOT).contains("incomplete")
                        || warning.contains("partial")));
        if (truncated || incomplete) {
            return "completed_with_caveats";
        }
        return "completed";
    }

    /** Groups window aggregates for a reader who wants one component's series. */
    static List<AggregatedMetricEvent> seriesFor(SimulationSummaryEvent summary, String nodeId,
                                                 String metricName) {
        List<AggregatedMetricEvent> series = new ArrayList<>();
        for (AggregatedMetricEvent aggregate : summary.windowAggregates()) {
            if (aggregate.nodeId().equals(nodeId) && aggregate.metricName().equals(metricName)) {
                series.add(aggregate);
            }
        }
        return series;
    }

    /** The contract carries ids as strings; the schema stores them as UUIDs. */
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
