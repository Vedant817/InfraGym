package com.archforge.simulation.engine;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.simulation.failure.FailureInjector;
import com.archforge.simulation.support.TestGraphs;
import com.archforge.simulation.workload.WorkloadProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.assertj.core.data.Offset.offset;

class RequestAccountingTest {

    private static final TickObserver NOOP = (node, state, metric, value, tick, phase, failing) -> { };

    @ParameterizedTest
    @CsvSource({"client,false", "client,true", "lb,false", "lb,true", "gateway,false", "gateway,true"})
    void sevenNodeDesignConservesRequestsRejectedOrLostBeforeAnotherEntryPoint(String bottleneck, boolean outage) {
        CompiledGraph graph = TestGraphs.graph("Seven-node accounting regression",
                List.of(TestGraphs.builder("client", NodeType.CLIENT, 0)
                                .capacity("requestsPerSecond", bottleneck.equals("client") ? 1 : 1000)
                                .latency("p50Ms", 1).build(),
                        TestGraphs.builder("lb", NodeType.LOAD_BALANCER, 0)
                                .capacity("requestsPerSecond", bottleneck.equals("lb") ? 1 : 1000)
                                .latency("p50Ms", 1).build(),
                        TestGraphs.builder("gateway", NodeType.GATEWAY, 0)
                                .capacity("requestsPerSecond", bottleneck.equals("gateway") ? 1 : 1000)
                                .latency("p50Ms", 1).build(),
                        TestGraphs.cache("cache"), TestGraphs.queue("queue"),
                        TestGraphs.worker("worker"), TestGraphs.database("db")),
                List.of(TestGraphs.edge("client", "lb"), TestGraphs.edge("lb", "gateway"),
                        TestGraphs.edge("gateway", "cache"), TestGraphs.edge("gateway", "queue"),
                        TestGraphs.edge("queue", "worker"), TestGraphs.edge("worker", "db")));
        DiscreteEventSimulator simulator = simulator(graph, outage ? List.of(
                new FailureInjector.Failure("outage", bottleneck, "entry point", "fail-stop", 10, 35, Set.of(), 0))
                : List.of());
        simulator.run(60, NOOP);

        assertSoftly(softly -> {
            assertConserved(softly, simulator, bottleneck + ", outage=" + outage);
            softly.assertThat(simulator.totalFailed()).as("the bottleneck actually rejects traffic").isPositive();
            softly.assertThat(simulator.states().get("lb").offeredTotal())
                    .as("load balancer receives only what the client forwards")
                    .isCloseTo(simulator.states().get("client").servedTotal(), offset(1e-8));
            softly.assertThat(simulator.states().get("gateway").offeredTotal())
                    .as("gateway receives only what the load balancer forwards")
                    .isCloseTo(simulator.states().get("lb").servedTotal(), offset(1e-8));
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cacheHitsAndBacklogAreDisjointEvenWhenAnOverloadedCacheFails(boolean terminal, boolean outage) {
        var cache = TestGraphs.builder("cache", NodeType.CACHE, 0)
                .throughput("operationsPerSecond", 1).latency("p50Ms", 1)
                .capacity("queueSize", 5).property("hitRate", 0.8).build();
        CompiledGraph graph = TestGraphs.graph("Overloaded cache",
                terminal ? List.of(TestGraphs.loadBalancer("lb"), cache)
                        : List.of(TestGraphs.loadBalancer("lb"), cache, TestGraphs.database("db")),
                terminal ? List.of(TestGraphs.edge("lb", "cache"))
                        : List.of(TestGraphs.edge("lb", "cache"), TestGraphs.edge("cache", "db")));
        DiscreteEventSimulator simulator = simulator(graph, outage ? List.of(
                new FailureInjector.Failure("cache_failure", "cache", "cache", "bypass", 10, 35, Set.of(), 0))
                : List.of());
        assertSoftly(softly -> {
            double[] previousBacklog = {0};
            simulator.run(60, (node, state, metric, value, tick, phase, failing) -> {
                if (node.getId().equals("cache") && metric.equals(MetricNames.LATENCY_MS)) {
                    if (!terminal && failing) {
                        softly.assertThat(state.getLastErrorRate()).as("cache outage tick %s", tick).isZero();
                        softly.assertThat(state.getLastBypassRps())
                                .as("outage forwards arrivals and queued lookups at tick %s", tick)
                                .isCloseTo(state.getLastOfferedRps() + previousBacklog[0], offset(1e-8));
                    }
                    previousBacklog[0] = state.getQueueDepth();
                }
            });
            assertConserved(softly, simulator, "terminal=" + terminal + ", outage=" + outage);
            softly.assertThat(simulator.residualBacklog()).as("queued work remains backlogged").isPositive();
        });
    }

    @Test
    void queueRetainsWorkWhenItsConsumerFails() {
        CompiledGraph graph = TestGraphs.queued();
        DiscreteEventSimulator simulator = simulator(graph, List.of(
                new FailureInjector.Failure("consumer_stall", "w", "worker", "stall", 0, 60, Set.of(), 0)));
        simulator.run(60, NOOP);
        assertSoftly(softly -> {
            assertConserved(softly, simulator, "stalled consumer");
            softly.assertThat(simulator.totalFailed()).isZero();
            softly.assertThat(simulator.residualBacklog()).isCloseTo(simulator.totalOffered(), offset(1e-8));
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {250, 1000})
    void variedDagsConserveTrafficAcrossSplitsMergesOutagesAndRecovery(long tickMillis) {
        Random random = new Random(91);
        NodeType[] types = NodeType.values();
        for (int design = 0; design < 50; design++) {
            List<CompiledNode> nodes = new ArrayList<>();
            List<CompiledEdge> edges = new ArrayList<>();
            for (int node = 0; node < 12; node++) {
                nodes.add(TestGraphs.builder("n" + node, node == 0 ? NodeType.CLIENT
                                : types[random.nextInt(types.length)], 0)
                        .capacity("requestsPerSecond", 1 + random.nextInt(50))
                        .capacity("queueSize", 5).latency("p50Ms", 1)
                        .property("hitRate", 0.8).build());
                for (int upstream = 0; upstream < node; upstream++) {
                    if (random.nextDouble() < 0.3) {
                        edges.add(TestGraphs.edge("n" + upstream, "n" + node));
                    }
                }
            }
            CompiledGraph graph = TestGraphs.graph("Generated DAG " + design, nodes, edges);
            WorkloadProfile workload = new WorkloadProfile(
                    List.of(new WorkloadProfile.Phase("steady", 60, 1.0, 1.0, false)), 60);
            List<FailureInjector.Failure> failures = nodes.stream()
                    .map(node -> new FailureInjector.Failure("outage", node.getId(), node.getType().getValue(),
                            "fail-stop", 10, 35, Set.of(), 0)).toList();
            DiscreteEventSimulator simulator = new DiscreteEventSimulator(
                    graph, workload, failures, 30, design, tickMillis);
            simulator.run(60_000 / tickMillis, NOOP);
            assertSoftly(softly -> assertConserved(softly, simulator, graph.getName() + ", tick=" + tickMillis));
        }
    }

    private static DiscreteEventSimulator simulator(CompiledGraph graph, List<FailureInjector.Failure> failures) {
        WorkloadProfile workload = new WorkloadProfile(
                List.of(new WorkloadProfile.Phase("steady", 60, 1.0, 1.0, false)), 60);
        return new DiscreteEventSimulator(graph, workload, failures, 3, 42, 1000);
    }

    private static void assertConserved(org.assertj.core.api.SoftAssertions softly,
                                        DiscreteEventSimulator simulator, String scenario) {
        softly.assertThat(simulator.totalServed() + simulator.totalFailed() + simulator.residualBacklog())
                .as("%s: offered %s, served %s, failed %s, queued %s", scenario,
                        simulator.totalOffered(), simulator.totalServed(), simulator.totalFailed(),
                        simulator.residualBacklog())
                .isCloseTo(simulator.totalOffered(), offset(1e-8));
    }
}
