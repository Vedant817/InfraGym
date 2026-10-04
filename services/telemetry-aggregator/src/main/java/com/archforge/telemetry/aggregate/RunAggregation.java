package com.archforge.telemetry.aggregate;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.TelemetryEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One simulation's samples, accumulating into tick windows.
 *
 * <p>A run's samples arrive keyed by {@code simulationId}, which the engine keys so that a
 * run occupies exactly one partition and therefore arrives in tick order. That guarantee
 * is what makes this class possible: a window can be closed the moment a sample for a
 * later window appears, because nothing from the current window can still be in flight.
 *
 * <p><b>Not thread-safe, deliberately.</b> One instance belongs to one run, and a run
 * belongs to one partition, so it is only ever touched by the single consumer thread
 * serving that partition. Locking it would imply a concurrency that does not exist and
 * would hide the property the design actually relies on.
 */
public final class RunAggregation {

    /** A window that has been closed and is ready to publish. */
    public record ClosedWindow(String nodeId, String metricName, long windowIndex, SampleWindow window) {
    }

    private final String simulationId;
    private final long windowSizeTicks;
    private final long tickMillis;

    /** (nodeId, metricName) -> windowIndex -> window. Only open windows are retained. */
    private final Map<SeriesKey, Map<Long, SampleWindow>> open = new LinkedHashMap<>();

    /** Per-series high-water mark: the highest window index that series has reached. */
    private final Map<SeriesKey, Long> seriesHighWater = new HashMap<>();

    /**
     * Every window a series has held, closed ones included.
     *
     * <p>Retained because the run's roll-up must cover the whole run. A component that stops
     * reporting at tick 30 has no open windows by tick 60, and a roll-up built from open
     * windows alone would silently omit it — reporting a clean P50 for a component that
     * died halfway through. Bounded per run and released with the run.
     */
    private final Map<SeriesKey, List<SampleWindow>> history = new LinkedHashMap<>();

    private final Map<String, String> nodeTypes = new LinkedHashMap<>();

    private String submissionId;
    private String correlationId;
    private String scenario;
    private SimulationLifecycleEvent.ArchitectureDigest architecture;
    private Instant startedAt = Instant.now();

    /**
     * The run's accounting identity, as the engine computed it.
     *
     * <p>Taken from the terminal lifecycle event rather than reconstructed from samples,
     * because it cannot be reconstructed: the four terms are accumulated in three different
     * places in the engine and a downstream service working from per-tick samples gets a
     * different answer with no way to tell that the engine's is the right one.
     */
    private double totalOffered;
    private double totalServed;
    private double totalFailed;
    private double totalBacklogged;
    private long tickCount;
    private long totalTicks;
    private boolean traceComplete = true;
    private long droppedSamples;

    private long lastTickSeen = -1;

    /**
     * Samples that arrived for a window already closed.
     *
     * <p>Should be zero, because the engine flushes and waits for the broker before it
     * publishes {@code COMPLETED}, and because one partition delivers a run in order. It is
     * counted rather than dropped, because a non-zero value means the ordering guarantee
     * was violated somewhere and a reader deserves to know that a percentile was computed
     * over a set with holes in it.
     */
    private long lateSamples;

    /** Samples whose value was refused because they were not finite. */
    private long nonFiniteSamples;

    public RunAggregation(String simulationId, long windowSizeTicks, long tickMillis) {
        this.simulationId = simulationId;
        this.windowSizeTicks = windowSizeTicks;
        this.tickMillis = tickMillis;
    }

    private record SeriesKey(String nodeId, String metricName) {
        @Override
        public String toString() {
            return nodeId + "/" + metricName;
        }
    }

