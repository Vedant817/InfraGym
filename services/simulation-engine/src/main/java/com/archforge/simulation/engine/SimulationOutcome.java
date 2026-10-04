package com.archforge.simulation.engine;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.simulation.failure.FailureInjector;
import com.archforge.simulation.workload.WorkloadProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one completed run produced.
 *
 * <p>Deliberately a plain value with no Kafka or Spring types in it: the simulation is
 * a pure function of (graph, scenario, seed, duration), so it can be asserted on
 * directly in a test and the transport is a separate concern.
 *
 * @param simulationId    run identifier
 * @param submissionId    submission this run belongs to
 * @param correlationId   pipeline correlation id, carried end to end
 * @param scenario        the scenario that was executed
 * @param seed            the seed every random draw came from
 * @param nominalRps      the graph's own sustainable capacity
 * @param tickCount       ticks executed
 * @param tickMillis      simulated time per tick
 * @param nodeReports     per-component results
 * @param failures        the failure plan as executed
 * @param warnings        routing and configuration problems found while running
 * @param totalOffered    requests offered at the entry points
 * @param totalServed     requests that reached a terminal component
 * @param totalFailed     requests lost at any component, mid-graph included
 * @param residualBacklog requests still queued when the run ended
 * @param graph           the DAG that was simulated
 * @param workload        the phases that were offered
 * @param startedAt       wall-clock start
 * @param completedAt     wall-clock end
 */
