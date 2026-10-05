package com.archforge.simulation.service;

import com.archforge.contracts.event.SimulationLifecycleEvent;
import com.archforge.contracts.event.SimulationRequestedEvent;
import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.lifecycle.SimulationStatus;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.simulation.config.SimulationProperties;
import com.archforge.simulation.engine.SimulationOutcome;
import com.archforge.simulation.support.TestGraphs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Run orchestration: what gets published, in what order, and what happens when it fails.
 *
 * <p>No broker and no Spring context. The collaborators are substituted by hand because
 * the ordering contract being tested <em>is</em> the contract with the broker — a test
 * that used an embedded broker would pass for the wrong reasons when the ordering
 * regressed, and would be slow enough that nobody would run it.
 */
class SimulationRunServiceTest {

    /** Records lifecycle events in publication order. */
    private static final class RecordingLifecyclePublisher
            extends com.archforge.simulation.event.SimulationLifecyclePublisher {

        private final List<SimulationLifecycleEvent> events = new CopyOnWriteArrayList<>();
        private boolean failNext;

        RecordingLifecyclePublisher() {
            super(null, null, new SimpleMeterRegistry());
        }

        @Override
        public boolean publish(String topic, SimulationLifecycleEvent event) {
            if (failNext) {
                return false;
            }
            events.add(event);
            return true;
        }

        List<SimulationStatus> statuses() {
            return events.stream().map(SimulationLifecycleEvent::status).toList();
        }
    }

    /** Records submission transitions in publication order. */
    private static final class RecordingSubmissionPublisher
            extends com.archforge.messaging.SubmissionLifecyclePublisher {

        private final List<SubmissionLifecycleEvent> events = new CopyOnWriteArrayList<>();

        RecordingSubmissionPublisher() {
            super(null, null, null, new SimpleMeterRegistry());
        }

        @Override
        public void publish(SubmissionLifecycleEvent event) {
            events.add(event);
        }

        List<SubmissionStatus> statuses() {
            return events.stream().map(SubmissionLifecycleEvent::status).toList();
        }
    }

    /** Collects every telemetry sample the run produced. */
    private static final class CollectingSender implements com.archforge.simulation.event.TelemetryPublisher.BatchSender {
        private final List<TelemetryEvent> received = new CopyOnWriteArrayList<>();
        private final List<String> topics = new CopyOnWriteArrayList<>();

        @Override
        public int send(String topic, List<TelemetryEvent> events) {
            topics.add(topic);
            received.addAll(events);
            return events.size();
        }
    }

    private SimulationProperties properties;
    private RecordingLifecyclePublisher lifecyclePublisher;
    private RecordingSubmissionPublisher submissionPublisher;
    private CollectingSender telemetrySender;
    private SimulationOutcomeRegistry registry;
    private SimulationRunService service;

    @BeforeEach
    void setUp() {
        properties = new SimulationProperties();
        properties.setMaxTicksPerRun(5_000);
        properties.setRetainedRuns(10);

        lifecyclePublisher = new RecordingLifecyclePublisher();
        submissionPublisher = new RecordingSubmissionPublisher();
        telemetrySender = new CollectingSender();
        registry = new SimulationOutcomeRegistry(properties);

        // Every topic from the contract, so this test cannot drift from topics.conf.
        // See KafkaTopics#withContractNames.
        var topics = com.archforge.messaging.KafkaTopics.withContractNames();

        var telemetry = new com.archforge.simulation.event.TelemetryPublisher(
                topics, properties, new SimpleMeterRegistry(), telemetrySender);
        telemetry.afterPropertiesSet();

        service = new SimulationRunService(
                new com.archforge.simulation.workload.WorkloadGenerator(),
                new com.archforge.simulation.failure.FailureInjector(),
                telemetry,
                submissionPublisher,
                lifecyclePublisher,
                topics,
                properties,
                new SimpleMeterRegistry(),
                registry);
    }

    private SimulationRequestedEvent task(String scenario, long durationSeconds) {
        return new SimulationRequestedEvent(
                "evt-1", "corr-1", "sim-1", "sub-1", "user-1", "sess-1",
                TestGraphs.readThroughCache(), scenario, durationSeconds, 1000L, 42L,
                java.util.Map.of(), Instant.now());
    }