    /**
     * Folds one sample in, closing and returning any windows it completes.
     *
     * @return windows that are now complete, in ascending window order
     */
    public List<ClosedWindow> accept(TelemetryEvent event) {
        List<ClosedWindow> closed = new ArrayList<>();
        if (event == null) {
            return closed;
        }
        if (event.simulationId() != null && !event.simulationId().equals(simulationId)) {
            // A record on this partition for a different run. Counted, never merged: the
            // whole design assumes one run per partition, and quietly absorbing another
            // run's samples would corrupt this one in a way nothing downstream could see.
            return closed;
        }
        if (submissionId == null) {
            submissionId = event.submissionId();
        }
        if (!Double.isFinite(event.value())) {
            // A NaN or infinity in a telemetry payload is not a number a percentile can
            // absorb: it propagates into the distribution and makes every downstream
            // average NaN. The engine sanitises before publishing, so this is a second
            // line of defence that counts rather than spreads.
            nonFiniteSamples++;
            return closed;
        }

        long windowIndex = TickWindow.indexOf(event.tick(), windowSizeTicks);
        lastTickSeen = Math.max(lastTickSeen, event.tick());

        // A window is closed by a *different* series' arrival in a later window, not by
        // the same series'. The subtlety is that each component reports independently, and
        // they do not report in lockstep: on a 10-tick boundary api can be on tick 10 while
        // db is still on tick 9. Closing on any component's high-water mark would publish
        // db's window from half the samples belonging in it — a latency figure computed
        // over the ticks before the one that mattered.
        //
        // So each series keeps its own high-water mark, and a window for a given series is
        // closed only when that series has moved past it. A component that stops reporting
        // therefore keeps its last window open until the run ends, which is correct: its
        // remaining windows are genuinely still accumulating.
        SeriesKey arriving = new SeriesKey(event.nodeId(), event.metricName());
        long previousHighWater = this.seriesHighWater.getOrDefault(arriving, -1L);

        if (windowIndex > previousHighWater) {
            // From previousHighWater, not previousHighWater + 1: the window the series was
            // last *in* is the one the new arrival completes. Starting at +1 would leave
            // every window open until the run ended, and nothing would ever publish early.
            for (long index = previousHighWater; index < windowIndex; index++) {
                SampleWindow window = windowsFor(arriving).remove(index);
                if (window != null) {
                    closed.add(new ClosedWindow(arriving.nodeId(), arriving.metricName(),
                            index, window));
                    history.computeIfAbsent(arriving, ignored -> new ArrayList<>()).add(window);
                }
            }
            seriesHighWater.merge(arriving, windowIndex, Math::max);
        } else if (windowIndex < previousHighWater) {
            lateSamples++;
            return closed;
        }

        nodeTypes.putIfAbsent(event.nodeId(), resolveType(event));
        windowsFor(arriving)
                .computeIfAbsent(windowIndex,
                        index -> new SampleWindow(event.nodeId(), event.metricName(), index))
                .add(event);
        return closed;
    }

    /** The open windows of one series, created on demand. */
    private Map<Long, SampleWindow> windowsFor(SeriesKey key) {
        return open.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
    }

    /**
     * Emits every window at or below {@code index}, including empty ones.
     *
     * <p>Empty windows are emitted because their absence is information: a gap in the
     * series is how a chart shows that a component stopped reporting, which is a very
     * different thing from a component reporting zero. The contract has
     * {@link com.archforge.contracts.event.AggregatedMetricEvent#empty} for exactly this.
     */
    private List<ClosedWindow> closeAtOrBelow(long index) {
        List<ClosedWindow> closed = new ArrayList<>();
        List<SeriesKey> empty = new ArrayList<>();
        for (Map.Entry<SeriesKey, Map<Long, SampleWindow>> entry : open.entrySet()) {
            SampleWindow window = entry.getValue().remove(index);
            if (window != null) {
                closed.add(new ClosedWindow(entry.getKey().nodeId(), entry.getKey().metricName(),
                        index, window));
            }
            if (entry.getValue().isEmpty()) {
                empty.add(entry.getKey());
            }
        }
        empty.forEach(open::remove);
        return closed;
    }

    private static String resolveType(TelemetryEvent event) {
        NodeType type = NodeType.findByValue(event.nodeType());
        return type == null ? NodeType.SERVICE.getValue() : type.getValue();
    }

    /**
     * Closes and returns every remaining open window.
     *
     * <p>Called when the run's terminal lifecycle event arrives. Windows are returned in
     * node, then metric, then window order so the output for a run is deterministic.
     */
    public List<ClosedWindow> drain() {
        List<ClosedWindow> closed = new ArrayList<>();
        for (Map.Entry<SeriesKey, Map<Long, SampleWindow>> entry : open.entrySet()) {
            List<Long> indexes = new ArrayList<>(entry.getValue().keySet());
            indexes.sort(Long::compareTo);
            for (long index : indexes) {
                SampleWindow window = entry.getValue().remove(index);
                closed.add(new ClosedWindow(entry.getKey().nodeId(), entry.getKey().metricName(),
                        index, window));
                history.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(window);
            }
        }
        open.clear();
        return closed;
    }

