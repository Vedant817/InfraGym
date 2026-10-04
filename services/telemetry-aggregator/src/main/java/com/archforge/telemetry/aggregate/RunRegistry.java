package com.archforge.telemetry.aggregate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runs currently being accumulated, and the terminal events waiting for them.
 *
 * <h2>Why a terminal event cannot be acted on immediately</h2>
 * {@code simulation.telemetry} and {@code simulation.lifecycle} are different topics with
 * no ordering relationship between them. The engine guarantees that every sample is
 * <em>acknowledged by the broker</em> before it publishes {@code COMPLETED} — that is what
 * its flush barrier is for — but "written to the log" is not "consumed by this service".
 * Two consumers reading two topics at their own speeds will routinely see the terminal
 * event first, and a roll-up built then is missing the run's last samples. That is the
 * same class of bug the engine's flush barrier exists to prevent, reintroduced one hop
 * downstream.
 *
 * <p>So the terminal event is <em>parked</em> until the telemetry side says it has seen the
 * run's last tick, or until a bounded grace period expires. Parking rather than dropping
 * is what makes the wait safe: worst case the roll-up is built a few hundred milliseconds
 * late, and best case it is complete.
 *
 * <h2>Bounded, because it holds samples</h2>
 * Each entry holds a run's open windows, so this map is the process's dominant memory
 * consumer and must not grow with the number of runs the process has ever seen. Two bounds:
 * runs are evicted after their terminal roll-up, and an over-capacity registry evicts the
 * oldest in-flight run rather than refusing work — a run that never arrives must not be
 * able to exhaust the aggregator for every other run.
 */
@Slf4j
@Component
public class RunRegistry {

    /** How many runs may be in flight before the oldest is evicted. */
    static final int MAX_IN_FLIGHT = 256;

    /** A parked terminal event, waiting for its run's last samples. */
    public record PendingTerminal(String simulationId, long totalTicks, boolean traceComplete,
                                  long droppedSamples, Runnable action) {
    }

    /** Windows already published for a run, so the summary can carry the whole series. */
    private final Map<String, Map<String, List<RunAggregation.ClosedWindow>>> publishedWindows =
            new ConcurrentHashMap<>();

    /**
     * Runs whose roll-up has already happened.
     *
     * <p>Without it, a run that is re-created — because more samples arrive after its
     * summary was published — is rolled up a second time, and the second roll-up's windows
     * are appended to the first's. The symptom is a summary listing the same window twice
     * with different sample counts, which reads as two measurements and is one.
     *
     * <p>Bounded, because it is a set of ids that only ever grows within a run's lifetime.
     */
    private final Map<String, Boolean> rolledUp = new ConcurrentHashMap<>();

    private final Map<String, RunAggregation> inFlight = new ConcurrentHashMap<>();
    private final Map<String, PendingTerminal> parked = new ConcurrentHashMap<>();
    private final Map<String, Long> parkedAt = new ConcurrentHashMap<>();

    private final AtomicLong evictedRuns = new AtomicLong();
    private final AtomicLong rolledUpRuns = new AtomicLong();

    /**
     * The accumulator for a run, created on first sight.
     *
     * <p>Tick length and window size are fixed per registry rather than per run because the
     * telemetry event does not carry them: it is a per-sample record and repeating the run's
     * tick length on every one of ~3 000 samples to save two constructor arguments would
     * be a poor trade. The engine's tick length is a configured default, not a per-request
     * value.
     */
    public RunAggregation acquire(String simulationId, long windowSizeTicks, long tickMillis) {
        return inFlight.computeIfAbsent(simulationId,
                id -> new RunAggregation(id, windowSizeTicks, tickMillis));
    }

    /** The accumulator for a run, or null when it is not in flight. */
    public RunAggregation peek(String simulationId) {
        return inFlight.get(simulationId);
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public int parkedCount() {
        return parked.size();
    }

    public long evictedRuns() {
        return evictedRuns.get();
    }

    public long rolledUpRuns() {
        return rolledUpRuns.get();
    }

    /**
     * Notes that a run has received everything it is going to receive.
     *
     * <p>Called by the telemetry side when a sample for the run's final tick arrives, or
     * when the run is drained. Releases a parked terminal event if there is one.
     */
    public void signalLastSampleSeen(String simulationId) {
        release(simulationId);
    }

    /**
     * Parks or runs a terminal event's action.
     *
     * @param action invoked once the run's samples have all arrived, or after the grace
     *               period, whichever comes first
     */
    public void onTerminal(String simulationId, long totalTicks, boolean traceComplete,
                           long droppedSamples, Runnable action) {
        PendingTerminal pending = new PendingTerminal(simulationId, totalTicks, traceComplete,
                droppedSamples, action);
        parked.put(simulationId, pending);
        parkedAt.put(simulationId, System.nanoTime());

        if (inFlight.containsKey(simulationId)) {
            log.debug("Parked terminal event for {}: waiting on {} more samples",
                    simulationId, inFlight.get(simulationId).lastTickSeen());
            return;
        }
        // No samples are outstanding, so there is nothing to wait for. This is the common
        // case for a run the engine rejected before simulating, and for one whose telemetry
        // was all lost -- both of which must still produce a terminal record rather than
        // leaving the submission stuck at AGGREGATING.
        release(simulationId);
    }

    /** Whether a terminal event is waiting for its run's samples. */
    public boolean isParked(String simulationId) {
        return parked.containsKey(simulationId);
    }

    /**
     * Records windows already published for a run.
     *
     * <p>The summary carries the whole series so the evaluator can answer "P99 during the
     * spike window" rather than only a run-wide average, and a run-wide average hides
     * exactly the regression the user needs told about. Held per run and released with the
     * run's other state, so it costs one reference to an already-retained window.
     */
    public void recordPublished(String simulationId, List<RunAggregation.ClosedWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return;
        }
        publishedWindows
                .computeIfAbsent(simulationId, ignored -> new LinkedHashMap<>())
                .putIfAbsent(windowKey(windows), windows);
    }