    @Test
    @DisplayName("a successful run publishes QUEUED, RUNNING then COMPLETED, in that order")
    void lifecycleOrderIsCorrect() {
        SimulationOutcome outcome = service.run(task("normal", 30));

        assertSoftly(softly -> {
            softly.assertThat(outcome).as("outcome").isNotNull();
            softly.assertThat(lifecyclePublisher.statuses())
                    .as("lifecycle order")
                    .containsExactly(SimulationStatus.QUEUED, SimulationStatus.RUNNING,
                            SimulationStatus.COMPLETED);
        });
    }

    @Test
    @DisplayName("the submission advances to SIMULATING as the run starts")
    void submissionAdvancesToSimulating() {
        service.run(task("normal", 30));

        assertThat(submissionPublisher.statuses())
                .containsExactly(SubmissionStatus.SIMULATING);
        assertThat(submissionPublisher.events.get(0).stage())
                .isEqualTo(SubmissionLifecycleEvent.STAGE_SIMULATION);
        assertThat(submissionPublisher.events.get(0).simulationId()).isEqualTo("sim-1");
        assertThat(submissionPublisher.events.get(0).submissionId()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("every telemetry sample reaches the broker before COMPLETED is published")
    void telemetryPrecedesCompletion() {
        // The ordering is the contract with the aggregator. If COMPLETED overtakes the
        // last sample, the aggregator builds its summary from a partial trace — which is
        // how evaluation used to fire on whatever handful of messages had arrived.
        service.run(task("normal", 30));

        int telemetryIndex = telemetrySender.received.size();
        assertThat(telemetryIndex)
                .as("telemetry was published")
                .isGreaterThan(0);

        SimulationLifecycleEvent completed = lifecyclePublisher.events.stream()
                .filter(event -> event.status() == SimulationStatus.COMPLETED)
                .findFirst()
                .orElseThrow();

        assertThat(completed.totalTicks()).isGreaterThan(0);
        // The flush is synchronous against the sender, so by the time COMPLETED is
        // constructed every sample has been handed over.
        assertThat(telemetrySender.received)
                .allSatisfy(event -> assertThat(event.simulationId()).isEqualTo("sim-1"));
    }

    @Test
    @DisplayName("telemetry carries the scenario's phase on every sample")
    void telemetryCarriesPhase() {
        service.run(task("traffic_spike", 60));

        assertThat(telemetrySender.received)
                .isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.phase()).isNotBlank();
                    assertThat(event.unit()).isNotNull();
                    assertThat(event.simulationId()).isEqualTo("sim-1");
                    assertThat(event.submissionId()).isEqualTo("sub-1");
                });

        assertThat(telemetrySender.received.stream().map(TelemetryEvent::phase).distinct().toList())
                .as("the spike scenario's phases must reach the wire")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("a non-normal scenario actually injects a failure")
    void failureScenarioInjectsAndReportsIt() {
        SimulationOutcome outcome = service.run(task("cache_failure", 60));

        assertSoftly(softly -> {
            softly.assertThat(outcome).isNotNull();
            softly.assertThat(outcome.failures()).as("failure plan").isNotEmpty();
            softly.assertThat(outcome.node("cache"))
                    .as("the cache report")
                    .isNotNull();
            softly.assertThat(outcome.node("cache").wasFailureTarget())
                    .as("the cache was a target")
                    .isTrue();
        });

        assertThat(telemetrySender.received)
                .as("the failure is visible in the telemetry, not just the summary")
                .anyMatch(event -> "failure_active".equals(event.metricName())
                        && event.value() > 0);
    }

    @Test
    @DisplayName("the outcome is recorded and queryable")
    void outcomeIsRecorded() {
        service.run(task("normal", 30));

        assertThat(registry.find("sim-1")).isPresent();
        assertThat(registry.recent(10)).hasSize(1);
        assertThat(registry.recent(10).get(0).simulationId()).isEqualTo("sim-1");
    }

    @Test
    @DisplayName("the outcome carries every component's report")
    void outcomeHasEveryNode() {
        SimulationOutcome outcome = service.run(task("normal", 30));

        assertThat(outcome.nodeReports())
                .extracting(report -> report.nodeId())
                .containsExactlyInAnyOrder("lb", "api", "cache", "db");
        assertThat(outcome.node("db").p99LatencyMs()).isGreaterThan(0);
        assertThat(outcome.worstNodeP99Ms()).isGreaterThan(0);
    }

