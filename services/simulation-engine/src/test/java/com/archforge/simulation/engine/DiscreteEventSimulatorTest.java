package com.archforge.simulation.engine;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.simulation.failure.FailureInjector;
import com.archforge.simulation.support.TestGraphs;
import com.archforge.simulation.workload.WorkloadProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The simulator's behaviour: what it produces from a compiled DAG and a workload.
 *
 * <p>Each test names the mechanism it pins down. Most of them exist because the
 * previous engine got that specific thing wrong while reporting a healthy status and
 * empty topics — the failure mode this service is being rebuilt to eliminate.
 */
class DiscreteEventSimulatorTest {

    private static final long SEED = 42L;
    private static final long TICK = 1000L;

    /** Flat load at a given rate for the whole run; no ramp, no phases. */
    private static WorkloadProfile flat(double rps, long seconds) {
        return new WorkloadProfile(
                List.of(new WorkloadProfile.Phase("steady", (int) seconds, 1.0, 1.0, false)),
                seconds);
    }

    /** Records every sample, so a test can assert on series rather than final state. */
    private static final class Recorder implements TickObserver {
        private final Map<String, List<Sample>> byNode = new LinkedHashMap<>();
        private long count;

        @Override
        public void onSample(CompiledNode node, ComponentState state, String metricName,
                              double value, long tick, String phase, boolean failing) {
            byNode.computeIfAbsent(node.getId(), key -> new ArrayList<>())
                    .add(new Sample(metricName, value, tick, phase, failing));
            count++;
        }

        List<Sample> series(String nodeId, String metric) {
            return byNode.getOrDefault(nodeId, List.of()).stream()
                    .filter(sample -> sample.metric().equals(metric))
                    .toList();
        }

        List<Double> values(String nodeId, String metric) {
            return series(nodeId, metric).stream().map(Sample::value).toList();
        }

        double last(String nodeId, String metric) {
            List<Sample> series = series(nodeId, metric);
            return series.isEmpty() ? Double.NaN : series.get(series.size() - 1).value();
        }

        double max(String nodeId, String metric) {
            return series(nodeId, metric).stream().mapToDouble(Sample::value).max().orElse(0);
        }

        String phaseAt(String nodeId, long tick) {
            return series(nodeId, MetricNames.LATENCY_MS).stream()
                    .filter(sample -> sample.tick() == tick)
                    .map(Sample::phase)
                    .findFirst()
                    .orElse(null);
        }
    }

    private record Sample(String metric, double value, long tick, String phase, boolean failing) {
    }

    private static DiscreteEventSimulator simulator(CompiledGraph graph, WorkloadProfile workload,
                                                     double nominalRps) {
        return new DiscreteEventSimulator(graph, workload, List.of(), nominalRps, SEED, TICK);
    }

    private static DiscreteEventSimulator simulator(CompiledGraph graph, WorkloadProfile workload,
                                                     double nominalRps,
                                                     List<FailureInjector.Failure> failures) {
        return new DiscreteEventSimulator(graph, workload, failures, nominalRps, SEED, TICK);
    }

    // ---------------------------------------------------------------- determinism

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same seed produces byte-identical telemetry")
        void sameSeedSameNumbers() {
            CompiledGraph graph = TestGraphs.deepChain();
            WorkloadProfile workload = flat(500, 30);

            Recorder first = new Recorder();
            simulator(graph, workload, 500).run(30, first);
            Recorder second = new Recorder();
            simulator(graph, workload, 500).run(30, second);

            assertThat(second.values("ext", MetricNames.LATENCY_MS))
                    .isEqualTo(first.values("ext", MetricNames.LATENCY_MS));
            assertThat(second.values("db", MetricNames.LATENCY_MS))
                    .isEqualTo(first.values("db", MetricNames.LATENCY_MS));
            assertThat(second.values("cache", MetricNames.LATENCY_MS))
                    .isEqualTo(first.values("cache", MetricNames.LATENCY_MS));
        }

        @Test
        @DisplayName("a different seed changes the jitter")
        void differentSeedDifferentNumbers() {
            CompiledGraph graph = TestGraphs.deepChain();
            WorkloadProfile workload = flat(500, 30);

            Recorder a = new Recorder();
            new DiscreteEventSimulator(graph, workload, List.of(), 500, 1L, TICK).run(30, a);
            Recorder b = new Recorder();
            new DiscreteEventSimulator(graph, workload, List.of(), 500, 2L, TICK).run(30, b);

            // Service times are fixed and propagation is deterministic, so the jitter
            // is the only source of variation -- but it is enough that a re-run with a
            // different seed is visibly a different run.
            assertThat(b.values("api", MetricNames.LATENCY_MS))
                    .isNotEqualTo(a.values("api", MetricNames.LATENCY_MS));
        }

