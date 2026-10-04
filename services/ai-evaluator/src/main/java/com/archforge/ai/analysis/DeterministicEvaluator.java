package com.archforge.ai.analysis;

import com.archforge.ai.analysis.ArchitectureAnalysis.Observation;
import com.archforge.ai.analysis.ArchitectureAnalysis.Verdict;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.SimulationSummaryEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Works out what a run actually proves, before any model is involved.
 *
 * <p>Pure arithmetic on {@link SimulationSummaryEvent}. No Spring, no Kafka, no network --
 * so the part of the product the reader is asked to trust can be tested exhaustively and
 * its output can be recomputed by hand from the same summary the reader can see.
 *
 * <p><b>How the score is built.</b> Start at 100, deduct for each breached threshold, and
 * say what was deducted for. Each deduction is proportional to how far past its threshold
 * the measurement is, which means the score degrades smoothly rather than stepping, and
 * saturates at the full weight so a pathological outlier cannot take a score to zero and
 * make the rest of the report meaningless.
 *
 * <pre>
 *   deduction = weight * min(1, (observed - threshold) / threshold)
 * </pre>
 *
 * <p>A measurement at or under its threshold costs nothing. One at twice the threshold
 * costs the full weight.
 */
@Component
public class DeterministicEvaluator {

    /**
     * The thresholds the score is measured against.
     *
     * <p>Every number here is a judgement, so all of them are configurable and all of them
     * are stated rather than buried. The defaults are the ordinary starting points for an
     * interactive service: a quarter-second P99 is what a user stops noticing, one percent
     * errors is what a user does not.
     */
    public record Thresholds(
            double p99LatencyMs,
            double peakErrorRate,
            double latencyWeight,
            double errorWeight,
            double saturationWeight,
            double backlogWeight,
            double backlogSaturatingRatio,
            double queueDepthAlert,
            double incompleteTraceCap,
            int incompleteTracePenalty) {

        public Thresholds {
            if (p99LatencyMs <= 0) {
                throw new IllegalArgumentException("p99LatencyMs must be positive, was " + p99LatencyMs);
            }
            if (peakErrorRate <= 0) {
                throw new IllegalArgumentException("peakErrorRate must be positive, was " + peakErrorRate);
            }
        }

        public static Thresholds defaults() {
            return new Thresholds(250, 0.01, 35, 30, 18, 35, 0.20, 100, 60, 15);
        }
    }

    private static final int MAX_SCORE = 100;

    private final Thresholds thresholds;

    public DeterministicEvaluator() {
        this(Thresholds.defaults());
    }

