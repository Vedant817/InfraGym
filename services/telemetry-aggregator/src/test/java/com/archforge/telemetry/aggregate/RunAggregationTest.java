package com.archforge.telemetry.aggregate;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.contracts.lifecycle.MetricUnits;
import com.archforge.contracts.lifecycle.SimulationStatus;
import com.archforge.contracts.stats.Distribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How one run's samples become windows, and when a window may be published.
 *
 * <p>The aggregation is not arithmetic — it is bookkeeping under a delivery guarantee. The
 * engine keys telemetry by simulation id so a run occupies one partition and arrives in tick
 * order, and this class is correct only because of that: it closes a window the moment a
 * sample for a later window arrives, which is only safe because nothing for the closed
 * window can still be in flight. So most of these tests are about <em>ordering and
 * completeness</em>, not about sums.
 */
class RunAggregationTest {

    private static final String SIM = "sim-1";
    private static final long WINDOW = 10;

    private static RunAggregation run() {
        return new RunAggregation(SIM, WINDOW, 1000L);
    }

    /**
     * One telemetry sample.
     *
     * <p>Constructed rather than built with {@code TelemetryEvent.of}, because that
     * convenience takes the tick as the *eighth* argument and a call site that gets the
     * order wrong still compiles — it just produces a run whose ticks are all zero. Every
     * window test then fails for a reason that has nothing to do with windowing. The
     * argument names here are what stop that happening again.
     */
    private static TelemetryEvent sample(long tick, String nodeId, String metric, double value) {
        return new TelemetryEvent(SIM, "sub-1", nodeId, NodeType.SERVICE.getValue(), metric,
                value, MetricUnits.forMetric(metric), tick, "steady", false, EPOCH);
    }

    private static final java.time.Instant EPOCH =
            java.time.Instant.parse("2026-01-01T00:00:00Z");

    private static TelemetryEvent latency(long tick, String nodeId, double millis) {
        return sample(tick, nodeId, MetricNames.LATENCY_MS, millis);
    }

    /**
     * Feeds one sample per tick and returns every window that closed along the way.
     *
     * <p>Returns rather than discarding them because a window closes at the moment a
     * *later* sample arrives, so a helper that swallowed {@code accept()}'s return value
     * would lose every window but the last — and the assertions below would then be
     * measuring the harness rather than the aggregator.
     */
    private static List<RunAggregation.ClosedWindow> feed(RunAggregation run, long fromTick,
                                                           long toTick, String nodeId,
                                                           String metric,
                                                           java.util.function.LongToDoubleFunction value) {
        List<RunAggregation.ClosedWindow> closed = new ArrayList<>();
        for (long tick = fromTick; tick < toTick; tick++) {
            closed.addAll(run.accept(sample(tick, nodeId, metric, value.applyAsDouble(tick))));
        }
        return closed;
    }

    @Nested
    @DisplayName("window boundaries")
    class Boundaries {