public record SimulationOutcome(
        String simulationId,
        String submissionId,
        String correlationId,
        ScenarioType scenario,
        long seed,
        double nominalRps,
        long tickCount,
        long tickMillis,
        List<NodeReport> nodeReports,
        List<FailureInjector.Failure> failures,
        List<String> warnings,
        double totalOffered,
        double totalServed,
        double totalFailed,
        double residualBacklog,
        CompiledGraph graph,
        WorkloadProfile workload,
        Instant startedAt,
        Instant completedAt) {

    public SimulationOutcome {
        nodeReports = nodeReports == null ? List.of() : List.copyOf(nodeReports);
        failures = failures == null ? List.of() : List.copyOf(failures);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        scenario = scenario == null ? ScenarioType.NORMAL : scenario;
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (completedAt == null) {
            completedAt = startedAt;
        }
    }

    /**
 * A copy of this outcome with one more warning.
 *
 * <p>Records are immutable, so a run that discovers part-way through that its telemetry
 * was dropped cannot mutate the outcome it is building. It copies instead — so the
 * incompleteness lands in the same record a reader would query, rather than living only
 * in a log line that nobody reads at the moment they need it.
     */
    public SimulationOutcome withWarning(String warning) {
        List<String> combined = new ArrayList<>(warnings);
        combined.add(warning);
        return new SimulationOutcome(simulationId, submissionId, correlationId, scenario, seed,
                nominalRps, tickCount, tickMillis, nodeReports, failures, List.copyOf(combined),
                totalOffered, totalServed, totalFailed, residualBacklog,
                graph, workload, startedAt, completedAt);
    }

    public Duration wallClock() {
        return Duration.between(startedAt, completedAt);
    }

    /**
     * Whether offered, served, failed and queued account for every request.
     *
     * <p>{@code offered == served + failed + backloged} is the identity that makes the
     * completion rate mean anything. When it does not hold, one of the three is being
     * counted at the wrong place, and a reader has no way to tell which.
     */
    public boolean reconciles() {
        return Math.abs(totalOffered - (totalServed + totalFailed + residualBacklog)) <= 1.0;
    }

    /**
     * Fraction of offered load that completed, over the whole run.
     *
     * <p>Unclamped, deliberately. It was {@code Math.min(1.0, ...)}, which hid the
     * fan-out bug where every branch of a scatter received the upstream's entire load
     * and a run reported serving twice what it was offered. A completion rate above 1 is
     * not a number to tidy away — it is the signal that the accounting is wrong, and
     * clamping it meant the symptom never reached anyone who could fix it.
     */
    public double completionRate() {
        if (totalOffered <= 0) {
            return 0;
        }
        return totalServed / totalOffered;
    }

    /**
     * Worst P99 across all components: the single number a UI headlines.
     *
     * <p>Worst rather than mean, because a mean over components hides exactly the
     * one component that is failing the user. Also the number the README's worked
     * example is built around, so it has to mean the same thing here.
     */
    public double worstNodeP99Ms() {
        return nodeReports.stream().mapToDouble(NodeReport::p99LatencyMs).max().orElse(0);
    }

    /** Slowest single observation in the run. */
    public double worstLatencyMs() {
        return nodeReports.stream().mapToDouble(NodeReport::maxLatencyMs).max().orElse(0);
    }

    public NodeReport node(String nodeId) {
        return nodeReports.stream()
                .filter(report -> report.nodeId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    /** Components with something worth telling the user about, worst first. */
    public List<NodeReport> nodesWithFindings() {
        return nodeReports.stream()
                .filter(report -> !report.findings().isEmpty())
                .sorted((a, b) -> Double.compare(severity(b), severity(a)))
                .toList();
    }

    /**
     * How badly a component misbehaved, for ordering findings.
     *
     * <p>Latency dominates and error rate breaks ties, scaled to the same magnitude so
     * a 1% error rate competes with a few hundred milliseconds rather than being
     * numerically invisible next to it.
     */
    private static double severity(NodeReport report) {
        return Math.max(0, report.maxLatencyMs()) + Math.max(0, report.peakErrorRate()) * 1000;
    }

    /**
     * Projects this outcome into the shared summary contract.
     *
     * <p>The aggregator's version of this is authoritative — it is built from the wire
     * stream and includes per-window detail the engine does not retain. This
     * projection exists so the engine's own numbers can be published, diffed against
     * the aggregator's, and used as the fallback when the aggregator has not yet
     * finished when a client asks.
     */
    public SimulationSummaryEvent toSummaryEvent() {
        List<SimulationSummaryEvent.FailureRecord> records = failures.stream()
                .map(failure -> {
                    NodeReport report = node(failure.nodeId());
                    return new SimulationSummaryEvent.FailureRecord(
                            failure.nodeId(),
                            failure.nodeType(),
                            failure.failureMode(),
                            failure.startTick(),
                            failure.endTick(),
                            report != null && report.failureTicks() > 0,
                            report == null ? 0 : (long) Math.max(0, report.recoveryTimeMs()),
                            List.copyOf(failure.affectedNodeIds()),
                            report == null ? 0 : report.maxLatencyMs(),
                            report == null ? 0 : report.peakErrorRate());
                })
                .toList();

        List<SimulationSummaryEvent.NodeSummary> summaries = nodeReports.stream()
                .map(report -> new SimulationSummaryEvent.NodeSummary(
                        report.nodeId(),
                        report.nodeType(),
                        report.label(),
                        report.p50LatencyMs(),
                        report.p95LatencyMs(),
                        report.p99LatencyMs(),
                        report.maxLatencyMs(),
                        report.avgErrorRate(),
                        report.peakErrorRate(),
                        report.peakThroughputRps(),
                        report.avgThroughputRps(),
                        report.peakUtilization(),
                        report.avgCacheHitRate(),
                        (long) report.peakQueueDepth(),
                        report.wasFailureTarget(),
                        report.saturated(),
                        report.sampleCount()))
                .toList();

        SimulationSummaryEvent.Totals totals = new SimulationSummaryEvent.Totals(
                nodeReports.stream().mapToDouble(NodeReport::p50LatencyMs).max().orElse(0),
                nodeReports.stream().mapToDouble(NodeReport::p95LatencyMs).max().orElse(0),
                worstNodeP99Ms(),
                totalOffered > 0 ? totalFailed / totalOffered : 0,
                nodeReports.stream().mapToDouble(NodeReport::peakErrorRate).max().orElse(0),
                nodeReports.stream().mapToDouble(NodeReport::peakThroughputRps).max().orElse(0),
                tickCount > 0 ? totalOffered / (tickCount * tickMillis / 1000.0) : 0,
                totalServed + totalFailed,
                totalFailed,
                residualBacklog,
                (int) nodeReports.stream().filter(NodeReport::saturated).count(),
                nodeReports.stream()
                        .filter(report -> report.wasFailureTarget() && report.recoveryTimeMs() >= 0)
                        .mapToDouble(NodeReport::recoveryTimeMs)
                        .average()
                        .orElse(0));

        return new SimulationSummaryEvent(
                simulationId,
                submissionId,
                correlationId,
                scenario.getValue(),
                SimulationLifecycleEvent.ArchitectureDigest.of(graph),
                records,
                summaries,
                totals,
                List.of(),
                graph == null ? java.util.Map.of() : graph.getGraphMetadata(),
                startedAt,
                completedAt,
                tickCount,
                tickMillis);
    }
}
