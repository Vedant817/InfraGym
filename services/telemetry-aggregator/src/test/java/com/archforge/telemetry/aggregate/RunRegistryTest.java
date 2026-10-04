package com.archforge.telemetry.aggregate;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.contracts.lifecycle.MetricUnits;
import com.archforge.contracts.lifecycle.SimulationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bookkeeping for runs in progress, and for terminal events waiting on them.
 *
 * <p>Every test here is about a case where the pipeline would otherwise produce a confident
 * wrong answer rather than an error: a roll-up built before its samples arrived, a run
 * summarised twice, a run that never ends holding memory for the life of the process.
 */
class RunRegistryTest {

    private static final String SIM = "sim-1";
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private static TelemetryEvent sample(long tick, String nodeId, double millis) {
        return new TelemetryEvent(SIM, "sub-1", nodeId, NodeType.SERVICE.getValue(),
                MetricNames.LATENCY_MS, millis, MetricUnits.MILLISECOND, tick, "steady", false, EPOCH);
    }

    // ------------------------------------------------------- the parked terminal event

    @Test
    @DisplayName("a terminal event waits for the run's samples rather than acting on it")
    void terminalWaitsForSamples() {
        // The ordering guarantee this exists for. The engine flushes its samples and waits
        // for the broker before publishing COMPLETED, so every sample is in the log first --
        // but two consumers on two topics have no ordering relationship, and "in the log" is
        // not "consumed here". Acting immediately builds the roll-up mid-arrival.
        RunRegistry registry = new RunRegistry();
        registry.acquire(SIM, 10, 1000L);

        AtomicInteger rolled = new AtomicInteger();
        registry.onTerminal(SIM, 60, true, 0, rolled::incrementAndGet);

        assertThat(rolled.get())
                .as("the run still has samples outstanding")
                .isZero();
        assertThat(registry.isParked(SIM)).isTrue();

        registry.signalLastSampleSeen(SIM);

        assertThat(rolled.get()).isEqualTo(1);
        assertThat(registry.isParked(SIM)).isFalse();
    }

    @Test
    @DisplayName("a terminal event for a run with no samples acts immediately")
    void terminalWithNoSamplesActsNow() {
        // A run the engine rejected before simulating, or one whose telemetry was all lost.
        // Both must still produce a terminal record; waiting for samples that will never
        // arrive is how a submission sits at AGGREGATING forever.
        RunRegistry registry = new RunRegistry();
        AtomicInteger rolled = new AtomicInteger();

        registry.onTerminal(SIM, 60, true, 0, rolled::incrementAndGet);

        assertThat(rolled.get()).isEqualTo(1);
        assertThat(registry.isParked(SIM)).isFalse();
    }

    @Test
    @DisplayName("a parked event is released when its grace period expires")
    void parkedEventExpires() {
        // The engine was killed mid-run, so its last tick never arrives. Without this the
        // run's accumulator and its parked action are held for the life of the process and
        // its submission never advances.
        RunRegistry registry = new RunRegistry();
        registry.acquire(SIM, 10, 1000L);
        AtomicInteger rolled = new AtomicInteger();

        registry.onTerminal(SIM, 60, true, 0, rolled::incrementAndGet);
        assertThat(rolled.get()).isZero();

        registry.releaseExpired(1); // any positive grace is already expired

        assertThat(rolled.get()).isEqualTo(1);
        assertThat(registry.inFlightCount())
                .as("the accumulator is released with it")
                .isZero();
    }

    @Test
    @DisplayName("an unexpired parked event is left alone")
    void unexpiredEventIsKept() {
        RunRegistry registry = new RunRegistry();
        registry.acquire(SIM, 10, 1000L);
        AtomicInteger rolled = new AtomicInteger();

        registry.onTerminal(SIM, 60, true, 0, rolled::incrementAndGet);
        registry.releaseExpired(Long.MAX_VALUE);

        assertThat(rolled.get()).isZero();
        assertThat(registry.isParked(SIM)).isTrue();
    }

    @Test
    @DisplayName("a roll-up that throws does not leave the run parked forever")
    void failedRollupDoesNotStrand() {
        // The listener thread is shared by every run on this partition, so an exception
        // escaping the action would stop consuming all of them.
        RunRegistry registry = new RunRegistry();
        registry.acquire(SIM, 10, 1000L);

        registry.onTerminal(SIM, 60, true, 0, () -> {
            throw new IllegalStateException("publish failed");
        });
        registry.signalLastSampleSeen(SIM);

        assertThat(registry.isParked(SIM))
                .as("cleared even though the action failed")
                .isFalse();
    }

    // ----------------------------------------------------------------- rolling up once

    @Test
    @DisplayName("a run is rolled up exactly once")
    void rollsUpOnce() {
        RunRegistry registry = new RunRegistry();

        assertThat(registry.claimRollup(SIM))
                .as("the first claim wins")
                .isTrue();
        assertThat(registry.claimRollup(SIM))
                .as("a second claim is refused, so the run is not summarised twice")
                .isFalse();
        assertThat(registry.isRolledUp(SIM)).isTrue();
    }

