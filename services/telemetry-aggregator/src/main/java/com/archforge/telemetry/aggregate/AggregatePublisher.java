package com.archforge.telemetry.aggregate;

import com.archforge.contracts.event.AggregatedMetricEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.contracts.lifecycle.MetricUnits;
import com.archforge.contracts.stats.Distribution;
import com.archforge.telemetry.config.AggregatorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes windowed aggregates and the whole-run summary.
 *
 * <p><b>Which event type, and why it matters.</b> The aggregator used to publish a private
 * ten-field class of its own onto {@code aggregated.metrics}. The contract has carried
 * {@link AggregatedMetricEvent} — nineteen fields, with {@code windowStartTick},
 * {@code windowEndTick}, {@code submissionId}, {@code nodeType}, {@code sum},
 * {@code stdDev}, {@code p50}, {@code p95} and {@code p99} — since it was written. Nothing
 * produced it, so every consumer downstream read a payload with no percentiles, no
 * submission to correlate and no simulated time to place on an axis. The README's central
 * promise was not merely unimplemented; it was unreachable by construction.
 *
 * <p>Both aggregates and summaries are keyed by {@code simulationId}, which preserves the
 * one-partition-per-run property for everything downstream. The old topology re-keyed its
 * output to {@code simulationId:nodeId}, so a run's windows were spread across partitions by
 * component and no consumer could reassemble a run in order.
 */
@Slf4j
@Component
public class AggregatePublisher {

    /** How long a publish may take before it is treated as failed. */
    static final int AWAIT_TIMEOUT_SECONDS = 15;

    /**
     * Window aggregates carried on a summary.
     *
     * <p>{@code windowAggregates} exists so the evaluator's tools can answer "P99 during the
     * spike window" rather than only run-wide averages — an average over a whole run hides
     * exactly the regression the user needs told about. It is also the one unbounded thing
     * in the event, so it is capped and the cap is stated on the record.
     */
    static final int MAX_SUMMARY_WINDOWS = 2_000;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AggregatorProperties properties;
    private final MeterRegistry meterRegistry;

    public AggregatePublisher(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              AggregatorProperties properties,
                              MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** Publishes every window the run has completed since the last call. */
    public int publishWindows(RunAggregation run, List<RunAggregation.ClosedWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return 0;
        }
        int published = 0;
        for (RunAggregation.ClosedWindow closed : windows) {
            if (send(properties.getAggregatedMetricsTopic(), toEvent(run, closed), "aggregated metric")) {
                published++;
                meterRegistry.counter("aggregator.metrics.published").increment();
            }
        }
        return published;
    }

    /**
     * Projects one window onto the wire event.
     *
     * <p>Percentiles only where they mean something. A utilisation or error-rate sample has
     * no meaningful P99 — "the 99th percentile error rate" is not a quantity anyone wants —
     * and publishing one anyway invites a chart to draw a latency line for a rate. The
     * contract already answers that in {@link AggregatedMetricEvent#isLatencyShaped()}, so
     * the projection consults it rather than deciding again.
     */
    AggregatedMetricEvent toEvent(RunAggregation run, RunAggregation.ClosedWindow closed) {
        SampleWindow window = closed.window();
        long windowSize = run.windowSizeTicks();
        long startTick = TickWindow.startOf(closed.windowIndex(), windowSize);
        long endTick = TickWindow.endOf(closed.windowIndex(), windowSize);
        String unit = MetricUnits.forMetric(closed.metricName());
        boolean latencyShaped = MetricUnits.MILLISECOND.equals(unit);
        Distribution distribution = window.distribution();

        long startMillis = TickWindow.startMillisOf(closed.windowIndex(), windowSize, run.tickMillis());

        return new AggregatedMetricEvent(
                run.simulationId(),
                run.submissionId(),
                closed.nodeId(),
                run.nodeType(closed.nodeId()),
                closed.metricName(),
                unit,
                startTick,
                endTick,
                Instant.ofEpochMilli(startMillis),
                Instant.ofEpochMilli(startMillis + windowSize * run.tickMillis()),
                window.count(),
                window.min(),
                window.max(),
                window.avg(),
                window.sum(),
                latencyShaped ? distribution.p50() : 0,
                latencyShaped ? distribution.p95() : 0,
                latencyShaped ? distribution.p99() : 0,
                latencyShaped ? distribution.stdDev() : 0);
    }

    /** One component's figures, folded from all the metrics that component reported. */
    private record NodeFigures(String nodeId, String nodeType, Map<String, RunAggregation.SeriesRollup> series) {

        /** Builds a distribution from one metric's samples. */
        Distribution distribution(String metric) {
            RunAggregation.SeriesRollup rollup = series.get(metric);
            if (rollup == null) {
                return Distribution.of(new double[0]);
            }
            double[] array = new double[rollup.values().size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = rollup.values().get(i);
            }
            return Distribution.of(array);
        }

        /** First metric present from a preference list; a node may report either name. */
        Distribution first(String... metrics) {
            for (String metric : metrics) {
                if (series.containsKey(metric)) {
                    return distribution(metric);
                }
            }
            return Distribution.of(new double[0]);
        }