    @Test
    @DisplayName("the graph's own compilation warnings are carried into the outcome")
    void graphWarningsAreCarried() {
        var warned = TestGraphs.graph("Warned",
                List.of(TestGraphs.loadBalancer("lb"), TestGraphs.service("api")),
                List.of(TestGraphs.edge("lb", "api")));
        // Rebuild with a warning the compiler would have attached.
        var withWarning = com.archforge.contracts.graph.CompiledGraph.builder()
                .name("Warned")
                .nodes(warned.getNodes())
                .edges(warned.getEdges())
                .warnings(List.of("Node 'api' has no declared failure modes"))
                .build();

        SimulationRequestedEvent task = new SimulationRequestedEvent(
                "evt", "corr", "sim-w", "sub-w", "user", null, withWarning,
                "normal", 20, 1000L, 1L, java.util.Map.of(), Instant.now());

        SimulationOutcome outcome = service.run(task);

        assertThat(outcome.warnings())
                .contains("Node 'api' has no declared failure modes");
    }

    @Test
    @DisplayName("a run with no telemetry is reported as a warning, not silently accepted")
    void noTelemetryIsWarned() {
        // A run that produced no samples has produced no measurements. Saying so is
        // the difference between a bug report and a confident wrong number.
        var emptyGraph = TestGraphs.graph("Empty", List.of(), List.of());
        SimulationRequestedEvent task = new SimulationRequestedEvent(
                "evt", "corr", "sim-e", "sub-e", "user", null, emptyGraph,
                "normal", 20, 1000L, 1L, java.util.Map.of(), Instant.now());

        service.run(task);

        assertThat(registry.find("sim-e")).isEmpty();
        assertThat(registry.findFailure("sim-e"))
                .as("an empty graph cannot be simulated")
                .isPresent();
        assertThat(registry.findFailure("sim-e").orElseThrow().message())
                .contains("no compiled graph");
    }

    @Test
    @DisplayName("a task with no graph is rejected with a terminal lifecycle event")
    void noGraphIsRejected() {
        SimulationRequestedEvent task = new SimulationRequestedEvent(
                "evt", "corr", "sim-n", "sub-n", "user", null, null,
                "normal", 20, 1000L, 1L, java.util.Map.of(), Instant.now());

        SimulationOutcome outcome = service.run(task);

        assertSoftly(softly -> {
            softly.assertThat(outcome).as("no outcome for a rejected task").isNull();
            softly.assertThat(lifecyclePublisher.statuses())
                    .contains(SimulationStatus.REJECTED);
            softly.assertThat(submissionPublisher.statuses())
                    .contains(SubmissionStatus.FAILED);
        });
    }

    @Test
    @DisplayName("an unresolvable scenario is rejected rather than degraded to NORMAL")
    void unknownScenarioIsRejected() {
        // SimulationRequestedEvent.scenarioType() answers NORMAL for anything it
        // cannot parse. That is right for a default and wrong for an explicit request:
        // a typo would otherwise run a steady-state simulation and report it as a flash
        // sale. Every one of the nine scenarios used to degrade exactly this way.
        SimulationOutcome outcome = service.run(task("flashsale", 30));

        assertThat(outcome).isNull();
        assertThat(lifecyclePublisher.statuses()).contains(SimulationStatus.REJECTED);
        assertThat(registry.findFailure("sim-1").orElseThrow().message())
                .contains("Unknown scenario")
                .contains("flash_sale");
    }

    @Test
    @DisplayName("an oversized graph is rejected before any tick executes")
    void oversizedGraphIsRejected() {
        properties.setMaxNodes(1);

        SimulationOutcome outcome = service.run(task("normal", 30));

        assertThat(outcome).isNull();
        assertThat(registry.findFailure("sim-1").orElseThrow().message())
                .contains("the limit is 1");
        assertThat(telemetrySender.received).as("nothing was simulated").isEmpty();
    }

    @Test
    @DisplayName("the tick budget is clamped to the configured ceiling")
    void tickBudgetIsClamped() {
        properties.setMaxTicksPerRun(10);

        var workload = new com.archforge.simulation.workload.WorkloadGenerator()
                .generate(TestGraphs.readThroughCache(),
                        com.archforge.contracts.lifecycle.ScenarioType.NORMAL, 600);

        // 600 s at a 1 s tick would be 600 ticks; the ceiling is 10. Without the clamp a
        // record can dictate an unbounded run.
        assertThat(service.tickBudget(task("normal", 600), workload)).isEqualTo(10);
    }

