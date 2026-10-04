package com.archforge.simulation.engine;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.simulation.failure.FailureInjector;
import com.archforge.simulation.workload.WorkloadProfile;

import java.time.Duration;
import java.time.Instant;
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
 * @param totalFailed     requests that failed at a terminal component
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

    public Duration wallClock() {
        return Duration.between(startedAt, completedAt);
    }

    /** Fraction of offered load that completed, over the whole run. */
    public double completionRate() {
        if (totalOffered <= 0) {
            return 0;
        }
        return Math.min(1.0, totalServed / totalOffered);
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
                totalOffered - totalServed - totalFailed,
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