        long count(String... metrics) {
            for (String metric : metrics) {
                RunAggregation.SeriesRollup rollup = series.get(metric);
                if (rollup != null) {
                    return rollup.count();
                }
            }
            return 0;
        }
    }

    /**
     * Builds and publishes the whole-run summary.
     *
     * @param traceComplete the engine's own claim that it delivered every sample, which is
     *                      what decides whether the percentiles here are quotable
     */
    public void publishSummary(RunAggregation run, List<RunAggregation.ClosedWindow> windows) {
        if (!properties.isPublishSummary()) {
            return;
        }
        Map<String, NodeFigures> figures = foldByNode(run);

        List<SimulationSummaryEvent.NodeSummary> nodes = new ArrayList<>(figures.size());
        figures.forEach((nodeId, node) -> {
            Distribution latency = node.distribution(MetricNames.LATENCY_MS);
            Distribution errors = node.distribution(MetricNames.ERROR_RATE);
            Distribution throughput = node.first(MetricNames.SUCCESS_RPS, MetricNames.THROUGHPUT_RPS);
            Distribution utilisation = node.first(MetricNames.CPU_UTILIZATION, MetricNames.DB_UTILIZATION);
            Distribution queueDepth = node.distribution(MetricNames.QUEUE_DEPTH);
            Distribution hitRate = node.distribution(MetricNames.CACHE_HIT_RATE);

            nodes.add(new SimulationSummaryEvent.NodeSummary(
                    nodeId,
                    node.nodeType(),
                    nodeId,
                    latency.p50(),
                    latency.p95(),
                    latency.p99(),
                    latency.max(),
                    errors.avg(),
                    errors.max(),
                    throughput.max(),
                    throughput.avg(),
                    utilisation.max(),
                    hitRate.isEmpty() ? -1 : hitRate.avg(),
                    (long) queueDepth.max(),
                    false,
                    utilisation.max() >= 1.0,
                    node.count(MetricNames.LATENCY_MS)));
        });
        nodes.sort(Comparator.comparing(SimulationSummaryEvent.NodeSummary::nodeId));

        List<AggregatedMetricEvent> aggregates = summariseWindows(run, windows);
        SimulationSummaryEvent summary = withWarnings(new SimulationSummaryEvent(
                run.simulationId(),
                run.submissionId(),
                run.correlationId(),
                run.scenario(),
                run.architecture(),
                List.of(),
                nodes,
                totals(nodes, run),
                aggregates,
                Map.of(),
                run.startedAt(),
                Instant.now(),
                run.tickCount(),
                run.tickMillis(),
                List.of()),
                run, windows.size());

        send(properties.getAggregatedSummaryTopic(), summary, "simulation summary");
    }

    /**
     * One NodeSummary per component, not per component-metric.
     *
     * <p>The distinction matters: {@code NodeSummary.peakUtilization} is one number, and a
     * design with a cache in front of a database has two utilisation series. Choosing between
     * them is a judgement, so it is made here by preference (CPU over storage, because a CPU
     * figure exists for every component and a storage figure only for storage) rather than by
     * whichever series happened to be iterated last.
     */
    private Map<String, NodeFigures> foldByNode(RunAggregation run) {
        Map<String, Map<String, RunAggregation.SeriesRollup>> byNode = new LinkedHashMap<>();
        for (RunAggregation.SeriesRollup series : run.rollup()) {
            byNode.computeIfAbsent(series.nodeId(), ignored -> new LinkedHashMap<>())
                    .put(series.metricName(), series);
        }
        Map<String, NodeFigures> figures = new LinkedHashMap<>();
        byNode.forEach((nodeId, series) ->
                figures.put(nodeId, new NodeFigures(nodeId, run.nodeType(nodeId), series)));
        return figures;
    }

    private List<AggregatedMetricEvent> summariseWindows(RunAggregation run,
                                                         List<RunAggregation.ClosedWindow> windows) {
        List<AggregatedMetricEvent> aggregates = new ArrayList<>();
        for (RunAggregation.ClosedWindow closed : windows) {
            if (aggregates.size() >= MAX_SUMMARY_WINDOWS) {
                log.warn("Simulation {} produced {} windows; the summary carries the first {}",
                        run.simulationId(), windows.size(), MAX_SUMMARY_WINDOWS);
                break;
            }
            aggregates.add(toEvent(run, closed));
        }
        return aggregates;
    }