    @Test
    @DisplayName("a sample arriving after the roll-up is recognised, not re-aggregated")
    void lateSampleAfterRollupIsRecognised() {
        // The consumer being behind is normal, not exceptional. A late sample must not
        // re-create the run and have it summarised a second time -- which appends a second
        // set of windows to the summary and reads as two measurements of one.
        RunRegistry registry = new RunRegistry();
        registry.acquire(SIM, 10, 1000L);
        assertThat(registry.claimRollup(SIM)).isTrue();
        registry.discard(SIM);

        assertThat(registry.isRolledUp(SIM))
                .as("the tombstone outlives discard")
                .isTrue();
    }

    @Test
    @DisplayName("tombstones are forgotten once a run is long finished")
    void tombstonesAreBounded() {
        RunRegistry registry = new RunRegistry();
        registry.claimRollup(SIM);
        assertThat(registry.isRolledUp(SIM)).isTrue();

        int forgotten = registry.sweepFinishedTombstones(0);

        assertThat(forgotten).isEqualTo(1);
        assertThat(registry.isRolledUp(SIM))
                .as("bounded, because a service running for months would otherwise accumulate "
                        + "one entry per run forever")
                .isFalse();
    }

    @Test
    @DisplayName("a recent tombstone survives the sweep")
    void recentTombstoneSurvives() {
        RunRegistry registry = new RunRegistry();
        registry.claimRollup(SIM);

        assertThat(registry.sweepFinishedTombstones(Long.MAX_VALUE / 2))
                .as("far too young to forget: a run could still be arriving")
                .isZero();
        assertThat(registry.isRolledUp(SIM)).isTrue();
    }

    // ----------------------------------------------------------------- window retention

    @Test
    @DisplayName("a window recorded twice is retained once")
    void duplicateWindowsAreCollapsed() {
        // The symptom of a second roll-up: the same window listed twice with different
        // sample counts, which reads as two measurements of one thing.
        RunRegistry registry = new RunRegistry();
        RunAggregation run = registry.acquire(SIM, 10, 1000L);
        run.accept(sample(0, "api", 10));
        List<RunAggregation.ClosedWindow> first = run.accept(sample(10, "api", 20));

        registry.recordPublished(SIM, first);
        registry.recordPublished(SIM, first);

        assertThat(registry.publishedFor(SIM)).hasSize(1);
    }

    @Test
    @DisplayName("distinct windows are all retained")
    void distinctWindowsAreKept() {
        RunRegistry registry = new RunRegistry();
        RunAggregation run = registry.acquire(SIM, 10, 1000L);
        run.accept(sample(0, "api", 10));
        registry.recordPublished(SIM, run.accept(sample(10, "api", 20)));
        registry.recordPublished(SIM, run.accept(sample(20, "api", 30)));

        assertThat(registry.publishedFor(SIM)).hasSize(2);
    }

    // ------------------------------------------------------------------------- eviction

    @Test
    @DisplayName("an over-capacity registry evicts the oldest run rather than refusing work")
    void evictsOldestWhenOverCapacity() {
        RunRegistry registry = new RunRegistry();
        for (int i = 0; i < RunRegistry.MAX_IN_FLIGHT + 5; i++) {
            String id = "sim-" + i;
            registry.acquire(id, 10, 1000L).accept(sample(i, "api", 10));
        }

        registry.evictIfOverCapacity();

        assertThat(registry.inFlightCount())
                .as("bounded: a run whose terminal event never arrives must not be able to "
                        + "exhaust the process for every other run")
                .isEqualTo(RunRegistry.MAX_IN_FLIGHT);
        assertThat(registry.evictedRuns()).isEqualTo(5);
    }

    @Test
    @DisplayName("a registry within capacity evicts nothing")
    void withinCapacityEvictsNothing() {
        RunRegistry registry = new RunRegistry();
        registry.acquire(SIM, 10, 1000L).accept(sample(0, "api", 10));

        registry.evictIfOverCapacity();

        assertThat(registry.inFlightCount()).isEqualTo(1);
        assertThat(registry.evictedRuns()).isZero();
    }

    @Test
    @DisplayName("a run's accumulator is released once its roll-up is out")
    void discardReleasesMemory() {
        RunRegistry registry = new RunRegistry();
        registry.acquire(SIM, 10, 1000L);
        registry.recordPublished(SIM, List.of());
        assertThat(registry.inFlightCount()).isEqualTo(1);

        registry.discard(SIM);

        assertThat(registry.inFlightCount())
                .as("the accumulator holds the run's open windows and is this process's "
                        + "dominant memory consumer")
                .isZero();
    }

    @Test
    @DisplayName("the terminal event's own facts are carried, not discarded")
    void terminalFactsAreCarried() {
        RunRegistry registry = new RunRegistry();
        List<SimulationLifecycleEvent> seen = new ArrayList<>();

        registry.onTerminal(SIM, 60, false, 400, () -> seen.add(new SimulationLifecycleEvent(
                "e", "c", SIM, "sub-1", "normal", SimulationStatus.COMPLETED, "", 60, 60,
                null, Map.of(), EPOCH)));

        assertThat(seen).hasSize(1);
        assertThat(registry.isParked(SIM)).isFalse();
    }
}