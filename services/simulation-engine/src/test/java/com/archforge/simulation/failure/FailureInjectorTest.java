package com.archforge.simulation.failure;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.simulation.support.TestGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Failure-site selection.
 *
 * <p>The class exists because the previous injector was broken in a way that could
 * never surface: it constructed a fresh injector per node and passed {@code null} as
 * the graph, so the first scenario that actually injected a failure threw
 * {@code NullPointerException}, killed the tick loop, and the run was still reported as
 * {@code COMPLETED}. It stayed invisible because {@code scenarioType} was never
 * populated, so no scenario ever injected anything.
 *
 * <p>Determinism is the other half. Site selection is seeded, so submitting the same
 * design twice produces the same failures and a P99 can be compared against a P99.
 */
class FailureInjectorTest {

    private final FailureInjector injector = new FailureInjector();

    @Test
    @DisplayName("a steady-state scenario injects nothing")
    void normalInjectsNothing() {
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.deepChain(), ScenarioType.NORMAL, 42L, 60);

        assertThat(plan).isEmpty();
    }

    @Test
    @DisplayName("a traffic scenario injects no failure either")
    void trafficScenarioInjectsNothing() {
        // Load-only scenarios must not take components down; otherwise the latency the
        // user sees is confounded and the comparison with NORMAL is meaningless.
        assertThat(injector.plan(TestGraphs.deepChain(), ScenarioType.TRAFFIC_SPIKE, 1L, 60)).isEmpty();
        assertThat(injector.plan(TestGraphs.deepChain(), ScenarioType.FLASH_SALE, 1L, 60)).isEmpty();
        assertThat(injector.plan(TestGraphs.deepChain(), ScenarioType.TRAFFIC_DROP, 1L, 60)).isEmpty();
    }

    @Test
    @DisplayName("a compute failure targets a compute component")
    void computeFailureTargetsCompute() {
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.deepChain(), ScenarioType.SERVICE_FAILURE, 42L, 60);

        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(failure ->
                assertThat(NodeType.findByValue(failure.nodeType()))
                        .isIn(NodeType.SERVICE, NodeType.WORKER, NodeType.FUNCTION));
    }

    @Test
    @DisplayName("a storage failure targets storage, not a cache")
    void storageFailureTargetsStorage() {
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.readThroughCache(), ScenarioType.DATABASE_OVERLOAD, 42L, 60);

        assertThat(plan).isNotEmpty();
        // A cache is categorised as storage. Taking the cache down for a "database
        // saturation" scenario would test the wrong component.
        assertThat(plan).allSatisfy(failure ->
                assertThat(failure.nodeType()).isEqualTo(NodeType.DATABASE.getValue()));
    }

    @Test
    @DisplayName("a cache failure targets the cache")
    void cacheFailureTargetsCache() {
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.readThroughCache(), ScenarioType.CACHE_FAILURE, 42L, 60);

        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(failure ->
                assertThat(failure.nodeType()).isEqualTo(NodeType.CACHE.getValue()));
    }

    @Test
    @DisplayName("a messaging failure targets the queue")
    void messagingFailureTargetsQueue() {
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.queued(), ScenarioType.KAFKA_CONSUMER_FAILURE, 42L, 60);

        assertThat(plan).isNotEmpty();
        assertThat(plan.get(0).nodeType()).isEqualTo(NodeType.QUEUE.getValue());
    }

    @Test
    @DisplayName("the same seed selects the same components, twice")
    void sameSeedSameSites() {
        CompiledGraph graph = TestGraphs.graph("Many services",
                List.of(TestGraphs.loadBalancer("lb"),
                        TestGraphs.service("a"), TestGraphs.service("b"),
                        TestGraphs.service("c"), TestGraphs.service("d")),
                List.of(TestGraphs.edge("lb", "a"), TestGraphs.edge("lb", "b"),
                        TestGraphs.edge("lb", "c"), TestGraphs.edge("lb", "d")));

        List<FailureInjector.Failure> first = injector.plan(graph, ScenarioType.REGION_FAILURE, 7L, 60);
        List<FailureInjector.Failure> second = injector.plan(graph, ScenarioType.REGION_FAILURE, 7L, 60);

        assertThat(first.stream().map(FailureInjector.Failure::nodeId).toList())
                .isEqualTo(second.stream().map(FailureInjector.Failure::nodeId).toList());
    }

    /**
     * Seeded selection, not "any one will do".
     *
     * <p>Asserted as "the two seeds together cover more than one node" rather than "they
     * differ": with {@code nextInt()} even the shuffler is permitted to leave the order
     * untouched, and on a small target list several seeds genuinely coincide. The
     * property worth having is that selection is seed-driven and covers the
     * population — a plan that always picked the first target would pass a test that
     * only compared two seeds for inequality.
     */
    @Test
    @DisplayName("selection is seed-driven and covers the population across seeds")
    void selectionIsSeedDrivenAndCoversThePopulation() {
        CompiledGraph graph = TestGraphs.graph("Many services",
                List.of(TestGraphs.loadBalancer("lb"),
                        TestGraphs.service("a"), TestGraphs.service("b"),
                        TestGraphs.service("c"), TestGraphs.service("d"),
                        TestGraphs.service("e"), TestGraphs.service("f"),
                        TestGraphs.service("g"), TestGraphs.service("h")),
                List.of(TestGraphs.edge("lb", "a"), TestGraphs.edge("lb", "b"),
                        TestGraphs.edge("lb", "c"), TestGraphs.edge("lb", "d"),
                        TestGraphs.edge("lb", "e"), TestGraphs.edge("lb", "f"),
                        TestGraphs.edge("lb", "g"), TestGraphs.edge("lb", "h")));

        java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            chosen.addAll(injector.plan(graph, ScenarioType.REGION_FAILURE, seed, 60).stream()
                    .map(FailureInjector.Failure::nodeId)
                    .toList());
        }

        // 8 services, 0.33 fraction -> 3 per run. Across seeds every service must come
        // up; a plan that always took the first one would show up here immediately.
        assertThat(chosen)
                .as("every service is reachable as a failure target across seeds")
                .containsExactlyInAnyOrder("a", "b", "c", "d", "e", "f", "g", "h");
    }

    @Test
    @DisplayName("the failure fraction caps how many components go down")
    void fractionCapsTheCount() {
        CompiledGraph graph = TestGraphs.graph("Many services",
                List.of(TestGraphs.loadBalancer("lb"),
                        TestGraphs.service("a"), TestGraphs.service("b"),
                        TestGraphs.service("c"), TestGraphs.service("d"),
                        TestGraphs.service("e"), TestGraphs.service("f")),
                List.of(TestGraphs.edge("lb", "a"), TestGraphs.edge("lb", "b"),
                        TestGraphs.edge("lb", "c"), TestGraphs.edge("lb", "d"),
                        TestGraphs.edge("lb", "e"), TestGraphs.edge("lb", "f")));

        List<FailureInjector.Failure> plan =
                injector.plan(graph, ScenarioType.REGION_FAILURE, 3L, 60);

        // 6 services * 0.33 = 1.98, rounded up to 2.
        assertThat(plan).hasSize(2);
    }

    @Test
    @DisplayName("a monitoring component is never chosen, even for an ALL request")
    void monitoringIsNeverChosen() {
        CompiledGraph graph = TestGraphs.graph("With monitoring",
                List.of(TestGraphs.loadBalancer("lb"), TestGraphs.service("api"),
                        TestGraphs.monitoring("grafana")),
                List.of(TestGraphs.edge("lb", "api"), TestGraphs.edge("api", "grafana")));

        List<FailureInjector.Failure> plan = injector.plan(graph, ScenarioType.REGION_FAILURE, 5L, 60);

        // A dashboard going down is not something a caller observes, so it is not a
        // meaningful failure to inject or to report a blast radius for.
        assertThat(plan).noneMatch(failure -> "grafana".equals(failure.nodeId()));
    }

    @Test
    @DisplayName("a target category with no match widens rather than injecting nothing")
    void widensWhenNothingMatches() {
        // Only storage in the graph, and the scenario asks for compute. Reporting
        // nothing would hand the user a CACHE- or COMPUTE-labelled run that broke
        // nothing.
        CompiledGraph graph = TestGraphs.graph("Storage only",
                List.of(TestGraphs.loadBalancer("lb"), TestGraphs.database("db")),
                List.of(TestGraphs.edge("lb", "db")));

        List<FailureInjector.Failure> plan =
                injector.plan(graph, ScenarioType.SERVICE_FAILURE, 11L, 60);

        assertThat(plan).isNotEmpty();
    }

    @Test
    @DisplayName("an empty graph injects nothing")
    void emptyGraphInjectsNothing() {
        assertThat(injector.plan(null, ScenarioType.SERVICE_FAILURE, 1L, 60)).isEmpty();
        assertThat(injector.plan(TestGraphs.graph("Empty", List.of(), List.of()),
                ScenarioType.SERVICE_FAILURE, 1L, 60)).isEmpty();
    }

    @Test
    @DisplayName("the window fits inside the run")
    void windowFitsInsideTheRun() {
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.deepChain(), ScenarioType.SERVICE_FAILURE, 42L, 30);

        assertThat(plan).allSatisfy(failure -> assertSoftly(softly -> {
            softly.assertThat(failure.startTick()).as("start tick").isPositive();
            softly.assertThat(failure.startTick()).as("start tick").isLessThan(30);
            softly.assertThat(failure.endTick()).as("end tick").isLessThanOrEqualTo(30);
            softly.assertThat(failure.durationTicks()).as("duration").isPositive();
        }));
    }

    @Test
    @DisplayName("a very short run still gets a window inside it")
    void veryShortRun() {
        int ticks = 4;
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.deepChain(), ScenarioType.SERVICE_FAILURE, 42L, ticks);

        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(failure -> {
            assertThat(failure.startTick()).as("start").isLessThan(ticks);
            assertThat(failure.endTick()).as("end").isLessThanOrEqualTo(ticks);
            assertThat(failure.durationTicks()).as("duration").isPositive();
            // The window has to actually fire inside the run. A plan whose window
            // falls entirely past the last tick is a scenario that silently tested
            // nothing -- which is what happened to every scenario before the field
            // was populated at all.
            assertThat(java.util.stream.IntStream.range(0, ticks)
                    .anyMatch(failure::isActiveAt))
                    .as("the failure must be active for at least one tick of the run")
                    .isTrue();
        });
    }

    /**
 * Widening must not pick the load balancer.
 *
 * <p>Failing ingress means nothing downstream is offered any load, every percentile
 * reads as the floor, and the user is shown a clean report for an architecture that
 * never received a request. That reads as "your design handles this fine" — worse than
 * useless, because it is a positive claim about a design that was never exercised.
 */
    @Test
    @DisplayName("widening a storage-only request skips the load balancer")
    void wideningSkipsEntryPoints() {
        CompiledGraph graph = TestGraphs.graph("Ingress plus storage",
                List.of(TestGraphs.loadBalancer("lb"), TestGraphs.database("db")),
                List.of(TestGraphs.edge("lb", "db")));

        // SERVICE_FAILURE targets compute, which this graph lacks, so the search widens.
        List<FailureInjector.Failure> plan =
                injector.plan(graph, ScenarioType.SERVICE_FAILURE, 17L, 60);

        assertThat(plan).isNotEmpty();
        assertThat(plan).noneMatch(failure -> "lb".equals(failure.nodeId()));
    }

    @Test
    @DisplayName("a network failure may legitimately target the load balancer")
    void networkFailureTargetsTheNetwork() {
        // Deliberately not an entry-point exclusion: the scenario asked for the network,
        // the load balancer is the network, and degrading it is the thing under test.
        // Excluding it would leave the scenario with nothing to do.
        CompiledGraph graph = TestGraphs.graph("Ingress plus services",
                List.of(TestGraphs.loadBalancer("lb"),
                        TestGraphs.service("a"), TestGraphs.service("b")),
                List.of(TestGraphs.edge("lb", "a"), TestGraphs.edge("lb", "b")));

        List<FailureInjector.Failure> plan =
                injector.plan(graph, ScenarioType.NETWORK_DEGRADATION, 17L, 60);

        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(failure ->
                assertThat(failure.nodeType()).isEqualTo(NodeType.LOAD_BALANCER.getValue()));
    }

    @Test
    @DisplayName("a graph of only an entry point still gets a failure, and says so")
    void entryPointOnlyGraphStillFails() {
        // The honest last resort: an architecture with no interior component cannot
        // express the scenario any other way.
        CompiledGraph graph = TestGraphs.graph("Ingress only",
                List.of(TestGraphs.loadBalancer("lb")), List.of());

        List<FailureInjector.Failure> plan =
                injector.plan(graph, ScenarioType.SERVICE_FAILURE, 5L, 60);

        assertThat(plan).isNotEmpty();
        assertThat(plan.get(0).nodeId()).isEqualTo("lb");
    }

    @Test
    @DisplayName("the window is half-open, so no tick is counted twice")
    void windowIsHalfOpen() {
        FailureInjector.Failure failure = new FailureInjector.Failure(
                "service_failure", "api", "service", "fail-stop",
                5, 10, java.util.Set.of(), 0);

        assertThat(failure.isActiveAt(4)).isFalse();
        assertThat(failure.isActiveAt(5)).isTrue();
        assertThat(failure.isActiveAt(9)).isTrue();
        assertThat(failure.isActiveAt(10)).isFalse();
        assertThat(failure.durationTicks()).isEqualTo(5);
    }

    @Test
    @DisplayName("the failure mode is one the component actually declares")
    void modeIsOneTheNodeDeclares() {
        List<FailureInjector.Failure> plan =
                injector.plan(TestGraphs.readThroughCache(), ScenarioType.CACHE_FAILURE, 42L, 60);

        CompiledGraph graph = TestGraphs.readThroughCache();
        assertThat(plan).allSatisfy(failure -> {
            CompiledNode node = graph.node(failure.nodeId()).orElseThrow();
            if (!node.getFailureModes().isEmpty()) {
                assertThat(node.getFailureModes()).contains(failure.failureMode());
            }
        });
    }

    @Test
    @DisplayName("affected components are everything downstream, transitively")
    void affectedComponentsAreTransitive() {
        CompiledGraph graph = TestGraphs.deepChain();

        // db -> ext, so db's blast radius includes ext.
        java.util.Set<String> affected = injector.affectedComponents(graph, "db");

        assertThat(affected).contains("db", "ext");
        assertThat(affected).doesNotContain("lb", "api", "mid");
    }

    @Test
    @DisplayName("a leaf's blast radius is itself alone")
    void leafBlastRadius() {
        assertThat(injector.affectedComponents(TestGraphs.deepChain(), "ext"))
                .containsExactly("ext");
    }

    @Test
    @DisplayName("affected components are computed without dereferencing a null graph")
    void nullGraphIsSafe() {
        // The previous implementation called getAffectedNodes(failure, null) and then
        // dereferenced the argument, aborting the whole simulation with an NPE that was
        // then reported as a completed run.
        assertThat(injector.affectedComponents(null, "api")).isEmpty();
        assertThat(injector.affectedComponents(TestGraphs.deepChain(), null)).isEmpty();
    }

    @Test
    @DisplayName("the blast radius counts direct dependents, not transitive ones")
    void blastRadiusIsDirect() {
        CompiledGraph graph = TestGraphs.deepChain();
        List<FailureInjector.Failure> plan =
                injector.plan(graph, ScenarioType.REGION_FAILURE, 13L, 60);

        // REGION_FAILURE targets storage, and deepChain has exactly one: db. Its direct
        // dependent is ext; affectedComponents reaches further. The two must not be
        // conflated or the impact is double-counted in the report.
        assertThat(plan).hasSize(1);
        FailureInjector.Failure failure = plan.get(0);
        assertThat(failure.blastRadius()).isEqualTo(1);
        assertThat(failure.affectedNodeIds()).containsExactly("db", "ext");
    }

    @ParameterizedTest
    @EnumSource(ScenarioType.class)
    @DisplayName("no scenario throws, whatever the graph")
    void noScenarioThrows(ScenarioType scenario) {
        for (CompiledGraph graph : List.of(TestGraphs.deepChain(), TestGraphs.queued(),
                TestGraphs.singleNode(), TestGraphs.undeclared())) {
            assertThat(injector.plan(graph, scenario, 1L, 60))
                    .as("plan for %s on %s", scenario, graph.getName())
                    .isNotNull();
        }
    }
}