    public DeterministicEvaluator(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    /**
     * The analysis.
     *
     * @param summary the run's completed roll-up; never null
     */
    public ArchitectureAnalysis analyse(SimulationSummaryEvent summary) {
        if (summary == null) {
            throw new IllegalArgumentException(
                    "Cannot analyse a null summary. An evaluation with no measurements behind it is a "
                            + "fabrication, and the pipeline must never ask for one.");
        }
        List<Observation> observations = new ArrayList<>();
        if (summary.nodeSummaries().isEmpty()) {
            // A run that measured nothing has breached nothing, and with a penalty-only scheme
            // that earns it a perfect score -- the single most dangerous number this service
            // could publish, because it is indistinguishable on the page from a design that
            // genuinely passed. Either the telemetry was lost or the run was abandoned; both
            // mean there is nothing to assess, and that is a finding rather than a pass.
            observations.add(new Observation("architecture", null, "components_reported", 0, 1,
                    "components",
                    "This run reported no component measurements at all. Every figure below is "
                            + "therefore an absence of evidence rather than evidence of health: no "
                            + "latency, no error rate and no utilisation was observed, and a design "
                            + "that was never measured cannot be scored as sound.",
                    25, ArchitectureEvaluationEvent.Severity.CRITICAL,
                    "Find out why the run produced no telemetry -- a dropped trace, a failed "
                            + "engine, or a graph nothing could reach -- before trusting any "
                            + "verdict about this design.",
                    false));
        }
        observations.addAll(latencyObservations(summary));
        observations.addAll(errorObservations(summary));
        observations.addAll(saturationObservations(summary));
        observations.addAll(backlogObservation(summary));
        observations.addAll(queueObservations(summary));
        observations.addAll(recoveryObservations(summary));
        observations.addAll(structuralObservations(summary));
        observations.addAll(strengthObservations(summary));

        int penalty = observations.stream().mapToInt(Observation::penalty).sum();
        int score = Math.max(0, Math.min(MAX_SCORE, MAX_SCORE - penalty));

        // Confidence, not performance. A score derived from a trace the engine could not
        // deliver in full is a score about the parts we happened to receive, and letting it
        // read as a clean 88 is the specific failure this cap exists to prevent: the reader
        // has no way to tell a measured 88 from an inferred 88.
        boolean traceComplete = summary.warnings().stream().noneMatch(this::isIncompleteTraceWarning);
        if (!traceComplete) {
            score = Math.min(score, (int) thresholds.incompleteTraceCap());
        }

        List<Observation> problems = observations.stream()
                .filter(observation -> !observation.strength())
                .sorted(Comparator.comparingInt(Observation::penalty).reversed())
                .toList();
        List<Observation> strengths = observations.stream()
                .filter(Observation::strength)
                .sorted(Comparator.comparingInt(Observation::penalty))
                .toList();

        boolean backlogged = summary.totals() != null && summary.totals().backloggedRequests() > 0;
        return new ArchitectureAnalysis(
                score,
                Verdict.forScore(score, backlogged).label(),
                problems.stream().map(Observation::toBottleneck).toList(),
                toFindings(problems),
                strengths.stream().map(Observation::explanation).toList(),
                problems.stream().map(Observation::explanation).toList(),
                problems.stream()
                        .map(Observation::recommendation)
                        .filter(recommendation -> recommendation != null && !recommendation.isBlank())
                        .distinct()
                        .toList(),
                caveatsFor(summary, traceComplete, backlogged));
    }

    /**
     * A caveat is anything that limits how far the numbers above can be pushed.
     *
     * <p>Carried on the published event so the caveats travel with the verdict rather than
     * living in a log line nobody reads at the moment they need it.
     */
    private List<String> caveatsFor(SimulationSummaryEvent summary, boolean traceComplete,
                                    boolean backlogged) {
        List<String> caveats = new ArrayList<>(summary.warnings());
        if (!traceComplete) {
            caveats.add("The simulation engine reported that it could not deliver every telemetry "
                    + "sample, so this score is computed from an incomplete trace and is capped at "
                    + thresholds.incompleteTraceCap() + " to stop it reading as a clean result.");
        }
        if (backlogged) {
            long backloggedRequests = (long) summary.totals().backloggedRequests();
            long offered = (long) summary.totals().totalRequests();
            caveats.add(String.format(Locale.ROOT,
                    "%d of %d requests were still queued when the run ended (%.1f%%). Those requests "
                            + "were never served and never failed -- they were still in a queue when the "
                            + "clock ran out, which is a capacity problem rather than a defect in the "
                            + "request path. Treat this as the architecture's throughput ceiling for "
                    + "this workload, not as a measured latency.",
                    backloggedRequests, offered, ratio(backloggedRequests, offered) * 100));
        }
        if (summary.tickCount() > 0 && summary.totalTicks() > 0
                && summary.tickCount() < summary.totalTicks()) {
            caveats.add(String.format(Locale.ROOT,
                    "The run executed %d of %d requested ticks, so the later phases of the workload "
                            + "never happened and these figures describe a shorter run than the one asked for.",
                    summary.tickCount(), summary.totalTicks()));
        }
        return caveats;
    }

    private boolean isIncompleteTraceWarning(String warning) {
        String lower = warning.toLowerCase(Locale.ROOT);
        return lower.contains("incomplete trace") || lower.contains("undelivered")
                || lower.contains("partial");
    }

    // ------------------------------------------------------------------ latency

    private List<Observation> latencyObservations(SimulationSummaryEvent summary) {
        List<Observation> observations = new ArrayList<>();
        for (SimulationSummaryEvent.NodeSummary node : summary.nodeSummaries()) {
            if (node.sampleCount() <= 0) {
                // A component that reported no samples has no latency. Reporting its zero as
                // a fast P99 would be the worst kind of wrong: it looks like the best result
                // on the page.
                observations.add(new Observation(node.nodeId(), node.nodeType(),
                        "latency_samples", 0, 1, "samples",
                        "Component '" + node.nodeId() + "' reported no latency samples at all, so its "
                                + "latency is unknown rather than zero. Either it was never exercised by "
                                + "this workload or its telemetry was lost, and both mean this design is "
                                + "not fully accounted for.",
                        5, ArchitectureEvaluationEvent.Severity.MEDIUM,
                        "Confirm this component is on a path the workload actually reaches; if it is, "
                                + "its telemetry is being dropped somewhere.",
                        false));
                continue;
            }
            double p99 = node.p99LatencyMs();
            if (p99 <= thresholds.p99LatencyMs()) {
                continue;
            }
            double excess = (p99 - thresholds.p99LatencyMs()) / thresholds.p99LatencyMs();
            int penalty = (int) Math.round(thresholds.latencyWeight() * Math.min(1, excess));
            observations.add(new Observation(node.nodeId(), node.nodeType(), "p99_latency_ms",
                    p99, thresholds.p99LatencyMs(), "ms",
                    String.format(Locale.ROOT,
                            "'%s' reached a P99 of %.0f ms against a %.0f ms target. Its median was "
                                    + "%.0f ms, so the tail is %.1fx the median: this is a tail problem, "
                                    + "not a uniformly slow component, and averaging its latency would "
                                    + "hide it.",
                            node.nodeId(), p99, thresholds.p99LatencyMs(), node.p50LatencyMs(),
                            ratio(p99, node.p50LatencyMs())),
                    penalty, ArchitectureEvaluationEvent.Severity.CRITICAL,
                    "Investigate what makes the slowest 1% of requests to '" + node.nodeId()
                            + "' slow -- GC pauses, lock contention, a cold cache, or a "
                            + "dependency on the critical path -- rather than optimising its average.",
                    false));
        }
        return observations;
    }

    // ------------------------------------------------------------------- errors

    private List<Observation> errorObservations(SimulationSummaryEvent summary) {
        List<Observation> observations = new ArrayList<>();
        for (SimulationSummaryEvent.NodeSummary node : summary.nodeSummaries()) {
            double peak = node.peakErrorRate();
            if (peak <= thresholds.peakErrorRate()) {
                continue;
            }
            double excess = (peak - thresholds.peakErrorRate()) / thresholds.peakErrorRate();
            int penalty = (int) Math.round(thresholds.errorWeight() * Math.min(1, excess));
            observations.add(new Observation(node.nodeId(), node.nodeType(), "peak_error_rate",
                    peak, thresholds.peakErrorRate(), "fraction",
                    String.format(Locale.ROOT,
                            "'%s' rejected up to %.1f%% of requests at its worst moment, against a "
                                    + "%.1f%% target. Its run-wide average error rate was %.1f%%, so the "
                                    + "failures are concentrated in a spike rather than spread evenly -- "
                                    + "something gave way under load and recovered.",
                            node.nodeId(), peak * 100, thresholds.peakErrorRate() * 100,
                            node.avgErrorRate() * 100),
                    penalty, ArchitectureEvaluationEvent.Severity.CRITICAL,
                    "Find what fails under load in '" + node.nodeId() + "'. A peak/average gap like "
                            + "this points at exhaustion -- a timeout, a full pool, a downstream that "
                            + "stopped responding -- rather than a bug in the happy path.",
                    false));
        }
        return observations;
    }

    // --------------------------------------------------------------- saturation

    private List<Observation> saturationObservations(SimulationSummaryEvent summary) {
        List<Observation> observations = new ArrayList<>();
        List<SimulationSummaryEvent.NodeSummary> saturated = summary.nodeSummaries().stream()
                .filter(SimulationSummaryEvent.NodeSummary::saturated)
                .toList();
        if (saturated.isEmpty()) {
            return observations;
        }
        int penalty = (int) Math.min(thresholds.saturationWeight(),
                thresholds.saturationWeight() * 0.4 * saturated.size());
        String names = saturated.stream()
                .map(node -> String.format(Locale.ROOT, "%s (%.0f%% of capacity)",
                        node.nodeId(), node.peakUtilization() * 100))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        observations.add(new Observation(
                saturated.get(0).nodeId(), null, "peak_utilization",
                saturated.stream().mapToDouble(SimulationSummaryEvent.NodeSummary::peakUtilization)
                        .max().orElse(0), 1.0, "fraction",
                String.format(Locale.ROOT,
                        "%d component(s) were offered more work than they could do: %s. At or above "
                                + "100%% utilisation a component has no headroom left, so the next "
                                + "increment of load does not slow it down gradually -- it starts "
                                + "queueing or failing.",
                        saturated.size(), names),
                penalty, ArchitectureEvaluationEvent.Severity.HIGH,
                "Add capacity at " + names + ", or put a queue with a bounded depth in front so "
                        + "overload is shed deliberately instead of turning into latency.",
                false));
        return observations;
    }

    // --------------------------------------------------------- backlog / accounting

    private List<Observation> backlogObservation(SimulationSummaryEvent summary) {
        SimulationSummaryEvent.Totals totals = summary.totals();
        if (totals == null || totals.totalRequests() <= 0) {
            return List.of();
        }
        double backlogRatio = ratio(totals.backloggedRequests(), totals.totalRequests());
        if (backlogRatio <= 0) {
            return List.of();
        }
        // Proportional above a floor, not proportional from zero.
        //
        // Requests that were never served are categorically different from a design that served
        // all of them, so the deduction does not start at nothing and creep up with the count.
        // Found by running the pipeline: at a 9.5% backlog a purely proportional scheme cost
        // 7 points, which ranked "1652 requests were never served" *below* three findings
        // about having one instance of each component -- so the reader met the structural
        // notes first and the reason the run failed last, if at all.
        //
        // The floor also puts this finding above the structural ones by construction, which is
        // the right order: it is the one that explains the verdict.
        double proportional = Math.min(1, backlogRatio / thresholds.backlogSaturatingRatio());
        int penalty = (int) Math.round(thresholds.backlogWeight()
                * Math.max(0.5, proportional));
        return List.of(new Observation("architecture", null, "backlogged_requests",
                totals.backloggedRequests(), totals.totalRequests() * thresholds.backlogSaturatingRatio(),
                "requests",
                String.format(Locale.ROOT,
                        "%s of %s requests were still queued when the run ended (%.1f%%). They were "
                                + "neither served nor rejected -- they were waiting. The architecture's "
                                + "sustained throughput for this workload is below what was offered, and "
                                + "the gap is queue depth rather than latency.",
                        (long) totals.backloggedRequests(), (long) totals.totalRequests(),
                        backlogRatio * 100),
                penalty, ArchitectureEvaluationEvent.Severity.CRITICAL,
                "Either raise throughput to match the offered load, or make the overload "
                        + "behaviour explicit: a bounded queue that sheds load returns a fast, "
                        + "clear error instead of accumulating a slow one.",
                false));
    }

    // ------------------------------------------------------------------- queues

    private List<Observation> queueObservations(SimulationSummaryEvent summary) {
        List<Observation> observations = new ArrayList<>();
        for (SimulationSummaryEvent.NodeSummary node : summary.nodeSummaries()) {
            if (node.peakQueueDepth() <= thresholds.queueDepthAlert()) {
                continue;
            }
            observations.add(new Observation(node.nodeId(), node.nodeType(), "peak_queue_depth",
                    node.peakQueueDepth(), thresholds.queueDepthAlert(), "requests",
                    String.format(Locale.ROOT,
                            "'%s' held a peak queue of %d requests. A queue this deep means its "
                                    + "consumer is slower than its producer for a sustained period, so "
                                    + "latency inside it grows without bound -- and an unbounded queue is "
                                    + "a way of converting a throughput problem into a latency problem "
                                    + "while looking like it fixed anything.",
                            node.nodeId(), node.peakQueueDepth()),
                    8, ArchitectureEvaluationEvent.Severity.HIGH,
                    "Give the queue behind '" + node.nodeId() + "' a bound, and decide what happens "
                        + "when it fills: shed, batch, or scale the consumer. An unbounded queue only "
                        + "moves the failure later.",
                    false));
        }
        return observations;
    }

    // ----------------------------------------------------------------- recovery

    /**
     * What the injected failures actually did.
     *
     * <p>This is the part the previous evaluator ignored completely: the summary carries a
     * {@code FailureRecord} per injected fault, including whether it recovered and how long
     * that took, and none of it reached the assessment. A design whose database failed and
     * never came back was being graded on its happy path.
     */
    private List<Observation> recoveryObservations(SimulationSummaryEvent summary) {
        List<Observation> observations = new ArrayList<>();
        for (SimulationSummaryEvent.FailureRecord failure : summary.failures()) {
            long recoveryMs = failure.recoveryTimeMs();
            if (!failure.recovered()) {
                observations.add(new Observation(failure.nodeId(), failure.nodeType(),
                        "recovered", 0, 1, "boolean",
                        String.format(Locale.ROOT,
                                "The %s injected into '%s' never cleared. Its worst observed latency "
                                        + "was %.0f ms and its peak error rate %.1f%%, and by the end of "
                                        + "the run it was still broken. Whatever depends on this "
                                        + "component was unavailable for the whole scenario.",
                                failure.failureMode(), failure.nodeId(),
                                failure.worstObservedLatencyMs(), failure.peakObservedErrorRate() * 100),
                        20, ArchitectureEvaluationEvent.Severity.CRITICAL,
                        "'" + failure.nodeId() + "' has no path back from a " + failure.failureMode()
                                + " failure. Add redundancy or a fallback path.",
                        false));
            } else if (recoveryMs > 0) {
                observations.add(new Observation(failure.nodeId(), failure.nodeType(),
                        "recovery_time_ms", recoveryMs, 0, "ms",
                        String.format(Locale.ROOT,
                                "The %s injected into '%s' took %,d ms of simulated time to recover, "
                                        + "during which it reached %.0f ms latency and %.1f%% errors. "
                                        + "Whatever it was protecting was degraded for all of that time.",
                                failure.failureMode(), failure.nodeId(), recoveryMs,
                                failure.worstObservedLatencyMs(), failure.peakObservedErrorRate() * 100),
                        6, ArchitectureEvaluationEvent.Severity.MEDIUM,
                        "Check whether anything downstream of '" + failure.nodeId() + "' can tolerate "
                                + "a " + String.format(Locale.ROOT, "%.0f", recoveryMs / 1000.0)
                                + "s outage, or needs a circuit breaker.",
                        false));
            }
        }
        return observations;
    }

    // ---------------------------------------------------------------- structural

    /**
     * Single points of failure, read from the architecture rather than the measurements.
     *
     * <p>Only claims what the digest can actually support. A component that exists exactly
     * once and holds state is a single point of failure; a component that exists once and
     * holds nothing is a deployment inconvenience, not an availability risk, and saying
     * otherwise dilutes the findings that matter.
     */
    private List<Observation> structuralObservations(SimulationSummaryEvent summary) {
        SimulationLifecycleEvent.ArchitectureDigest architecture = summary.architecture();
        if (architecture == null || architecture.nodes() == null || architecture.nodes().isEmpty()) {
            return List.of();
        }
        Map<String, Integer> instancesByType = new TreeMap<>();
        for (SimulationLifecycleEvent.ArchitectureDigest.NodeDigest node : architecture.nodes()) {
            instancesByType.merge(node.type(), 1, Integer::sum);
        }

        List<Observation> observations = new ArrayList<>();

        // Every single-instance stateful component is one finding, not N of them.
        //
        // Two reasons, both found by running the pipeline. A reader scrolling top-down should
        // meet "1652 requests were never served", not three consecutive lines reading
        // "instance count is 1" ahead of it; and a design with three unprotected state stores
        // should not out-score one with two purely because it named more of them.
        //
        // The penalty still grows with the count, capped, because three unprotected state
        // stores really are worse than one -- the ranking is about what the reader meets
        // first, not about pretending the count does not matter.
        List<SimulationLifecycleEvent.ArchitectureDigest.NodeDigest> unprotected = new ArrayList<>();
        Set<String> reportedTypes = new java.util.LinkedHashSet<>();
        for (SimulationLifecycleEvent.ArchitectureDigest.NodeDigest node : architecture.nodes()) {
            if (instancesByType.getOrDefault(node.type(), 0) > 1) {
                continue;
            }
            if (!holdsState(node.type()) || !reportedTypes.add(node.type())) {
                continue;
            }
            unprotected.add(node);
        }
        if (!unprotected.isEmpty()) {
            String names = unprotected.stream()
                    .map(node -> String.format(Locale.ROOT, "%s (%s)", node.id(), node.type()))
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            int penalty = Math.min(18, 10 + 4 * (unprotected.size() - 1));
            observations.add(new Observation(
                    unprotected.get(0).id(), unprotected.get(0).type(), "redundancy",
                    unprotected.size(), 2, "instances",
                    String.format(Locale.ROOT,
                            "%d component(s) that hold state exist in exactly one instance: %s. "
                                    + "There is no second instance for traffic to fail over to, so "
                                    + "each is a single point of failure: when it stops, everything "
                                    + "downstream of it stops.",
                            unprotected.size(), names),
                    penalty, ArchitectureEvaluationEvent.Severity.CRITICAL,
                    "Run at least two instances of each of these, or remove the dependency on a "
                        + "single one: " + names + ".",
                    false));
        }

        // A sole entry point cannot be taken out of service without stopping all traffic.
        long sources = architecture.edges().stream()
                .map(SimulationLifecycleEvent.ArchitectureDigest.EdgeDigest::source)
                .distinct()
                .filter(source -> architecture.edges().stream()
                        .noneMatch(edge -> edge.target().equals(source)))
                .count();
        if (sources == 1) {
            observations.add(new Observation("architecture", null, "entry_points", 1, 2, "nodes",
                    "The design has exactly one component with no inbound edges, so all traffic "
                            + "enters through a single node. It is the one component that cannot be "
                            + "deployed, restarted or drained without the whole service going dark.",
                    8, ArchitectureEvaluationEvent.Severity.HIGH,
                    "Put a load balancer or gateway in front of the entry point so it can be "
                            + "replaced without downtime.",
                    false));
        }
        return observations;
    }

    // ----------------------------------------------------------------- strengths

    /**
     * Measured facts that argue for the design.
     *
     * <p>Included because an assessment with no strengths is indistinguishable from one that
     * failed to look. And these are measurements, not praise: "the load balancer's P99 stayed
     * at 1 ms" is something a reader can check.
     */
    private List<Observation> strengthObservations(SimulationSummaryEvent summary) {
        List<Observation> strengths = new ArrayList<>();
        for (SimulationSummaryEvent.NodeSummary node : summary.nodeSummaries()) {
            if (node.sampleCount() > 0 && node.p99LatencyMs() <= thresholds.p99LatencyMs() * 0.4) {
                strengths.add(new Observation(node.nodeId(), node.nodeType(), "p99_latency_ms",
                        node.p99LatencyMs(), thresholds.p99LatencyMs(), "ms",
                        String.format(Locale.ROOT,
                                "'%s' held its P99 to %.0f ms against a %.0f ms target, with %.0f%% "
                                        + "headroom at its busiest.",
                                node.nodeId(), node.p99LatencyMs(), thresholds.p99LatencyMs(),
                                (1 - ratio(node.p99LatencyMs(), thresholds.p99LatencyMs())) * 100),
                        0, ArchitectureEvaluationEvent.Severity.INFO, null, true));
            }
            if (node.avgCacheHitRate() >= 0 && node.avgCacheHitRate() < 0.3
                    && isCache(node.nodeType())) {
                strengths.add(new Observation(node.nodeId(), node.nodeType(), "avg_cache_hit_rate",
                        node.avgCacheHitRate(), 0.9, "fraction",
                        String.format(Locale.ROOT,
                                "The cache at '%s' averaged only a %.0f%% hit rate. Almost every "
                                        + "request was paying full price to the datastore behind it, so "
                                        + "there is real headroom in hit rate alone.",
                                node.nodeId(), node.avgCacheHitRate() * 100),
                        0, ArchitectureEvaluationEvent.Severity.INFO,
                        "Raise the hit rate before adding capacity downstream.", true));
            }
        }
        if (summary.totals() != null && summary.totals().totalRequests() > 0
                && summary.totals().totalErrors() <= 0
                && summary.totals().backloggedRequests() <= 0) {
            strengths.add(new Observation("architecture", null, "total_errors", 0, 0, "requests",
                    String.format(Locale.ROOT,
                            "Every one of the %s requests the run offered was served: nothing failed "
                                    + "and nothing was left queued.",
                            (long) summary.totals().totalRequests()),
                    0, ArchitectureEvaluationEvent.Severity.INFO, null, true));
        }
        return strengths;
    }

    // ------------------------------------------------------------------ findings

    private List<ArchitectureEvaluationEvent.Finding> toFindings(List<Observation> problems) {
        List<ArchitectureEvaluationEvent.Finding> findings = new ArrayList<>();
        for (int i = 0; i < problems.size(); i++) {
            Observation observation = problems.get(i);
            findings.add(new ArchitectureEvaluationEvent.Finding(
                    // Stable and derived from the metric, so re-evaluating the same run
                    // replaces a finding rather than accumulating a second copy of it.
                    "finding-" + observation.nodeId() + "-" + observation.metric(),
                    observation.severity(),
                    categoryOf(observation.metric()),
                    titleOf(observation),
                    observation.explanation(),
                    String.format(Locale.ROOT, "%s = %.4g %s (threshold %.4g)",
                            observation.metric(), observation.observedValue(),
                            observation.unit(), observation.thresholdValue()),
                    observation.recommendation(),
                    observation.nodeId().equals("architecture")
                            ? List.of()
                            : List.of(observation.nodeId())));
        }
        return findings;
    }

    private static String categoryOf(String metric) {
        return switch (metric) {
            case "p99_latency_ms", "max_latency_ms" -> "latency";
            case "peak_error_rate" -> "reliability";
            case "peak_utilization", "redundancy" -> "capacity";
            case "entry_points" -> "availability";
            case "backlogged_requests", "peak_queue_depth" -> "backpressure";
            case "recovered", "recovery_time_ms" -> "resilience";
            case "latency_samples" -> "observability";
            default -> "performance";
        };
    }

    private static String titleOf(Observation observation) {
        return String.format(Locale.ROOT, "%s: %s is %.4g %s",
                observation.nodeId(), humanise(observation.metric()),
                observation.observedValue(), observation.unit());
    }

    private static String humanise(String metric) {
        return switch (metric) {
            case "p99_latency_ms" -> "P99 latency";
            case "peak_error_rate" -> "peak error rate";
            case "peak_utilization" -> "peak utilisation";
            case "peak_queue_depth" -> "peak queue depth";
            case "backlogged_requests" -> "requests left queued";
            case "recovery_time_ms" -> "recovery time";
            case "recovered" -> "recovery";
            case "redundancy" -> "instance count";
            case "entry_points" -> "entry points";
            case "latency_samples" -> "latency sample count";
            case "total_errors" -> "total errors";
            case "avg_cache_hit_rate" -> "cache hit rate";
            default -> metric;
        };
    }

    /**
     * Whether losing this component's only instance loses data rather than merely capacity.
     *
     * <p>Composed from {@link NodeType}'s own capability predicates rather than from a second
     * taxonomy maintained alongside it. Storage and messaging are the categories that hold
     * state whose loss is unrecoverable; a lone compute node is a deployment inconvenience
     * and a restart, and calling that an availability risk would dilute the findings that
     * are.
     */
    private static boolean holdsState(String type) {
        NodeType nodeType = NodeType.findByValue(type);
        return nodeType != null && (nodeType.isStorage() || nodeType.isMessaging());
    }

    private static String describe(String type) {
        NodeType nodeType = NodeType.findByValue(type);
        return nodeType == null ? type : nodeType.getDisplayName().toLowerCase(Locale.ROOT);
    }

    private static boolean isCache(String type) {
        return NodeType.CACHE.getValue().equals(type);
    }

    private static double ratio(double part, double whole) {
        // Guards the zero-denominator case that a cold component produces, and keeps a
        // never-served component from dividing by zero and reporting an infinite score.
        if (whole <= 0 || !Double.isFinite(whole)) {
            return 0;
        }
        return part / whole;
    }

    /** Grouped by node for the prompt, so the model sees a structure rather than a flat list. */
    public static Map<String, List<Observation>> byNode(List<Observation> observations) {
        Map<String, List<Observation>> grouped = new LinkedHashMap<>();
        for (Observation observation : observations) {
            grouped.computeIfAbsent(observation.nodeId(), ignored -> new ArrayList<>()).add(observation);
        }
        return grouped;
    }
}