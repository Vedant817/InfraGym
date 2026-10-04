package com.archforge.ai.prompt;

import com.archforge.ai.analysis.ArchitectureAnalysis;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.contracts.lifecycle.MetricNames;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds what the model is shown.
 *
 * <p><b>What changed, and why it is the whole design.</b> The previous prompt was assembled
 * from a flat list of metric messages and rendered as:
 *
 * <pre>
 *   Architecture with 6 nodes:
 *   - Node db with metrics: [latency_ms, cpu_utilization]
 * </pre>
 *
 * <p>That is not an architecture. It is a list of identifiers, it contains no edges, no
 * component types, no capacities and no topology -- so the model was reasoning about a
 * system it could not see and inventing its structure in order to comment on it. A model
 * told "Node db has a metric called latency_ms" will confidently discuss database connection
 * pooling; it has not been told the database is in the request path at all.
 *
 * <p>So the prompt now carries the real graph from the summary's {@code ArchitectureDigest},
 * and the deterministic findings are presented as established facts rather than as something
 * to work out. The model is asked what the measurements <em>mean</em>, not what they are.
 *
 * <p>Every number is formatted with {@link Locale#ROOT}. In a locale using a comma decimal
 * separator, a P99 of 850.5 rendered as "850,5" is not a formatting nit inside a prompt:
 * it is a different number to anything downstream that parses it, including the model.
 */
public final class EvaluationPrompt {


    private EvaluationPrompt() {
    }

    /**
     * The system instruction.
     *
     * <p>States the ground rules explicitly: the measurements are already established, the
     * score is already computed, and disagreement is welcome only as new evidence. A model
     * asked to "evaluate" will produce an assessment whether or not there is anything to
     * assess; being told what is already settled is what stops it contradicting the
     * arithmetic without saying so.
     */
    public static String system() {
        return """
                You are a principal distributed-systems engineer reviewing a system design \
                that has just been through a discrete-event load simulation.

                You will be given the exact graph, the exact measurements from the run, and a \
                list of findings that have ALREADY been computed from those measurements.

                Rules you must follow:

                1. The measurements and the computed score are established fact. Never \
                contradict them. If you believe a conclusion is wrong, say what specific \
                measurement would support that, as a separate finding.
                2. The score has already been computed arithmetically from the thresholds. \
                Do NOT restate or revise it. Your job is to explain and to add context.
                3. Every finding you raise MUST cite a measurement from the run. A finding \
                with no evidence is a guess, and a guess in an engineering report is worse \
                than no finding.
                4. Prefer specifics. "The connection pool is likely undersized for a 1000 \
                rps peak with 150 ms p99" is useful. "Consider tuning your database" is not.
                5. If the simulation revealed nothing significant, say so plainly. \
                Manufacturing concern to appear thorough is a failure, not thoroughness.
                6. Only discuss components that appear in the graph you were given. Do not \
                invent components, and do not assume any component that is not listed.
                """;
    }

    /**
     * The user message: the graph, the measurements, the findings, and what to return.
     */
    public static String user(SimulationSummaryEvent summary,
                              ArchitectureAnalysis analysis,
                              List<ArchitectureEvaluationEvent.KnowledgeReference> references) {
        StringBuilder prompt = new StringBuilder(16_384);

        prompt.append("## 1. The architecture as submitted\n\n");
        prompt.append(architecture(summary)).append("\n");

        prompt.append("## 2. The workload\n\n");
        prompt.append(workload(summary)).append("\n");

        prompt.append("## 3. Measurements per component\n\n");
        prompt.append(measurements(summary)).append("\n");

        prompt.append("## 4. Latency over time (per tick window)\n\n");
        prompt.append(timeseries(summary)).append("\n");

        prompt.append("## 5. Injected failures and their measured effect\n\n");
        prompt.append(failures(summary)).append("\n");

        prompt.append("## 6. Findings already established from the measurements\n\n");
        prompt.append(computedFindings(analysis)).append("\n");

        prompt.append("## 7. Measured strengths\n\n");
        prompt.append(bulletList(analysis.strengths(), "None measured.")).append("\n");

        if (references != null && !references.isEmpty()) {
            prompt.append("## 8. Reference architectures retrieved from the knowledge base\n\n");
            prompt.append(bulletList(references.stream()
                    .map(reference -> String.format(Locale.ROOT, "%s (similarity %.2f): %s",
                            reference.title(), reference.similarity(), reference.snippet()))
                    .toList(), "None.")).append("\n");
            prompt.append("""
                    These are background references, not measurements. They may inform your \
                    reasoning but must never be cited as evidence about this run.
                    """).append("\n");
        }

        prompt.append("## What to return\n\n");
        prompt.append(returnContract(analysis));

        return prompt.toString();
    }

    // ----------------------------------------------------------------- the graph

    private static String architecture(SimulationSummaryEvent summary) {
        SimulationLifecycleEvent.ArchitectureDigest architecture = summary.architecture();
        if (architecture == null || architecture.nodes() == null || architecture.nodes().isEmpty()) {
            return "The architecture digest was not published with this run, so the component "
                    + "structure is unknown. Judge only from the measurements below and say that "
                    + "the structure could not be assessed.";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Design name: %s%n%n",
                architecture.name() == null ? "(unnamed)" : architecture.name()));
        out.append("Components:\n");
        for (SimulationLifecycleEvent.ArchitectureDigest.NodeDigest node : architecture.nodes()) {
            out.append(String.format(Locale.ROOT, "  - %-14s type=%-14s %s%n",
                    node.id(), node.type(), node.label()));
        }
        out.append(String.format(Locale.ROOT, "%nConnections (source -> target, link latency %.0f ms):%n",
                averageEdgeLatency(architecture)));
        for (SimulationLifecycleEvent.ArchitectureDigest.EdgeDigest edge : architecture.edges()) {
            out.append(String.format(Locale.ROOT, "  - %s -> %s (%.0f ms, %.0f Mbps)%n",
                    edge.source(), edge.target(), edge.latencyMs(), edge.bandwidthMbps()));
        }
        if (architecture.graphMetadata() != null && !architecture.graphMetadata().isEmpty()) {
            out.append("\nStructural metadata:\n");
            architecture.graphMetadata().forEach((key, value) ->
                    out.append(String.format(Locale.ROOT, "  - %s: %s%n", key, value)));
        }
        if (architecture.warnings() != null && !architecture.warnings().isEmpty()) {
            out.append("\nCompiler warnings about this design:\n");
            architecture.warnings().forEach(warning -> out.append("  - " + warning + "\n"));
        }
        return out.toString();
    }

    private static double averageEdgeLatency(SimulationLifecycleEvent.ArchitectureDigest architecture) {
        if (architecture.edges() == null || architecture.edges().isEmpty()) {
            return 0;
        }
        return architecture.edges().stream()
                .mapToDouble(SimulationLifecycleEvent.ArchitectureDigest.EdgeDigest::latencyMs)
                .average().orElse(0);
    }

    // --------------------------------------------------------------- the workload

    private static String workload(SimulationSummaryEvent summary) {
        SimulationSummaryEvent.Totals totals = summary.totals();
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Scenario: %s%n", summary.scenario()));
        out.append(String.format(Locale.ROOT, "Simulated duration: %d ticks of %d ms (%d s)%n",
                summary.tickCount(), summary.tickMillis(),
                summary.tickCount() * summary.tickMillis() / 1000));
        if (totals == null) {
            return out + "\nThe run published no totals.\n";
        }
        long offered = (long) totals.totalRequests();
        out.append(String.format(Locale.ROOT, "Requests offered: %d%n", offered));
        out.append(String.format(Locale.ROOT, "Offered throughput: %.1f rps average, %.1f rps peak%n",
                totals.offeredThroughputRps(), totals.peakThroughputRps()));
        out.append(String.format(Locale.ROOT, "Served: %d%n", served(totals)));
        out.append(String.format(Locale.ROOT, "Failed: %d (%.2f%% of offered)%n",
                (long) totals.totalErrors(), percentage(totals.totalErrors(), totals.totalRequests())));
        out.append(String.format(Locale.ROOT, "Still queued at end: %d (%.2f%% of offered)%n",
                (long) totals.backloggedRequests(),
                percentage(totals.backloggedRequests(), totals.totalRequests())));
        out.append(String.format(Locale.ROOT, "Components saturated: %d of %d%n",
                totals.saturatedNodeCount(), summary.nodeSummaries().size()));
        out.append("\nAccounting identity: offered == served + failed + queued -> ");
        out.append(Math.abs(offered - (served(totals) + (long) totals.totalErrors()
                + (long) totals.backloggedRequests())) <= 1
                ? "holds.\n"
                : String.format(Locale.ROOT, "DOES NOT HOLD (short by %d). The figures below are "
                        + "internally inconsistent, so treat conclusions with care.%n",
                        Math.abs(offered - (served(totals) + (long) totals.totalErrors()
                                + (long) totals.backloggedRequests()))));
        return out.toString();
    }

    private static long served(SimulationSummaryEvent.Totals totals) {
        // Served is the offered load that was neither rejected nor left queued. The
        // aggregator's own arithmetic in the prompt, so the reader can check it against the
        // identity above rather than taking a fourth number on trust.
        return Math.max(0, (long) totals.totalRequests() - (long) totals.totalErrors()
                - (long) totals.backloggedRequests());
    }

    // ------------------------------------------------------------ the measurements

    private static String measurements(SimulationSummaryEvent summary) {
        if (summary.nodeSummaries().isEmpty()) {
            return "No component reported any measurement. This run produced no usable data, and "
                    + "no architectural conclusion can be drawn from it.";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "%-14s %8s %8s %8s %8s %9s %8s %8s %6s %6s%n",
                "component", "p50 ms", "p95 ms", "p99 ms", "max ms", "peak rps",
                "err avg", "err peak", "util", "queue"));
        for (SimulationSummaryEvent.NodeSummary node : summary.nodeSummaries()) {
            out.append(String.format(Locale.ROOT,
                    "%-14s %8.1f %8.1f %8.1f %8.1f %9.1f %7.2f%% %7.2f%% %5.0f%% %6d%s%n",
                    node.nodeId(), node.p50LatencyMs(), node.p95LatencyMs(), node.p99LatencyMs(),
                    node.maxLatencyMs(), node.peakThroughputRps(),
                    node.avgErrorRate() * 100, node.peakErrorRate() * 100,
                    node.peakUtilization() * 100, node.peakQueueDepth(),
                    node.sampleCount() > 0 ? "" : "  [NO SAMPLES]"));
        }
        out.append("\nutil is peak offered load as a fraction of that component's capacity; "
                + "queue is the peak number of requests waiting.\n");
        out.append("\nNote the gap between p50 and p99. A component whose p99 is many times its "
                + "p50 has a tail problem -- a small fraction of requests are very slow -- and "
                + "its average latency will understate the problem. Where that gap is large, "
                + "say what could cause it.\n");
        return out.toString();
    }

    // --------------------------------------------------------------- the timeseries

    /**
     * The per-window latency series.
     *
     * <p>This is the part a summary cannot replace. A run-wide P99 of 850 ms and a run-wide
     * P99 of 40 ms that spiked to 850 ms for two windows have the same worst-case figure and
     * completely different diagnoses: one is uniformly slow, the other is a specific
     * component failing at peak load. Which of those a design is depends entirely on where
     * in the timeline it happened.
     */
    private static String timeseries(SimulationSummaryEvent summary) {
        if (summary.windowAggregates().isEmpty()) {
            return "No windowed aggregates were published for this run, so the shape of the "
                    + "load over time is unknown.";
        }
        Map<String, List<com.archforge.contracts.event.AggregatedMetricEvent>> latencyWindows =
                new java.util.LinkedHashMap<>();
        for (com.archforge.contracts.event.AggregatedMetricEvent aggregate : summary.windowAggregates()) {
            if (MetricNames.LATENCY_MS.equals(aggregate.metricName())) {
                latencyWindows.computeIfAbsent(aggregate.nodeId(), ignored -> new java.util.ArrayList<>())
                        .add(aggregate);
            }
        }
        if (latencyWindows.isEmpty()) {
            return "No per-window latency aggregates were published for this run.";
        }
        latencyWindows.values().forEach(windows -> windows.sort(null));

        StringBuilder out = new StringBuilder();
        out.append("P99 latency per component per tick window (window start tick in seconds "
                + "of simulated time):\n\n");
        for (var entry : latencyWindows.entrySet()) {
            out.append(String.format(Locale.ROOT, "  %s:%n", entry.getKey()));
            for (com.archforge.contracts.event.AggregatedMetricEvent window : entry.getValue()) {
                out.append(String.format(Locale.ROOT,
                        "    t=%3ds..%3ds  n=%-4d p50=%7.1f p95=%7.1f p99=%7.1f max=%7.1f%n",
                        window.windowStartTick() * summary.tickMillis() / 1000,
                        window.windowEndTick() * summary.tickMillis() / 1000,
                        window.count(), window.p50(), window.p95(), window.p99(), window.max()));
            }
            out.append('\n');
        }
        out.append("Where does each component get worst? Does it track the load ramp, or does it "
                + "happen at a specific moment regardless of load?\n");
        return out.toString();
    }

    // ------------------------------------------------------------ the failures

    private static String failures(SimulationSummaryEvent summary) {
        if (summary.failures().isEmpty()) {
            return "No faults were injected during this run, so nothing here tests how the "
                    + "design behaves when a component fails. Say so in your assessment -- an "
                    + "untested failure mode is a gap in the evidence, not a strength.";
        }
        StringBuilder out = new StringBuilder();
        for (SimulationSummaryEvent.FailureRecord failure : summary.failures()) {
            out.append(String.format(Locale.ROOT,
                    "  - %s on '%s' from tick %d to %d: %s%n",
                    failure.failureMode(), failure.nodeId(), failure.startTick(), failure.endTick(),
                    failure.recovered()
                            ? String.format(Locale.ROOT, "recovered after %.0f ms of simulated time",
                                    failure.recoveryTimeMs())
                            : "NEVER RECOVERED before the run ended"));
            out.append(String.format(Locale.ROOT,
                    "      worst latency while failing: %.0f ms, peak error rate: %.1f%%%n",
                    failure.worstObservedLatencyMs(), failure.peakObservedErrorRate() * 100));
            if (failure.directlyAffectedNodeIds() != null
                    && !failure.directlyAffectedNodeIds().isEmpty()) {
                out.append(String.format(Locale.ROOT, "      directly affected: %s%n",
                        String.join(", ", failure.directlyAffectedNodeIds())));
            }
        }
        out.append("\nThese are the measured effects, not intended ones. A fault that was "
                + "supposed to be degraded and resilience was not is a design finding.\n");
        return out.toString();
    }

    // ---------------------------------------------------------- computed findings

    private static String computedFindings(ArchitectureAnalysis analysis) {
        if (analysis.findings().isEmpty()) {
            return "Nothing breached a threshold in this run. Do not manufacture a finding to "
                    + "appear thorough -- report that the measurements came back clean and "
                    + "explain what that does and does not prove.";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "Computed score: %d/100 -- verdict \"%s\"%n%n",
                analysis.overallScore(), analysis.verdict()));
        for (ArchitectureEvaluationEvent.Finding finding : analysis.findings()) {
            out.append(String.format(Locale.ROOT, "[%s] %s (%s)%n",
                    finding.severity(), finding.title(), finding.category()));
            out.append("  " + finding.detail() + "\n");
            out.append("  evidence: " + finding.evidence() + "\n");
            if (finding.recommendation() != null && !finding.recommendation().isBlank()) {
                out.append("  already-suggested fix: " + finding.recommendation() + "\n");
            }
            out.append('\n');
        }
        if (!analysis.caveats().isEmpty()) {
            out.append("Caveats that limit how far the above can be pushed:\n");
            analysis.caveats().forEach(caveat -> out.append("  - " + caveat + "\n"));
        }
        return out.toString();
    }

    // ------------------------------------------------------------ return contract

    private static String returnContract(ArchitectureAnalysis analysis) {
        return """
                Return JSON only, with no prose before or after it. Shape:

                {
                  "summary": "3-5 sentences: what this design does well, what actually limits \
                it under this workload, and the single highest-value change to make next. \
                Reference specific numbers.",
                  "verdictRationale": "2-3 sentences on why the computed score is what it is, \
                from the measurements.",
                  "additionalFindings": [
                    {
                      "title": "short claim",
                      "severity": "CRITICAL | HIGH | MEDIUM | LOW | INFO",
                      "category": "latency | reliability | capacity | backpressure | resilience \
                | scalability | design",
                      "detail": "what the problem is and what causes it",
                      "evidence": "the specific measurement from this run that shows it, \
                including the number",
                      "recommendation": "the specific change to make",
                      "relatedNodeIds": ["node-id"]
                    }
                  ],
                  "questionForUser": "one question only the designer can answer, or null"
                }

                Rules for additionalFindings: include one only if you have a measurement from \
                this run that supports it and it is not already listed above. An empty array \
                is the correct and expected answer when the established findings already \
                cover it. Do not restate the established findings here.
                """;
    }

    // ------------------------------------------------------------------ helpers

    private static String bulletList(List<String> items, String whenEmpty) {
        if (items == null || items.isEmpty()) {
            return whenEmpty;
        }
        StringBuilder out = new StringBuilder();
        items.forEach(item -> out.append("  - ").append(item).append('\n'));
        return out.toString();
    }

    private static double percentage(double part, double whole) {
        if (whole <= 0) {
            return 0;
        }
        return part / whole * 100;
    }

}