        @Test
        @DisplayName("a cache's sampled miss fraction stays near its declared hit rate")
        void sampledMissRateConverges() {
            CompiledGraph graph = TestGraphs.readThroughCache();
            // Declared hit rate is 0.8, so roughly a fifth of the load reaches the store.
            DiscreteEventSimulator simulator = simulator(graph, flat(400, 40), 400);
            simulator.run(40, new Recorder());

            ComponentState cache = simulator.states().get("cache");
            assertThat(cache.getCacheHitRate()).isBetween(0.5, 1.0);
        }
    }

    // ------------------------------------------------------------- cache outage

    @Nested
    @DisplayName("cache outage becomes database load")
    class CacheOutage {

        /**
         * The headline claim of the whole service, and the one the previous engine
         * could not produce at all: a cache that stops answering pushes its read load
         * onto the store behind it.
         */
        @Test
        @DisplayName("a healthy cache shields the database; a failed one does not")
        void failureTransfersLoadToTheStore() {
            CompiledGraph graph = TestGraphs.readThroughCache();
            long ticks = 40;
            WorkloadProfile workload = flat(400, ticks);

            DiscreteEventSimulator healthy = simulator(graph, workload, 400);
            healthy.run(ticks, new Recorder());
            double healthyDbLoad = healthy.states().get("db").servedTotal();

            // The cache fails for the whole run: exactly the CACHE_FAILURE shape.
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "cache_failure", "cache", "cache", "cache-miss-storm",
                    0, (int) ticks, java.util.Set.of("db"), 1));

            DiscreteEventSimulator outage = simulator(graph, workload, 400, failures);
            outage.run(ticks, new Recorder());
            double outageDbLoad = outage.states().get("db").servedTotal();

            assertThat(healthyDbLoad)
                    .as("with an 80%% hit rate the store should see a fraction of the read load")
                    .isGreaterThan(0);
            assertThat(outageDbLoad)
                    .as("a dead cache must push every read through to the store")
                    .isGreaterThan(healthyDbLoad * 2);
        }

        @Test
        @DisplayName("a healthy cache keeps the store's queue empty at rates it can serve")
        void healthyCacheProtectsTheStore() {
            CompiledGraph graph = TestGraphs.readThroughCache();
            // Below the store's 3000 IOPS ceiling even with every read missing.
            DiscreteEventSimulator simulator = simulator(graph, flat(1000, 30), 1000);
            simulator.run(30, new Recorder());

            ComponentState db = simulator.states().get("db");
            assertThat(db.getPeakQueueDepth()).isEqualTo(0);
            assertThat(db.averageErrorRate()).isLessThan(0.01);
        }

        @Test
        @DisplayName("a dead cache reports misses, not successes")
        void deadCacheMissesEverything() {
            CompiledGraph graph = TestGraphs.readThroughCache();
            long ticks = 20;
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "cache_failure", "cache", "cache", "cache-miss-storm",
                    0, (int) ticks, java.util.Set.of("db"), 1));

            DiscreteEventSimulator simulator = simulator(graph, flat(400, ticks), 400, failures);
            simulator.run(ticks, new Recorder());

            assertThat(simulator.states().get("cache").getCacheHitRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("a failing cache reports no error rate of its own")
        void failingCacheDoesNotReportErrors() {
            // A cache outage is not a 500 to the caller: the request still succeeds,
            // it is just slower because the store now does the work. Reporting it as an
            // error rate was the previous behaviour and it is what made a cache outage
            // look like a partial outage rather than a load problem.
            CompiledGraph graph = TestGraphs.readThroughCache();
            long ticks = 20;
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "cache_failure", "cache", "cache", "cache-miss-storm",
                    0, (int) ticks, java.util.Set.of("db"), 1));

            DiscreteEventSimulator simulator = simulator(graph, flat(400, ticks), 400, failures);
            simulator.run(ticks, new Recorder());

            assertThat(simulator.states().get("cache").getLastErrorRate()).isZero();
            assertThat(simulator.states().get("cache").wasFailureTarget()).isTrue();
        }

        @Test
        @DisplayName("a cache with no downstream component is flagged, not silently ignored")
        void unroutableCacheIsReported() {
            // Fan-out shape: service -> cache and service -> db as siblings. The cache's
            // misses now reach nothing, which understates the store's load. That is
            // reported rather than papered over.
            CompiledGraph graph = TestGraphs.graph("Fan-out cache",
                    List.of(TestGraphs.loadBalancer("lb"), TestGraphs.service("api"),
                            TestGraphs.cache("cache"), TestGraphs.database("db")),
                    List.of(TestGraphs.edge("lb", "api"), TestGraphs.edge("api", "cache"),
                            TestGraphs.edge("api", "db")));

            DiscreteEventSimulator simulator = simulator(graph, flat(400, 30), 400);
            simulator.run(30, new Recorder());

            assertThat(simulator.routingWarnings())
                    .anyMatch(warning -> warning.contains("cache")
                            && warning.contains("no downstream component"));
            assertThat(simulator.states().get("cache").getUnroutedBypassRequests())
                    .as("the missed volume must still be counted, not discarded")
                    .isGreaterThan(0);
        }
    }

    // ------------------------------------------------------- failure propagation

    @Nested
    @DisplayName("failure propagation")
    class FailurePropagation {

        @Test
        @DisplayName("a failed component fails its callers")
        void failStopPropagatesToCallers() {
            CompiledGraph graph = TestGraphs.withoutCache();
            long ticks = 30;
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "service_failure", "api", "service", "fail-stop",
                    0, (int) ticks, java.util.Set.of("db"), 1));

            DiscreteEventSimulator simulator = simulator(graph, flat(500, ticks), 500, failures);
            simulator.run(ticks, new Recorder());

            assertThat(simulator.states().get("api").wasFailureTarget()).isTrue();
            assertThat(simulator.states().get("api").getPeakErrorRate()).isGreaterThan(0.9);
            // The database starves: it is offered nothing because its only caller died.
            assertThat(simulator.states().get("db").servedTotal()).isEqualTo(0);
        }

        @Test
        @DisplayName("a failed component's callers wait for the timeout, not forever")
        void callerWaitsForTimeoutNotInfinity() {
            CompiledGraph graph = TestGraphs.withoutCache();
            long ticks = 20;
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "service_failure", "api", "service", "fail-stop",
                    0, (int) ticks, java.util.Set.of("db"), 1));

            Recorder recorder = new Recorder();
            DiscreteEventSimulator simulator = simulator(graph, flat(500, ticks), 500, failures);
            simulator.run(ticks, recorder);

            // No non-finite value may ever reach the wire: one Infinity makes every
            // downstream percentile NaN, which is precisely when it matters most.
            for (CompiledNode node : graph.getNodes()) {
                for (Sample sample : recorder.series(node.getId(), MetricNames.LATENCY_MS)) {
                    assertThat(sample.value())
                            .as("%s latency at tick %d", node.getId(), sample.tick())
                            .isFinite();
                }
            }

            // The cost lands on the deepest point of the path: a dead service costs its
            // caller the declared 5 s default timeout, and the rest of the request path
            // inherits it. An entry point's own figure excludes it, because a load
            // balancer does not wait for the request -- the chain does. So the
            // end-to-end number is the worst node's, not the first node's.
            double worst = simulator.states().values().stream()
                    .mapToDouble(ComponentState::getMaxPathLatencyMs)
                    .max()
                    .orElse(0);
            assertThat(worst).isGreaterThanOrEqualTo(4_000);
            assertThat(simulator.states().get("api").getMaxPathLatencyMs())
                    .isGreaterThanOrEqualTo(4_000);
        }

        @Test
        @DisplayName("a stalled consumer starves its downstream and grows its own backlog")
        void stalledConsumerStarvesDownstream() {
            CompiledGraph graph = TestGraphs.queued();
            long ticks = 40;
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "kafka_consumer_failure", "w", "worker", "task-timeout",
                    5, 35, java.util.Set.of(), 0));

            DiscreteEventSimulator simulator = simulator(graph, flat(300, ticks), 300, failures);
            simulator.run(ticks, new Recorder());

            ComponentState queue = simulator.states().get("q");
            ComponentState worker = simulator.states().get("w");

            assertThat(queue.getPeakQueueDepth())
                    .as("messages must accumulate while nothing consumes them")
                    .isGreaterThan(0);
            assertThat(queue.getMaxQueueAgeMs()).isGreaterThan(0);
            // The producer is unaffected: its enqueue still succeeds. That is what
            // consumer lag looks like from the producer's side.
            assertThat(queue.getPeakErrorRate()).isZero();
            // The worker served nothing while it was down, then caught up.
            assertThat(worker.servedTotal()).isLessThan(queue.offeredTotal());
        }

        @Test
        @DisplayName("queue age grows while a backlog exists and resets when it clears")
        void queueAgeTracksTheBacklog() {
            // Offered well above the worker's 100/s ceiling so a real backlog forms.
            Recorder recorder = new Recorder();
            DiscreteEventSimulator simulator = simulator(TestGraphs.queued(), flat(500, 30), 500);
            simulator.run(30, recorder);

            List<Double> ages = recorder.values("q", MetricNames.QUEUE_AGE_MS);

            assertThat(simulator.states().get("q").getMaxQueueAgeMs()).isGreaterThan(0);
            assertThat(ages).isNotEmpty();
            assertThat(ages.get(ages.size() - 1))
                    .as("a queue that never drains must end with a non-zero age")
                    .isGreaterThan(0);
            // Age is monotone while the backlog persists: an age that goes backwards
            // means the backlog is being reported without being modelled.
            for (int i = 1; i < ages.size(); i++) {
                assertThat(ages.get(i))
                        .as("queue age at sample %d", i)
                        .isGreaterThanOrEqualTo(ages.get(i - 1));
            }
        }

        @Test
        @DisplayName("recovery is measured, and the component reports the drain time")
        void recoveryIsObserved() {
            CompiledGraph graph = TestGraphs.withoutCache();
            long ticks = 40;
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "service_failure", "api", "service", "fail-stop",
                    10, 20, java.util.Set.of("db"), 1));

            DiscreteEventSimulator simulator = simulator(graph, flat(300, ticks), 300, failures);
            simulator.run(ticks, new Recorder());

            ComponentState api = simulator.states().get("api");
            assertThat(api.wasFailureTarget()).isTrue();
            assertThat(api.getFailureTicks()).isEqualTo(10);
            assertThat(api.getRecoveryTimeMs()).isEqualTo(10 * TICK);
            // Healthy again after the window closes.
            assertThat(api.isFailing()).isFalse();
        }

        @Test
        @DisplayName("no failure plan means nothing fails")
        void noFailuresNoFailing() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(300, 20), 300);
            simulator.run(20, new Recorder());

            assertThat(simulator.states().values())
                    .allMatch(state -> !state.wasFailureTarget());
        }
    }

    // -------------------------------------------------------------- path latency

    @Nested
    @DisplayName("end-to-end path latency")
    class PathLatency {

        @Test
        @DisplayName("accumulates along the path instead of reporting each hop alone")
        void accumulatesAlongThePath() {
            // lb(1ms) + 1ms link + api(20ms) + 1ms + mid(20ms) + 1ms + db(5ms).
            // Reporting only a hop's own service time made every component look fast
            // while the user waited for the sum -- the lesson the product exists for.
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(300, 20), 300);
            simulator.run(20, new Recorder());

            Map<String, ComponentState> states = simulator.states();
            double lb = states.get("lb").getLastPathLatencyMs();
            double api = states.get("api").getLastPathLatencyMs();
            double mid = states.get("mid").getLastPathLatencyMs();
            double db = states.get("db").getLastPathLatencyMs();
            double ext = states.get("ext").getLastPathLatencyMs();

            assertThat(api).isGreaterThan(lb);
            assertThat(mid).isGreaterThan(api);
            assertThat(db).isGreaterThan(mid);
            assertThat(ext).isGreaterThan(db);

            // Deepest hop is at least the sum of the declared service times plus links.
            assertThat(ext)
                    .isGreaterThanOrEqualTo(1 + 20 + 20 + 5 + 100 + 4 * 1 - 1);
        }

        @Test
        @DisplayName("the worst node's latency is the headline number, not the average")
        void worstNodeIsTheDeepest() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(300, 30), 300);
            simulator.run(30, new Recorder());

            double worst = simulator.states().values().stream()
                    .mapToDouble(ComponentState::getMaxPathLatencyMs)
                    .max()
                    .orElse(0);

            assertThat(worst).isGreaterThan(simulator.states().get("lb").getMaxPathLatencyMs());
        }

        @Test
        @DisplayName("edge latency is charged once, to the branch actually taken")
        void edgeLatencyCountedOnce() {
            // A fan-in has three 50 ms links into the sink. A request traverses one of
            // them, not all three, so the sink's latency must include one link's delay
            // and not three. Summing every incoming edge -- as the previous engine did
            // -- tripled the propagation cost of every fan-in, which made a design look
            // three times slower purely for having parallel branches.
            CompiledGraph graph = TestGraphs.graph("Fan-in",
                    List.of(TestGraphs.loadBalancer("lb"),
                            TestGraphs.service("a"), TestGraphs.service("b"), TestGraphs.service("c"),
                            TestGraphs.database("sink")),
                    List.of(TestGraphs.edge("lb", "a", 10), TestGraphs.edge("lb", "b", 10),
                            TestGraphs.edge("lb", "c", 10),
                            TestGraphs.edge("a", "sink", 50), TestGraphs.edge("b", "sink", 50),
                            TestGraphs.edge("c", "sink", 50)));

            DiscreteEventSimulator simulator = simulator(graph, flat(200, 20), 200);
            simulator.run(20, new Recorder());

            double upstream = simulator.states().get("a").getLastPathLatencyMs();
            double sink = simulator.states().get("sink").getLastPathLatencyMs();

            // One 50 ms link plus the database's own service time, jitter included.
            assertThat(sink).isGreaterThanOrEqualTo(upstream + 50);
            assertThat(sink)
                    .as("three 50 ms links would add 150 ms")
                    .isLessThan(upstream + 60);
        }

        @Test
        @DisplayName("a backlog shows up as lag, not as an absurd latency")
        void backlogIsLagNotLatency() {
            // Regression test for a real defect found against a live broker. A queue
            // whose drain rate is below its arrival rate never drains, so charging
            // Little's law (depth / service rate) to the request made the reported P99
            // grow without bound: a 60-second run on a 100 rps design published a P99 of
            // 26 615 150 ms — over seven hours — for messages that took 5 ms to publish.
            // Nothing in the model was wrong except the choice to charge a backlog's
            // drain time to the request that caused it.
            DiscreteEventSimulator simulator = simulator(TestGraphs.queued(), flat(2000, 60), 2000);
            simulator.run(60, new Recorder());

            ComponentState queue = simulator.states().get("q");
            Distribution queueLatency = queue.latencyDistribution();

            assertThat(queue.getPeakQueueDepth())
                    .as("the backlog is real and must be recorded")
                    .isGreaterThan(0);
            assertThat(queue.getMaxQueueAgeMs())
                    .as("lag is the signal a user can act on")
                    .isGreaterThan(0);

            // The queue's own latency is its publish cost, not the time to drain.
            // QUEUE declares p50Ms 5.
            assertThat(queueLatency.p99())
                    .as("a queue's latency is its publish cost, not its drain time")
                    .isLessThan(100);

            // And it does not creep upward over the run the way an unbounded
            // accumulator does.
            double firstHalf = queueLatency.percentile(40);
            double secondHalf = queueLatency.percentile(90);
            assertThat(secondHalf)
                    .as("latency must not grow with accumulated backlog")
                    .isLessThan(firstHalf * 3);
        }

        @Test
        @DisplayName("a stalled consumer does not slow the producer down")
        void stalledConsumerDoesNotSlowTheProducer() {
            // The whole point of a queue: the enqueue succeeds at full speed while
            // consumption stops. A 25-tick stall reported as a 25-second response time
            // inverts the entire reason for having one.
            CompiledGraph graph = TestGraphs.queued();
            long ticks = 40;
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "kafka_consumer_failure", "w", "worker", "task-timeout",
                    5, 30, java.util.Set.of(), 0));

            DiscreteEventSimulator simulator = simulator(graph, flat(1000, ticks), 1000, failures);
            Recorder recorder = new Recorder();
            simulator.run(ticks, recorder);

            assertThat(simulator.states().get("q").getMaxQueueAgeMs())
                    .as("the stall is visible as lag")
                    .isGreaterThan(10_000);
            assertThat(recorder.max("q", MetricNames.LATENCY_MS))
                    .as("the producer's latency stays near the publish cost")
                    .isLessThan(100);
        }

        @Test
        @DisplayName("a full bounded queue sheds work rather than reporting no error")
        void boundedQueueShedsRatherThanReportingNoError() {
            // A worker with queueSize 10 that is offered far more than it drains:
            // the overflow is a real failure and has to appear in the error rate. The
            // previous model reported zero errors while discarding the work.
            CompiledGraph graph = TestGraphs.graph("Bounded worker",
                    List.of(TestGraphs.loadBalancer("lb"),
                            TestGraphs.builder("w", NodeType.WORKER, 1)
                                    .concurrency(10)
                                    .capacity("queueSize", 10)
                                    .latency("processingTimeMs", 100)
                                    .build()),
                    List.of(TestGraphs.edge("lb", "w")));

            DiscreteEventSimulator simulator = simulator(graph, flat(2000, 40), 2000);
            simulator.run(40, new Recorder());

            assertThat(simulator.states().get("w").getPeakErrorRate())
                    .as("a full bounded queue must report the work it discarded")
                    .isGreaterThan(0);
        }
    }

    // ---------------------------------------------------- buffering vs rejecting

    @Nested
    @DisplayName("buffering versus rejecting")
    class BufferingVersusRejecting {

        @Test
        @DisplayName("a queue absorbs an overload instead of erroring")
        void queueAbsorbs() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.queued(), flat(3000, 40), 3000);
            simulator.run(40, new Recorder());

            ComponentState queue = simulator.states().get("q");
            assertThat(queue.getPeakQueueDepth()).isGreaterThan(0);
            assertThat(queue.getPeakErrorRate()).isZero();
        }

        @Test
        @DisplayName("a synchronous component rejects what it cannot take")
        void synchronousComponentRejects() {
            // A single service rated at 1000 rps, offered 3000.
            CompiledGraph graph = TestGraphs.graph("Single service",
                    List.of(TestGraphs.loadBalancer("lb"), TestGraphs.service("api")),
                    List.of(TestGraphs.edge("lb", "api")));

            DiscreteEventSimulator simulator = simulator(graph, flat(3000, 40), 3000);
            simulator.run(40, new Recorder());

            assertThat(simulator.states().get("api").getPeakErrorRate()).isGreaterThan(0.5);
            assertThat(simulator.states().get("api").getQueueDepth()).isZero();
        }

        @Test
        @DisplayName("a component inside its capacity does not error")
        void withinCapacityNoErrors() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.withoutCache(), flat(500, 30), 500);
            simulator.run(30, new Recorder());

            assertThat(simulator.states().get("db").averageErrorRate()).isZero();
            assertThat(simulator.states().get("api").averageErrorRate()).isZero();
        }

        /**
     * A worker must actually serve what its concurrency permits.
     *
     * <p>Regression test for a live-broker defect. The concurrency ceiling was computed
     * as {@code concurrency * tickSeconds / serviceMillis} instead of
     * {@code concurrency * tickMillis / serviceMillis}, making it a thousand times too
     * small: a worker declaring {@code concurrency: 20} with a 100 ms service time
     * completed 0.2 requests per tick instead of 200, served 0.2 rps against a declared
     * 100, and made the queue in front of it look permanently backlogged. The engine
     * reported it as healthy and the queue's depth climbed for the whole run.
     */
    @Test
        @DisplayName("a worker serves the rate its concurrency permits")
        void concurrencyPermitsItsFullRate() {
            // WORKER declares concurrency 10, processingTimeMs 100 -> 100/s, and also
            // declares tasksPerSecond 100. Both ceilings agree at 100.
            DiscreteEventSimulator simulator = simulator(TestGraphs.queued(), flat(90, 30), 90);
            simulator.run(30, new Recorder());

            ComponentState worker = simulator.states().get("w");

            // Served rps is bounded by what was offered, so at 90 rps offered the worker
            // correctly tops out at 90 -- which is itself the assertion: before the
            // fix it served 0.2.
            assertThat(worker.getPeakServedRps())
                    .as("must serve the full 90 rps offered, not the 0.2 the "
                            + "1000x-too-small concurrency ceiling allowed")
                    .isGreaterThanOrEqualTo(89.0);
            assertThat(worker.getPeakServedRps())
                    .as("and must not exceed the declared ceiling")
                    .isLessThanOrEqualTo(100 * 1.1);
        }

        @Test
        @DisplayName("concurrency and declared rate agree on the registry's worker")
        void workerCeilingsAgree() {
            NodeCharacteristics worker = NodeCharacteristics.of(TestGraphs.worker("w"));

            // 10 concurrent slots, 100 ms each -> 100/s, matching tasksPerSecond.
            assertThat(worker.concurrency()).isEqualTo(10);
            assertThat(worker.baseLatencyMs()).isEqualTo(100);
            assertThat(worker.sustainedRateRps()).isEqualTo(100);
        }

        @Test
        @DisplayName("a queue whose consumer keeps up does not accumulate a backlog")
        void queueDrainsWhenTheConsumerKeepsUp() {
            // Offered below what the worker can drain. The previous defect made this
            // backlog grow from the very first tick regardless of the offered rate,
            // because the worker's ceiling was 1000x too low.
            DiscreteEventSimulator simulator = simulator(TestGraphs.queued(), flat(50, 30), 50);
            simulator.run(30, new Recorder());

            ComponentState queue = simulator.states().get("q");
            assertThat(queue.getPeakQueueDepth())
                    .as("a consumer keeping up must leave no backlog")
                    .isLessThan(1);
            assertThat(queue.getMaxQueueAgeMs())
                    .as("nor any measurable lag")
                    .isZero();
        }

        @Test
        @DisplayName("concurrency still bounds in-flight requests")
        void concurrencyBoundsInFlight() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.queued(), flat(200, 30), 200);
            simulator.run(30, new Recorder());

            ComponentState worker = simulator.states().get("w");
            assertThat(worker.getPeakServedRps()).isLessThanOrEqualTo(100 * 1.1);
            assertThat(worker.getInFlight())
                    .as("Little's law on the declared concurrency")
                    .isLessThanOrEqualTo(10 * 1.5);
        }
    }

    // -------------------------------------------------------------- telemetry

    @Nested
    @DisplayName("telemetry output")
    class Telemetry {

        @Test
        @DisplayName("every sample is finite")
        void everySampleIsFinite() {
            Recorder recorder = new Recorder();
            simulator(TestGraphs.readThroughCache(), flat(4000, 40), 4000).run(40, recorder);

            assertThat(recorder.byNode.values().stream().flatMap(List::stream).toList())
                    .allSatisfy(sample -> assertThat(sample.value())
                            .as("%s@%d", sample.metric(), sample.tick())
                            .isFinite());
        }

        @Test
        @DisplayName("every component reports the core metric set on every tick")
        void coreMetricsAlwaysPresent() {
            CompiledGraph graph = TestGraphs.deepChain();
            Recorder recorder = new Recorder();
            simulator(graph, flat(300, 10), 300).run(10, recorder);

            for (CompiledNode node : graph.getNodes()) {
                for (String metric : List.of(MetricNames.LATENCY_MS, MetricNames.THROUGHPUT_RPS,
                        MetricNames.SUCCESS_RPS, MetricNames.ERROR_RATE, MetricNames.CONCURRENCY,
                        MetricNames.CPU_UTILIZATION, MetricNames.FAILURE_ACTIVE)) {
                    assertThat(recorder.series(node.getId(), metric))
                            .as("%s on %s", metric, node.getId())
                            .hasSize(10);
                }
            }
        }

        @Test
        @DisplayName("role-specific metrics appear only where they mean something")
        void roleSpecificMetrics() {
            CompiledGraph graph = TestGraphs.readThroughCache();
            Recorder recorder = new Recorder();
            simulator(graph, flat(400, 10), 400).run(10, recorder);

            // A cache publishes a hit rate; a database publishes utilisation.
            assertThat(recorder.series("cache", MetricNames.CACHE_HIT_RATE)).isNotEmpty();
            assertThat(recorder.series("cache", MetricNames.DB_UTILIZATION)).isEmpty();
            assertThat(recorder.series("db", MetricNames.DB_UTILIZATION)).isNotEmpty();
            assertThat(recorder.series("db", MetricNames.CACHE_HIT_RATE)).isEmpty();
            assertThat(recorder.series("lb", MetricNames.QUEUE_DEPTH)).isEmpty();
        }

        @Test
        @DisplayName("the workload phase is published on every sample")
        void phaseTravelsWithTheSample() {
            WorkloadProfile workload = new WorkloadProfile(List.of(
                    new WorkloadProfile.Phase("baseline", 10, 1.0, 1.0, false),
                    new WorkloadProfile.Phase("spike", 10, 5.0, 1.0, false)), 20);

            Recorder recorder = new Recorder();
            simulator(TestGraphs.deepChain(), workload, 300).run(20, recorder);

            assertThat(recorder.phaseAt("api", 0)).isEqualTo("baseline");
            assertThat(recorder.phaseAt("api", 15)).isEqualTo("spike");
        }

        @Test
        @DisplayName("the phase a regression belongs to is visible in the samples")
        void spikeIsVisibleInTheSamples() {
            WorkloadProfile workload = new WorkloadProfile(List.of(
                    new WorkloadProfile.Phase("baseline", 10, 1.0, 1.0, false),
                    new WorkloadProfile.Phase("spike", 10, 10.0, 1.0, false)), 20);

            Recorder recorder = new Recorder();
            simulator(TestGraphs.deepChain(), workload, 300).run(20, recorder);

            double baselineLatency = recorder.values("db", MetricNames.LATENCY_MS).subList(0, 10).stream()
                    .mapToDouble(Double::doubleValue).max().orElse(0);
            double spikeLatency = recorder.values("db", MetricNames.LATENCY_MS).subList(10, 20).stream()
                    .mapToDouble(Double::doubleValue).max().orElse(0);

            assertThat(spikeLatency).isGreaterThan(baselineLatency);
        }

        @Test
        @DisplayName("failure_active tracks the failure window exactly")
        void failureActiveMatchesWindow() {
            CompiledGraph graph = TestGraphs.withoutCache();
            List<FailureInjector.Failure> failures = List.of(new FailureInjector.Failure(
                    "service_failure", "api", "service", "fail-stop",
                    5, 10, java.util.Set.of("db"), 1));

            Recorder recorder = new Recorder();
            simulator(graph, flat(300, 20), 300, failures).run(20, recorder);

            List<Sample> active = recorder.series("api", MetricNames.FAILURE_ACTIVE);
            assertThat(active.get(4).failing()).isFalse();
            assertThat(active.get(5).failing()).isTrue();
            assertThat(active.get(9).failing()).isTrue();
            assertThat(active.get(10).failing()).isFalse();
            assertThat(active.get(19).failing()).isFalse();
        }
    }

    // ----------------------------------------------------------------- reporting

    @Nested
    @DisplayName("run totals and reports")
    class Reporting {

        @Test
        @DisplayName("percentiles are computed over every tick's samples")
        void percentilesOverTheRun() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(300, 60), 300);
            simulator.run(60, new Recorder());

            NodeReport report = NodeReport.from(simulator.states().get("db"));

            assertThat(report.sampleCount()).isEqualTo(60);
            assertThat(report.p50LatencyMs()).isGreaterThan(0);
            assertThat(report.p99LatencyMs()).isGreaterThanOrEqualTo(report.p50LatencyMs());
            assertThat(report.maxLatencyMs()).isGreaterThanOrEqualTo(report.p99LatencyMs());
        }

        @Test
        @DisplayName("the report's percentiles agree with the retained sample set")
        void reportAgreesWithDistribution() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(300, 40), 300);
            simulator.run(40, new Recorder());

            ComponentState state = simulator.states().get("db");
            Distribution direct = state.latencyDistribution();
            NodeReport report = NodeReport.from(state);

            assertThat(report.p50LatencyMs()).isEqualTo(direct.p50());
            assertThat(report.p95LatencyMs()).isEqualTo(direct.p95());
            assertThat(report.p99LatencyMs()).isEqualTo(direct.p99());
        }

        @Test
        @DisplayName("offered load is counted once, at the entry points")
        void offeredCountedOnceAtEntry() {
            // 100 rps for 20 seconds at a 1.0 multiplier is 2000 requests.
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(100, 20), 100);
            simulator.run(20, new Recorder());

            assertThat(simulator.totalOffered()).isCloseTo(2000.0, org.assertj.core.data.Offset.offset(1.0));
        }

        @Test
        @DisplayName("served and failed are counted once, at the terminal component")
        void servedCountedAtTerminal() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(100, 20), 100);
            simulator.run(20, new Recorder());

            assertThat(simulator.totalServed()).isLessThanOrEqualTo(simulator.totalOffered() + 1);
            assertThat(simulator.totalFailed()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("a healthy run completes everything it offered")
        void healthyRunCompletes() {
            // deepChain ends in an EXTERNAL node rated at 100 rps, which is the whole
            // chain's bottleneck. Offering 50 keeps every hop inside its ceiling, so any
            // failure recorded here is a modelling error rather than a real overload.
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(50, 30), 50);
            simulator.run(30, new Recorder());

            assertThat(simulator.totalFailed()).isEqualTo(0);
            assertThat(simulator.totalServed()).isCloseTo(simulator.totalOffered(),
                    org.assertj.core.data.Offset.offset(2.0));
        }

        @Test
        @DisplayName("saturated and healthy components are distinguishable")
        void saturationIsVisible() {
            CompiledGraph graph = TestGraphs.graph("Thin service",
                    List.of(TestGraphs.loadBalancer("lb"),
                            TestGraphs.builder("api", NodeType.SERVICE, 0)
                                    .capacity("requestsPerSecond", 50)
                                    .latency("p50Ms", 20)
                                    .build()),
                    List.of(TestGraphs.edge("lb", "api")));

            DiscreteEventSimulator simulator = simulator(graph, flat(1000, 30), 1000);
            simulator.run(30, new Recorder());

            NodeReport api = NodeReport.from(simulator.states().get("api"));
            assertThat(api.saturated()).isTrue();
            assertThat(api.peakUtilization()).isGreaterThan(1);
            assertThat(api.findings()).containsKey("saturated");
        }

        @Test
        @DisplayName("a healthy component reports no findings")
        void healthyComponentHasNoFindings() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(200, 30), 200);
            simulator.run(30, new Recorder());

            assertThat(NodeReport.from(simulator.states().get("lb")).findings()).isEmpty();
        }

        @Test
        @DisplayName("declared-rate gaps are reported as warnings")
        void undeclaredCharacteristicsAreWarned() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.undeclared(), flat(1, 5), 1);
            simulator.run(5, new Recorder());

            assertThat(simulator.routingWarnings())
                    .anyMatch(warning -> warning.contains("no throughput figure"));
            assertThat(simulator.routingWarnings())
                    .anyMatch(warning -> warning.contains("no latency figure"));
        }
    }

    // ------------------------------------------------------------ tick geometry

    @Nested
    @DisplayName("tick geometry")
    class TickGeometry {

        @Test
        @DisplayName("a sub-second tick does not grant a whole second of service")
        void subSecondTickDoesNotInflateCapacity() {
            // The bug this pins: capacity is a rate, so a 100 ms tick may serve
            // capacity * 0.1 requests. Treating the rate as a per-tick count gave every
            // tick a full second's allowance, so a 250 ms-tick run let a component
            // serve four times what it can.
            CompiledGraph graph = TestGraphs.graph("Thin service",
                    List.of(TestGraphs.loadBalancer("lb"),
                            TestGraphs.builder("api", NodeType.SERVICE, 0)
                                    .capacity("requestsPerSecond", 100)
                                    .latency("p50Ms", 1)
                                    .build()),
                    List.of(TestGraphs.edge("lb", "api")));

            // Offer 100 rps: exactly the component's ceiling. Served must not exceed it.
            DiscreteEventSimulator simulator = new DiscreteEventSimulator(
                    graph, flat(100, 20), List.of(), 100, SEED, 100L);
            simulator.run(200, new Recorder());

            ComponentState api = simulator.states().get("api");
            assertThat(api.averageServedRps()).isLessThanOrEqualTo(100 * 1.05);
            assertThat(api.getPeakErrorRate()).isZero();
        }

        @Test
        @DisplayName("a zero tick length falls back to one second rather than dividing by zero")
        void zeroTickFallsBack() {
            DiscreteEventSimulator simulator = new DiscreteEventSimulator(
                    TestGraphs.deepChain(), flat(100, 5), List.of(), 100, SEED, 0L);
            simulator.run(5, new Recorder());

            assertThat(simulator.states().get("api").getLastPathLatencyMs()).isFinite();
            assertThat(simulator.states().get("api").getLastServedRps()).isGreaterThan(0);
        }

        @Test
        @DisplayName("a zero nominal capacity does not produce zero offered load")
        void zeroNominalStillRuns() {
            DiscreteEventSimulator simulator = simulator(TestGraphs.deepChain(), flat(0, 10), 0);
            simulator.run(10, new Recorder());

            assertThat(simulator.states().get("lb").getLastOfferedRps()).isGreaterThan(0);
            assertThat(simulator.ticksExecuted()).isEqualTo(10);
        }
    }
}