    private static String windowKey(List<RunAggregation.ClosedWindow> windows) {
        StringBuilder key = new StringBuilder();
        for (RunAggregation.ClosedWindow window : windows) {
            key.append(window.nodeId()).append('/').append(window.metricName())
                    .append('@').append(window.windowIndex()).append(';');
        }
        return key.toString();
    }

    /** Windows published for a run so far, each appearing once. */
    public List<RunAggregation.ClosedWindow> publishedFor(String simulationId) {
        List<RunAggregation.ClosedWindow> windows = new ArrayList<>();
        publishedWindows.getOrDefault(simulationId, Map.of()).values().forEach(windows::addAll);
        return windows;
    }

    /**
     * Claims the right to roll a run up, exactly once.
     *
     * @return false when this run has already been rolled up
     */
    public boolean claimRollup(String simulationId) {
        if (rolledUp.putIfAbsent(simulationId, Boolean.TRUE) != null) {
            return false;
        }
        rolledUpAge.put(simulationId, System.currentTimeMillis());
        return true;
    }

    /** Whether a run has already been rolled up. */
    public boolean isRolledUp(String simulationId) {
        return rolledUp.containsKey(simulationId);
    }

    private void release(String simulationId) {
        PendingTerminal pending = parked.remove(simulationId);
        parkedAt.remove(simulationId);
        if (pending == null) {
            return;
        }
        try {
            pending.action().run();
            rolledUpRuns.incrementAndGet();
        } catch (RuntimeException e) {
            // One run's failure to roll up must not take the listener thread down: it would
            // stop consuming every other run on this partition.
            log.error("Failed to roll up simulation {}", pending.simulationId(), e);
        }
    }

    /**
     * Releases parked events whose grace period has expired.
     *
     * <p>Called on a timer. Without it a run whose last tick never arrives — the engine
     * was killed mid-run, or a partition was reassigned before its tail — would hold its
     * submission at {@code AGGREGATING} forever, which is the exact symptom the explicit
     * terminal event was introduced to remove.
     */
    public void releaseExpired(long graceNanos) {
        long now = System.nanoTime();
        List<String> expired = new ArrayList<>();
        parkedAt.forEach((id, since) -> {
            if (now - since >= graceNanos) {
                expired.add(id);
            }
        });
        for (String id : expired) {
            PendingTerminal pending = parked.get(id);
            if (pending != null) {
                log.warn("Simulation {} never received its last {} tick(s); rolling up after the "
                                + "grace period with whatever arrived", id, pending.totalTicks());
            }
            release(id);
            // The accumulator goes with the parked action. Holding it because the roll-up
            // "might still need it" is how a run whose last tick never arrives pins its
            // windows for the life of the process -- the accumulator holds the largest
            // structure this service keeps, and nothing will ever read it again.
            inFlight.remove(id);
        }
    }

    /** Drops a run's accumulator and its published windows once the summary is out. */
    public void discard(String simulationId) {
        inFlight.remove(simulationId);
        publishedWindows.remove(simulationId);
        // `rolledUp` is deliberately NOT cleared here: that tombstone is what stops a late
        // sample re-creating the run and having it rolled up a second time. Bounded
        // separately by sweepFinishedTombstones().
    }

    /**
     * Forgets roll-up tombstones older than the given age.
     *
     * <p>The tombstone set grows with the number of runs the process has handled, and it
     * holds no samples — but a service that runs for months would still accumulate one
     * entry per run forever. A run cannot still be in flight after its grace period plus
     * the retention below, so by then the tombstone has no job left.
     */
    public int sweepFinishedTombstones(long olderThanMillis) {
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        List<String> expired = new ArrayList<>();
        rolledUpAge.forEach((id, since) -> {
            if (since != null && since <= cutoff) {
                expired.add(id);
            }
        });
        expired.forEach(id -> {
            rolledUp.remove(id);
            rolledUpAge.remove(id);
        });
        return expired.size();
    }

    private final Map<String, Long> rolledUpAge = new ConcurrentHashMap<>();

    /**
     * Evicts in-flight runs beyond {@link #MAX_IN_FLIGHT}, oldest first.
     *
     * <p>A run whose terminal event never arrives must not be able to exhaust the process.
     * Eviction is logged because it means a run's roll-up will be short, and silently
     * truncating a trace is the failure mode this service is meant to eliminate.
     */
    public void evictIfOverCapacity() {
        while (inFlight.size() > MAX_IN_FLIGHT) {
            String oldest = null;
            long oldestTick = Long.MAX_VALUE;
            for (Map.Entry<String, RunAggregation> entry : inFlight.entrySet()) {
                if (entry.getValue().lastTickSeen() < oldestTick) {
                    oldestTick = entry.getValue().lastTickSeen();
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) {
                return;
            }
            inFlight.remove(oldest);
            publishedWindows.remove(oldest);
            evictedRuns.incrementAndGet();
            log.error("Evicted in-flight simulation {}: {} runs were open and its terminal event "
                    + "never arrived, so its roll-up will be incomplete", oldest, inFlight.size());
        }
    }
}