        @Test
        @DisplayName("a window closes when a sample for a later window arrives")
        void windowClosesOnTheNextOne() {
            RunAggregation run = run();

            feed(run, 0, WINDOW, "api", MetricNames.LATENCY_MS, t -> 10);
            assertThat(run.accept(latency(WINDOW, "api", 20)))
                    .as("the first window is complete the moment tick 10 appears")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a closed window carries exactly the samples of its own ticks")
        void closedWindowHasOnlyItsTicks() {
            RunAggregation run = run();
            feed(run, 0, WINDOW, "api", MetricNames.LATENCY_MS, t -> 10);

            List<RunAggregation.ClosedWindow> closed = run.accept(latency(WINDOW, "api", 999));

            assertThat(closed).hasSize(1);
            assertThat(closed.get(0).window().count()).isEqualTo(WINDOW);
            assertThat(closed.get(0).window().max())
                    .as("the 999 that triggered the close is in the *next* window")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("nothing closes while the run is still inside one window")
        void nothingClosesMidWindow() {
            RunAggregation run = run();

            feed(run, 0, WINDOW - 1, "api", MetricNames.LATENCY_MS, t -> 10);

            assertThat(run.lastTickSeen()).isEqualTo(WINDOW - 2);
            assertThat(run.drain()).as("draining mid-window returns it rather than losing it")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a skipped window does not shift the ones after it")
        void aGapDoesNotShiftWindows() {
            // A run whose samples skipped a window -- a component that stopped reporting --
            // must not push everything after the gap forward. Tick 25 belongs to window 2
            // whether or not window 1 was ever populated.
            RunAggregation run = run();
            feed(run, 0, WINDOW, "api", MetricNames.LATENCY_MS, t -> 10);

            List<RunAggregation.ClosedWindow> closed = run.accept(latency(25, "api", 30));

            // Window 0 is returned; window 1 saw no samples at all and so produces no
            // aggregate. Tick 25 still lands in window 2 -- the point of the test.
            assertThat(closed).hasSize(1);
            assertThat(closed.get(0).windowIndex()).isZero();

            assertThat(run.accept(latency(28, "api", 30))).isEmpty();
            assertThat(run.drain()).singleElement()
                    .satisfies(window -> assertThat(window.windowIndex()).isEqualTo(2));
        }

        @Test
        @DisplayName("every window of a long run closes in order")
        void everyWindowClosesInOrder() {
            RunAggregation run = run();
            List<Long> indexes = new ArrayList<>();

            // Windows 0-8 close as ticks 10..90 arrive; window 9 is still open at tick 99
            // and comes back from the drain. All ten together, in order.
            feed(run, 0, 100, "api", MetricNames.LATENCY_MS, t -> 10)
                    .forEach(window -> indexes.add(window.windowIndex()));
            run.drain().forEach(window -> indexes.add(window.windowIndex()));

            assertThat(indexes).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        }

        @Test
        @DisplayName("components are windowed independently")
        void eachComponentHasItsOwnWindows() {
            RunAggregation run = run();
            run.accept(latency(0, "api", 10));
            run.accept(latency(11, "db", 50));

            List<RunAggregation.ClosedWindow> closed = run.drain();

            assertThat(closed).hasSize(2);
            assertThat(closed).extracting(RunAggregation.ClosedWindow::nodeId)
                    .containsExactlyInAnyOrder("api", "db");
            assertThat(closed).allSatisfy(window ->
                    assertThat(window.window().count()).isEqualTo(1));
        }

        @Test
        @DisplayName("a window closes only when every component in it has moved on")
        void windowClosesWhenAllComponentsHaveMovedOn() {
            // The subtlety that makes per-run bookkeeping necessary. api has reached tick 20
            // but db has not, so window 1 is not complete: closing it would publish a
            // latency figure computed from half the samples that belong in it.
            RunAggregation run = run();
            List<RunAggregation.ClosedWindow> fromApi =
                    feed(run, 0, 20, "api", MetricNames.LATENCY_MS, t -> 10);
            List<RunAggregation.ClosedWindow> fromDb =
                    feed(run, 0, 10, "db", MetricNames.LATENCY_MS, t -> 50);

            // api is on tick 19 and has closed its window 0; db is still on tick 9 with
            // window 0 open. Publishing db's window 0 from its first 10 ticks would be
            // right; publishing it *again* once db reaches tick 20 is not.
            assertThat(fromApi).extracting(RunAggregation.ClosedWindow::windowIndex)
                    .containsExactly(0L);
            assertThat(fromDb)
                    .as("db has not moved past window 0, so nothing of db's has closed")
                    .isEmpty();

            assertThat(run.accept(latency(20, "db", 50)))
                    .as("db reaching tick 20 completes its own window 0")
                    .hasSize(1);
            assertThat(run.accept(latency(21, "api", 10)))
                    .as("and api's window 1 in the same step")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("percentiles")
    class Percentiles {

        @Test
        @DisplayName("a window's P99 is a value that was actually observed")
        void p99IsObserved() {
            RunAggregation run = run();
            feed(run, 0, WINDOW, "api", MetricNames.LATENCY_MS, t -> 10);

            // Captured from accept(), not from drain(): feeding tick 10 closes window 0 and
            // hands it back, so drain() would return window 1 — a window holding the single
            // sample that triggered the close. Reading the wrong window here yields a
            // distribution of one sample, whose P50 is 20 rather than 10.
            List<RunAggregation.ClosedWindow> closed = run.accept(latency(WINDOW, "api", 20));
            assertThat(closed).hasSize(1);

            SampleWindow window = closed.get(0).window();
            Distribution distribution = window.distribution();

            assertThat(distribution.count()).isEqualTo(WINDOW);
            assertThat(distribution.p50()).isEqualTo(10);
            assertThat(distribution.p99()).isEqualTo(10);
            assertThat(distribution.max()).isEqualTo(10);
        }

        @Test
        @DisplayName("a single slow tick moves the P99, which is the whole point")
        void aSingleOutlierIsVisible() {
            // The reason percentiles exist. A run where 9 of 10 ticks are 10 ms and one is
            // 5000 ms has a median of 10 ms and a P99 of 5000 ms. An average of 509 ms
            // describes neither: it implies every request took about half a second, when in
            // fact nine took ten milliseconds and one took five seconds -- which is exactly
            // the failure a reader would miss if only the average were charted.
            RunAggregation run = run();
            for (long tick = 0; tick < 10; tick++) {
                run.accept(latency(tick, "api", tick == 7 ? 5000 : 10));
            }

            SampleWindow window = run.drain().get(0).window();
            Distribution distribution = window.distribution();

            // 9 samples at 10 ms and one at 5000. Nearest rank puts p95 at
            // ceil(0.95 * 10) = the 10th sample, which is the outlier: with only ten
            // samples the 95th percentile genuinely *is* the worst one. That is the method
            // behaving correctly rather than a bug, and it is why the assertion below is on
            // p99 and max, which are what a reader would act on.
            assertThat(distribution.count()).isEqualTo(10);
            assertThat(distribution.p50()).as("the median is unaffected by one outlier").isEqualTo(10);
            assertThat(distribution.max()).isEqualTo(5000);
            assertThat(distribution.p99()).isEqualTo(5000);
            assertThat(distribution.avg()).isCloseTo(509.0,
                    org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("an empty window has no percentiles rather than a zero that looks like fast")
        void emptyWindowHasNoPercentiles() {
            SampleWindow window = new SampleWindow("api", MetricNames.LATENCY_MS, 0);

            assertThat(window.count()).isZero();
            assertThat(window.distribution().isEmpty()).isTrue();
            assertThat(window.distribution().p99()).isZero();
            assertThat(window.avg()).isZero();
        }

        @Test
        @DisplayName("the aggregator and the engine compute the same percentile for the same samples")
        void agreesWithTheEngine() {
            // Same Distribution on both sides is why it lives in contracts. If the two
            // implementations differed in method or tie-breaking, the per-component figure
            // in the run summary and the charted series would disagree and a reader
            // comparing them would have no way to tell which was right.
            double[] samples = new double[100];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = i;
            }
            Distribution shared = Distribution.of(samples);

            assertThat(shared.p50()).isEqualTo(49);
            assertThat(shared.p95()).isEqualTo(94);
            assertThat(shared.p99()).isEqualTo(98);
            assertThat(shared.percentile(100))
                    .as("the 100th percentile is the maximum, by nearest rank")
                    .isEqualTo(99);
        }
    }

    @Nested
    @DisplayName("rejected input")
    class Rejection {

        @Test
        @DisplayName("a NaN is refused and counted, because it would poison every average")
        void nanIsCounted() {
            RunAggregation run = run();
            run.accept(latency(0, "api", Double.NaN));
            run.accept(latency(1, "api", 10));

            assertThat(run.nonFiniteSamples()).isEqualTo(1);
            assertThat(run.drain().get(0).window().count())
                    .as("the NaN is not averaged in")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an infinity is refused the same way")
        void infinityIsCounted() {
            RunAggregation run = run();
            run.accept(latency(0, "api", Double.POSITIVE_INFINITY));

            assertThat(run.nonFiniteSamples()).isEqualTo(1);
        }

        @Test
        @DisplayName("a sample for another run is not merged in")
        void foreignSampleIsNotMerged() {
            // A record on this partition for a different run means the one-partition-per-run
            // guarantee was broken. Merging it would corrupt this run's percentiles in a way
            // nothing downstream could detect.
            RunAggregation run = run();
            run.accept(latency(0, "api", 10));
            run.accept(new TelemetryEvent("sim-OTHER", "sub-2", "api", NodeType.SERVICE.getValue(),
                    MetricNames.LATENCY_MS, 9999, MetricUnits.MILLISECOND, 0, "steady", false, EPOCH));

            assertThat(run.drain().get(0).window().max()).isEqualTo(10);
        }

        @Test
        @DisplayName("a null sample is ignored rather than throwing")
        void nullIsIgnored() {
            assertThat(run().accept(null)).isEmpty();
        }

        @Test
        @DisplayName("an unknown component type falls back rather than failing the record")
        void unknownTypeFallsBack() {
            RunAggregation run = run();
            run.accept(new TelemetryEvent(SIM, "sub-1", "api", null, MetricNames.LATENCY_MS,
                    10, MetricUnits.MILLISECOND, 0, "steady", false, EPOCH));

            assertThat(run.nodeType("api")).isEqualTo(NodeType.SERVICE.getValue());
        }
    }

    @Nested
    @DisplayName("the terminal event")
    class Terminal {

        private SimulationLifecycleEvent completed(Map<String, Object> details) {
            return new SimulationLifecycleEvent("evt", "corr-1", SIM, "sub-1", "flash_sale",
                    SimulationStatus.COMPLETED, "Completed 60 ticks", 60, 60, null,
                    details, Instant.parse("2026-01-01T00:01:00Z"));
        }

        @Test
        @DisplayName("supplies the identity the aggregator cannot reconstruct")
        void suppliesTheIdentity() {
            RunAggregation run = run();
            run.accept(latency(0, "api", 10));

            run.applyTerminal(completed(Map.of(
                    "offeredRequests", 17260,
                    "servedRequests", 15607,
                    "failedRequests", 0,
                    "backloggedRequests", 1653,
                    "telemetryComplete", true)));

            assertThat(run.totalOffered()).isEqualTo(17260);
            assertThat(run.totalServed()).isEqualTo(15607);
            assertThat(run.totalBacklogged()).isEqualTo(1653);
            assertThat(run.traceComplete()).isTrue();
            assertThat(run.tickCount()).isEqualTo(60);
            assertThat(run.totalTicks()).isEqualTo(60);
        }

        @Test
        @DisplayName("a run whose totals do not add up says so")
        void nonReconcilingRunIsReported() {
            RunAggregation run = run();
            run.applyTerminal(completed(Map.of(
                    "offeredRequests", 1000,
                    "servedRequests", 600,
                    "failedRequests", 0,
                    "backloggedRequests", 0)));

            assertThat(run.reconciles())
                    .as("400 requests are accounted for nowhere")
                    .isFalse();
        }

        @Test
        @DisplayName("a run with no samples reported reconciles trivially")
        void emptyRunReconciles() {
            RunAggregation run = run();
            run.applyTerminal(completed(Map.of()));

            assertThat(run.totalOffered())
                    .as("a run the engine rejected before simulating offered nothing")
                    .isZero();
            assertThat(run.reconciles())
                    .as("nothing was offered, so nothing is unaccounted for")
                    .isTrue();
        }

        @Test
        @DisplayName("an incomplete trace is recorded as such")
        void incompleteTraceIsRecorded() {
            RunAggregation run = run();
            run.applyTerminal(completed(Map.of(
                    "telemetryComplete", false,
                    "telemetrySamplesDropped", 400)));

            assertThat(run.traceComplete()).isFalse();
            assertThat(run.droppedSamples()).isEqualTo(400);
        }

        @Test
        @DisplayName("a terminal event with no completion flag is not silently trusted")
        void absentFlagIsNotAssumedComplete() {
            // The engine publishes the flag on every terminal event, so its absence means
            // the event did not come from the engine we know about -- a replayed record, a
            // hand-published test event, or a future producer. Defaulting to "complete"
            // would report an unverifiable trace as trustworthy, which is the one thing a
            // reader cannot detect afterwards.
            RunAggregation run = run();
            run.applyTerminal(completed(Map.of()));

            assertThat(run.traceComplete())
                    .as("absent means unknown, and unknown is not complete")
                    .isFalse();
        }

        @Test
        @DisplayName("an explicit completion flag is honoured")
        void explicitFlagIsHonoured() {
            RunAggregation run = run();
            run.applyTerminal(completed(Map.of("telemetryComplete", true)));

            assertThat(run.traceComplete()).isTrue();
        }

        @Test
        @DisplayName("the scenario and submission come through for correlation")
        void correlationDetails() {
            RunAggregation run = run();
            run.applyTerminal(completed(Map.of()));

            assertThat(run.scenario()).isEqualTo("flash_sale");
            assertThat(run.submissionId()).isEqualTo("sub-1");
            assertThat(run.correlationId()).isEqualTo("corr-1");
        }
    }

    @Nested
    @DisplayName("the whole-run roll-up")
    class Rollup {

        @Test
        @DisplayName("merges every window of a metric into one series")
        void rollupMergesWindows() {
            RunAggregation run = run();
            // The roll-up is read before draining, because draining is what publishes the
            // windows; a summary built after a drain would find nothing left to merge.
            feed(run, 0, 100, "api", MetricNames.LATENCY_MS, t -> 10);

            List<RunAggregation.SeriesRollup> rollups = run.rollup();

            assertThat(rollups).singleElement().satisfies(series -> {
                assertThat(series.nodeId()).isEqualTo("api");
                assertThat(series.metricName()).isEqualTo(MetricNames.LATENCY_MS);
                assertThat(series.count()).isEqualTo(100);
                assertThat(series.firstTick()).isZero();
                assertThat(series.lastTick()).isEqualTo(99);
            });
        }

        @Test
        @DisplayName("one series per component and metric, not per window")
        void oneSeriesPerComponentAndMetric() {
            RunAggregation run = run();
            feed(run, 0, 100, "api", MetricNames.LATENCY_MS, t -> 10);
            feed(run, 0, 100, "api", MetricNames.CPU_UTILIZATION, t -> 0.5);
            feed(run, 0, 100, "db", MetricNames.LATENCY_MS, t -> 50);

            assertThat(run.rollup()).hasSize(3);
            assertThat(run.seriesCount()).as("api x2 metrics, db x1").isEqualTo(3);
        }

        @Test
        @DisplayName("counts the components that reported")
        void nodeCount() {
            RunAggregation run = run();
            run.accept(latency(0, "api", 10));
            run.accept(latency(0, "db", 50));

            assertThat(run.nodeCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a sample arriving after its window was published is counted, not absorbed")
    void lateSampleIsCounted() {
        // Should be zero, because one partition delivers a run in tick order. Counted rather
        // than dropped because a non-zero value means the ordering guarantee was broken, and
        // the affected windows are missing values that no consumer could detect.
        RunAggregation run = run();
        feed(run, 0, 20, "api", MetricNames.LATENCY_MS, t -> 10);
        run.drain();

        run.accept(latency(3, "api", 10));

        assertThat(run.lateSamples()).isEqualTo(1);
    }
}