    @Test
    @DisplayName("the tick budget is at least one, never zero")
    void tickBudgetIsAtLeastOne() {
        properties.setMaxTicksPerRun(0);
        var workload = new com.archforge.simulation.workload.WorkloadGenerator()
                .generate(TestGraphs.readThroughCache(),
                        com.archforge.contracts.lifecycle.ScenarioType.NORMAL, 10);

        assertThat(service.tickBudget(task("normal", 10), workload)).isEqualTo(1);
    }

    @Test
    @DisplayName("the sample ceiling clamps the tick count, because the tick ceiling alone does not")
    void sampleCeilingClampsTheTickCount() {
        // A tick ceiling bounds nothing useful on its own. The engine emits 7-11 samples
        // per node per tick, so the largest combination the other two settings accept --
        // maxTicksPerRun ticks of maxNodes nodes -- asks for 500 x 20000 x ~9 = 90 million
        // samples into a 200 000 buffer. At that point 0.22% of the trace survives and the
        // run still reports COMPLETED, which is the worst outcome available: a confident,
        // complete-looking report built from almost nothing.
        properties.setMaxTicksPerRun(20_000);
        properties.setMaxSamplesPerRun(10_000);
        properties.setMetricsPerNode(9);

        // 20 nodes x 9 metrics = 180 samples per tick, so 10 000 samples buys 55 ticks.
        var big = TestGraphs.graph("Twenty nodes", twentyServices(), twentyEdges());
        var workload = new com.archforge.simulation.workload.WorkloadGenerator()
                .generate(big, com.archforge.contracts.lifecycle.ScenarioType.NORMAL, 600);

        long ticks = service.tickBudget(taskFor(big, "sim-big", 600, 1000L), workload);

        assertThat(ticks)
                .as("clamped to the sample budget, not the 20000 tick ceiling")
                .isEqualTo(10_000 / (20L * 9));
    }

    @Test
    @DisplayName("the sample ceiling scales with the graph, so a small design is not throttled")
    void sampleCeilingAdaptsToGraphSize() {
        // The ceiling is derived per run from the graph's node count, so the documented
        // 500-node worst case does not penalise a four-node design into a fraction of its
        // intended duration.
        properties.setMaxTicksPerRun(20_000);
        properties.setMaxSamplesPerRun(10_000);
        properties.setMetricsPerNode(9);

        var workload = new com.archforge.simulation.workload.WorkloadGenerator()
                .generate(TestGraphs.readThroughCache(),
                        com.archforge.contracts.lifecycle.ScenarioType.NORMAL, 600);

        // 4 nodes x 9 = 36 samples per tick, so this budget buys 277 ticks rather than the
        // 55 a 20-node graph gets from the same 10 000 samples. The ceiling tracks the
        // graph rather than assuming the largest one it will ever be handed.
        assertThat(service.tickBudget(task("normal", 600), workload))
                .as("a four-node design still gets 6.6x the ticks a twenty-node one does")
                .isEqualTo(10_000 / (4L * 9));
        assertThat(service.tickBudget(task("normal", 600), workload))
                .as("and comfortably more than the 55 ticks the twenty-node graph is limited to")
                .isGreaterThan(10_000 / (20L * 9));
    }

    @Test
    @DisplayName("the tick budget is never zero or negative, whatever the settings")
    void tickBudgetAlwaysPositive() {
        properties.setMaxTicksPerRun(0);
        properties.setMaxSamplesPerRun(0);
        properties.setMetricsPerNode(0);

        var workload = new com.archforge.simulation.workload.WorkloadGenerator()
                .generate(TestGraphs.readThroughCache(),
                        com.archforge.contracts.lifecycle.ScenarioType.NORMAL, 10);

        assertThat(service.tickBudget(task("normal", 10), workload))
                .as("a zero-tick run would report COMPLETED having measured nothing")
                .isGreaterThanOrEqualTo(1);
    }

    private static List<com.archforge.contracts.graph.CompiledNode> twentyServices() {
        var nodes = new java.util.ArrayList<com.archforge.contracts.graph.CompiledNode>();
        nodes.add(TestGraphs.loadBalancer("lb"));
        for (int i = 0; i < 19; i++) {
            nodes.add(TestGraphs.service("api" + i));
        }
        return nodes;
    }