    /** Every metric this run reported, merged across all its windows. */
    public List<SeriesRollup> rollup() {
        // Closed windows from history plus whatever is still open. Both halves are required:
        // closed alone omits the run's final window — the one holding whatever happened
        // last, so a component that fell over at tick 95 and was never sampled again is
        // invisible in a roll-up that stops at tick 89.
        Map<SeriesKey, List<SampleWindow>> all = new LinkedHashMap<>(history);
        for (Map.Entry<SeriesKey, Map<Long, SampleWindow>> entry : open.entrySet()) {
            List<SampleWindow> windows = all.computeIfAbsent(entry.getKey(),
                    ignored -> new ArrayList<>());
            List<Long> indexes = new ArrayList<>(entry.getValue().keySet());
            indexes.sort(Long::compareTo);
            for (long index : indexes) {
                windows.add(entry.getValue().get(index));
            }
        }

        List<SeriesRollup> rollups = new ArrayList<>();
        all.forEach((key, windows) -> {
            List<Double> values = new ArrayList<>();
            long count = 0;
            long first = Long.MAX_VALUE;
            long last = Long.MIN_VALUE;
            for (SampleWindow window : windows) {
                window.mergeInto(values);
                count += window.count();
                first = Math.min(first, window.firstTick());
                last = Math.max(last, window.lastTick());
            }
            if (count == 0) {
                return;
            }
            rollups.add(new SeriesRollup(key.nodeId(), key.metricName(), values, count,
                    first == Long.MAX_VALUE ? -1 : first,
                    last == Long.MIN_VALUE ? -1 : last));
        });
        return rollups;
    }

    /** One component's metric across the whole run. */
    public record SeriesRollup(String nodeId, String metricName, List<Double> values,
                               long count, long firstTick, long lastTick) {
    }

    /**
     * Folds in everything only the terminal lifecycle event knows.
     *
     * <p>Called once, when the run ends. Kept separate from {@link #accept} because a
     * terminal event can arrive <em>before</em> the run's last samples — the two topics have
     * no ordering relationship — so the terminal facts have to be recorded without depending
     * on the samples being complete, and vice versa.
     */
    public void applyTerminal(SimulationLifecycleEvent event) {
        if (event == null) {
            return;
        }
        if (submissionId == null) {
            submissionId = event.submissionId();
        }
        if (correlationId == null) {
            correlationId = event.correlationId();
        }
        if (scenario == null) {
            scenario = event.scenario();
        }
        if (architecture == null) {
            architecture = event.architecture();
        }
        if (event.occurredAt() != null) {
            startedAt = event.occurredAt().minusMillis(
                    Math.max(0, (totalTicks - event.ticksExecuted()) * tickMillis));
        }
        tickCount = event.ticksExecuted();
        totalTicks = event.totalTicks();
        traceComplete = event.isTraceComplete();
        droppedSamples = event.droppedSamples();

        totalOffered = number(event.details(), "offeredRequests", totalOffered);
        totalServed = number(event.details(), "servedRequests", totalServed);
        totalFailed = number(event.details(), "failedRequests", totalFailed);
        totalBacklogged = number(event.details(), "backloggedRequests", totalBacklogged);
    }

    private static double number(Map<String, Object> details, String key, double fallback) {
        Object value = details.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    public String simulationId() {
        return simulationId;
    }

    public String submissionId() {
        return submissionId;
    }

    public String correlationId() {
        return correlationId;
    }

    public String scenario() {
        return scenario;
    }

    public SimulationLifecycleEvent.ArchitectureDigest architecture() {
        return architecture;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String nodeType(String nodeId) {
        return nodeTypes.getOrDefault(nodeId, NodeType.SERVICE.getValue());
    }

    public long windowSizeTicks() {
        return windowSizeTicks;
    }

    public long tickMillis() {
        return tickMillis;
    }

    public long lastTickSeen() {
        return lastTickSeen;
    }

    public long lateSamples() {
        return lateSamples;
    }

    public long nonFiniteSamples() {
        return nonFiniteSamples;
    }

    public long totalOffered() {
        return (long) totalOffered;
    }

    public long totalServed() {
        return (long) totalServed;
    }

    public long totalFailed() {
        return (long) totalFailed;
    }

    public long totalBacklogged() {
        return (long) totalBacklogged;
    }

    public long tickCount() {
        return tickCount;
    }

    public long totalTicks() {
        return totalTicks;
    }

    public boolean traceComplete() {
        return traceComplete;
    }

    public long droppedSamples() {
        return droppedSamples;
    }

    /**
     * Whether the run's own four totals account for every request.
     *
     * <p>False means at least one is being counted in the wrong place. The aggregator cannot
     * tell which, so it says so rather than publishing a completion rate it cannot stand
     * behind.
     */
    public boolean reconciles() {
        return totalOffered <= 0
                || Math.abs(totalOffered - (totalServed + totalFailed + totalBacklogged)) <= 1.0;
    }

    /** Components that reported at least one sample in this run. */
    public int nodeCount() {
        return nodeTypes.size();
    }

    /** Distinct (component, metric) series this run produced. */
    public int seriesCount() {
        return new java.util.LinkedHashSet<>(seriesHighWater.keySet()).size();
    }
}