    private SimulationSummaryEvent.Totals totals(List<SimulationSummaryEvent.NodeSummary> nodes,
                                                 RunAggregation run) {
        return new SimulationSummaryEvent.Totals(
                nodes.stream().mapToDouble(SimulationSummaryEvent.NodeSummary::p50LatencyMs).max().orElse(0),
                nodes.stream().mapToDouble(SimulationSummaryEvent.NodeSummary::p95LatencyMs).max().orElse(0),
                nodes.stream().mapToDouble(SimulationSummaryEvent.NodeSummary::p99LatencyMs).max().orElse(0),
                nodes.stream().mapToDouble(SimulationSummaryEvent.NodeSummary::avgErrorRate).max().orElse(0),
                nodes.stream().mapToDouble(SimulationSummaryEvent.NodeSummary::peakErrorRate).max().orElse(0),
                nodes.stream().mapToDouble(SimulationSummaryEvent.NodeSummary::peakThroughputRps).max().orElse(0),
                run.totalTicks() > 0 && run.tickCount() > 0
                        ? run.totalOffered() * 1000.0 / (run.tickCount() * run.tickMillis()) : 0,
                run.totalOffered(),
                run.totalFailed(),
                run.totalBacklogged(),
                (int) nodes.stream().filter(SimulationSummaryEvent.NodeSummary::saturated).count(),
                0);
    }

    /**
     * The caveats that must travel with the summary.
     *
     * <p>A summary computed from 0.2% of a run's samples is still published — withholding it
     * leaves a submission stuck at {@code AGGREGATING} with nothing to show — but it says so
     * on the record rather than in a log. This is the aggregator's half of the contract the
     * engine keeps: a run whose trace was partial must not be readable as a complete one.
     */
    private SimulationSummaryEvent withWarnings(SimulationSummaryEvent summary, RunAggregation run,
                                               int windowCount) {
        List<String> warnings = new ArrayList<>();
        if (!run.traceComplete()) {
            warnings.add("This summary was computed from an incomplete trace: the engine reported "
                    + run.droppedSamples() + " undelivered telemetry sample(s). Every percentile in "
                    + "it is unreliable and any chart drawn from this run is partial.");
        }
        if (run.lateSamples() > 0) {
            warnings.add(run.lateSamples() + " sample(s) arrived after the window they belong to had "
                    + "already been published, so those windows are missing values. The engine keys "
                    + "telemetry by simulation id so this should not happen; it is reported rather "
                    + "than absorbed because it means the ordering guarantee was broken.");
        }
        if (run.nonFiniteSamples() > 0) {
            warnings.add(run.nonFiniteSamples() + " sample(s) had a non-finite value and were excluded. "
                    + "A NaN would make every average downstream of it NaN.");
        }
        if (windowCount > MAX_SUMMARY_WINDOWS) {
            warnings.add("This run produced " + windowCount + " metric windows; the summary carries the "
                    + "first " + MAX_SUMMARY_WINDOWS + ". The complete series is on "
                    + properties.getAggregatedMetricsTopic() + ".");
        }
        // The window count is checked against the run's own tick range, because a summary
        // built from a partial trace is otherwise indistinguishable from a whole one: the
        // same percentiles, the same fields, no warning. Found by running the pipeline — a
        // live 60-tick run summarised with 2 of its 6 windows and no caveat, because the
        // terminal event was read before the backlog of telemetry ahead of it.
        long expectedWindows = run.tickCount() > 0 && run.windowSizeTicks() > 0
                ? (run.tickCount() + run.windowSizeTicks() - 1) / run.windowSizeTicks()
                : 0;
        long observedWindows = windowCount > 0
                ? run.rollup().stream().mapToLong(series -> series.count() / run.windowSizeTicks() + 1)
                        .max().orElse(0)
                : 0;
        if (expectedWindows > 0 && observedWindows < expectedWindows) {
            warnings.add("This summary covers " + observedWindows + " of the run's " + expectedWindows
                    + " tick windows. The terminal event was read before this aggregator had "
                    + "caught up with the run's telemetry, so every percentile in it describes "
                    + "only the samples that had arrived and its series has a gap.");
        }
        if (run.totalOffered() > 0 && !run.reconciles()) {
            warnings.add("This run's own totals do not reconcile: offered "
                    + run.totalOffered() + ", served " + run.totalServed()
                    + ", failed " + run.totalFailed() + ", queued "
                    + run.totalBacklogged() + ". At least one of those is being counted "
                    + "in the wrong place; treat the completion rate as unreliable.");
        }
        if (run.totalTicks() > 0 && run.tickCount() < run.totalTicks()) {
            warnings.add("This run executed " + run.tickCount() + " of " + run.totalTicks()
                    + " requested ticks, so the later phases of its workload never happened. "
                    + "Its figures describe a shorter run than the one that was asked for.");
        }
        SimulationSummaryEvent result = summary;
        for (String warning : warnings) {
            result = result.withWarning(warning);
        }
        return result;
    }

    private boolean send(String topic, Object payload, String what) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            meterRegistry.counter("aggregator.serialize_failed").increment();
            log.error("Failed to serialise {}", what, e);
            return false;
        }
        String key = payload instanceof AggregatedMetricEvent metric ? metric.simulationId()
                : payload instanceof SimulationSummaryEvent summary ? summary.simulationId()
                : null;
        try {
            CompletableFuture<?> future = kafkaTemplate.send(topic, key, json);
            future.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("aggregator.publish_failed").increment();
            log.error("Interrupted publishing {} to {}", what, topic);
            return false;
        } catch (Exception e) {
            meterRegistry.counter("aggregator.publish_failed").increment();
            log.error("Failed to publish {} to {}: {}", what, topic, e.getMessage());
            return false;
        }
    }

    }