    private static List<com.archforge.contracts.graph.CompiledEdge> twentyEdges() {
        var edges = new java.util.ArrayList<com.archforge.contracts.graph.CompiledEdge>();
        edges.add(TestGraphs.edge("lb", "api0"));
        for (int i = 0; i < 18; i++) {
            edges.add(TestGraphs.edge("api" + i, "api" + (i + 1)));
        }
        return edges;
    }

    private static SimulationRequestedEvent taskFor(
            com.archforge.contracts.graph.CompiledGraph graph,
            String simulationId, long durationSeconds, long tickMillis) {
        return new SimulationRequestedEvent(
                "evt", "corr", simulationId, "sub", "user", null, graph,
                "normal", durationSeconds, tickMillis, 42L, java.util.Map.of(), Instant.now());
    }

    @Test
    @DisplayName("a lifecycle publish failure does not lose the run")
    void lifecycleFailureDoesNotLoseTheRun() {
        // A lost COMPLETED leaves the aggregator waiting and the submission stuck at
        // AGGREGATING. It must be logged loudly, but the telemetry is already on the
        // wire and throwing here would discard a finished run's outcome.
        lifecyclePublisher.failNext = true;

        SimulationOutcome outcome = service.run(task("normal", 30));

        assertThat(outcome).isNotNull();
        assertThat(lifecyclePublisher.events)
                .as("the first publish was refused")
                .isEmpty();
        assertThat(registry.find("sim-1")).as("the run is still recorded").isPresent();
    }

    @Test
    @DisplayName("counters reflect what happened")
    void countersTrackRuns() {
        service.run(task("normal", 30));
        service.run(task("flashsale", 30));

        assertSoftly(softly -> {
            softly.assertThat(service.runsCompleted()).as("completed").isEqualTo(1);
            softly.assertThat(service.runsFailed()).as("failed").isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the run never throws, whatever the task")
    void runNeverThrows() {
        // The consumer hands off to a worker thread, so an escaping exception kills the
        // thread silently and the submission stays at SIMULATING forever.
        List<SimulationRequestedEvent> hostile = new ArrayList<>();
        hostile.add(task("normal", 30));
        hostile.add(new SimulationRequestedEvent(
                "evt", "corr", "sim-x", "sub-x", "u", null, null, "normal",
                60, 1000L, 1L, java.util.Map.of(), Instant.now()));
        hostile.add(new SimulationRequestedEvent(
                "evt", "corr", "sim-y", "sub-y", "u", null, TestGraphs.singleNode(),
                "nonsense", 60, 1000L, 1L, java.util.Map.of(), Instant.now()));
        hostile.add(new SimulationRequestedEvent(
                "evt", "corr", "sim-z", "sub-z", "u", null,
                TestGraphs.graph("Empty", List.of(), List.of()),
                "normal", 60, 1000L, 1L, java.util.Map.of(), Instant.now()));

        for (SimulationRequestedEvent task : hostile) {
            assertThatCode(() -> service.run(task))
                    .as("run of %s must not throw", task.simulationId())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the engine's summary projection matches the outcome it came from")
    void summaryProjectionMatchesTheOutcome() {
        SimulationOutcome outcome = service.run(task("cache_failure", 60));

        var summary = outcome.toSummaryEvent();

        assertSoftly(softly -> {
            softly.assertThat(summary.simulationId()).isEqualTo("sim-1");
            softly.assertThat(summary.submissionId()).isEqualTo("sub-1");
            softly.assertThat(summary.scenarioType())
                    .isEqualTo(com.archforge.contracts.lifecycle.ScenarioType.CACHE_FAILURE);
            softly.assertThat(summary.nodeSummaries()).hasSize(4);
            softly.assertThat(summary.failures()).isNotEmpty();
            softly.assertThat(summary.totals().p99LatencyMs())
                    .isEqualTo(outcome.worstNodeP99Ms());
            softly.assertThat(summary.architecture()).isNotNull();
            softly.assertThat(summary.architecture().nodes()).hasSize(4);
            softly.assertThat(summary.startedAt()).isNotNull();
            softly.assertThat(summary.completedAt()).isNotNull();
        });
    }
}
