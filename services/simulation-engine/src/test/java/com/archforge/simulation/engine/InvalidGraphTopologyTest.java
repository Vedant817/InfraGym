package com.archforge.simulation.engine;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.simulation.support.TestGraphs;
import com.archforge.simulation.workload.WorkloadProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Graphs the simulator refuses, and the ones it must not refuse.
 *
 * <p>Every case here produces a run that reports {@code COMPLETED} with a wrong number
 * rather than an error, which is what makes them worth a refusal. The constructor is
 * where the check lives — the tick loop cannot detect any of it once started, because a
 * starved node and an idle one look identical in the state it reads.
 *
 * <p>These fixtures use {@link TestGraphs#graphWithDeclaredRanks} rather than
 * {@link TestGraphs#graph}, which repairs ranks from the edges and would hand every one
 * of these a valid ordering before the simulator saw it. Asserting that the simulator
 * rejects a bad graph via a helper that makes it good tests the helper.
 */
class InvalidGraphTopologyTest {

    private static final long TICK = 1000L;

    private static WorkloadProfile flat(double rps, long seconds) {
        return new WorkloadProfile(
                List.of(new WorkloadProfile.Phase("steady", (int) seconds, 1.0, 1.0, false)),
                seconds);
    }

    private static DiscreteEventSimulator simulate(CompiledGraph graph) {
        return new DiscreteEventSimulator(graph, flat(100, 5), List.of(), 100, 1L, TICK);
    }

    @Nested
    @DisplayName("duplicate component ids")
    class DuplicateIds {

        @Test
        @DisplayName("are refused: every metric would be counted twice")
        void refused() {
            // The state map collapses them while the tick order keeps both, so the node
            // runs twice per tick against one state and each (node, metric, tick) key is
            // published twice. A percentile computed downstream is then over a stream
            // with every sample in it duplicated, which is not a small error — it
            // silently narrows every distribution while looking plausible.
            // No edges, deliberately. A fixture with an edge into 'api' can also fail
            // the dangling-edge check, and a substring assertion on 'api' matches both
            // messages — so the test would go green for a reason unrelated to the
            // behaviour it names.
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Duplicate", List.of(
                    TestGraphs.service("api"),
                    TestGraphs.service("api")),
                    List.of());

            assertThatThrownBy(() -> simulate(graph))
                    .isInstanceOf(InvalidGraphException.class)
                    .hasMessageContaining("share the id")
                    .hasMessageContaining("api");
        }

        @Test
        @DisplayName("are refused even when the duplicate is fully wired")
        void refusedOnAWiredGraph() {
            // Same defect with the graph connected end to end: the duplicate is both a
            // caller and a callee, so a check that only looked at, say, the trailing
            // nodes would miss it.
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Wired duplicate", List.of(
                    TestGraphs.builder("lb", NodeType.LOAD_BALANCER, 0).build(),
                    TestGraphs.builder("api", NodeType.SERVICE, 1).build(),
                    TestGraphs.builder("api", NodeType.SERVICE, 1).build(),
                    TestGraphs.builder("db", NodeType.DATABASE, 2).build()),
                    List.of(TestGraphs.edge("lb", "api"), TestGraphs.edge("api", "db")));

            assertThatThrownBy(() -> simulate(graph))
                    .isInstanceOf(InvalidGraphException.class)
                    .hasMessageContaining("share the id");
        }
    }

    @Nested
    @DisplayName("edges to components that are not there")
    class DanglingEdges {

        @Test
        @DisplayName("a missing target is refused: nothing would ever be counted as served")
        void missingTarget() {
            // db is not in the graph, so the edge still makes api non-terminal. api's
            // load is forwarded to nobody, db is never evaluated, and the run completes
            // reporting everything offered and nothing served -- 100% loss with an empty
            // findings list and no warning.
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Dangling target", List.of(
                    TestGraphs.service("api")),
                    List.of(TestGraphs.edge("api", "ghost")));

            assertThatThrownBy(() -> simulate(graph))
                    .isInstanceOf(InvalidGraphException.class)
                    .hasMessageContaining("ghost");
        }

        @Test
        @DisplayName("a missing source is refused")
        void missingSource() {
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Dangling source", List.of(
                    TestGraphs.service("api")),
                    List.of(TestGraphs.edge("ghost", "api")));

            assertThatThrownBy(() -> simulate(graph))
                    .isInstanceOf(InvalidGraphException.class)
                    .hasMessageContaining("ghost");
        }
    }

    @Nested
    @DisplayName("graphs that are not in topological order")
    class RankInversions {

        @Test
        @DisplayName("are refused, naming both components and both ranks")
        void equalRanksRefused() {
            // The rule is strictly increasing, so equal ranks are as broken as inverted
            // ones: the upstream's tick state is reset before the downstream reads it,
            // so the downstream reads zero and stays starved for the whole run. Verified
            // not to self-heal -- the cause is the ordering, not the first tick.
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Flat ranks", List.of(
                    TestGraphs.builder("lb", NodeType.LOAD_BALANCER, 0).build(),
                    TestGraphs.builder("api", NodeType.SERVICE, 0).build()),
                    List.of(TestGraphs.edge("lb", "api")));

            assertThatThrownBy(() -> simulate(graph))
                    .isInstanceOf(InvalidGraphException.class)
                    .hasMessageContaining("lb")
                    .hasMessageContaining("api")
                    .hasMessageContaining("topological order");
        }

        @Test
        @DisplayName("an inverted pair is refused")
        void invertedRanksRefused() {
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Inverted", List.of(
                    TestGraphs.builder("lb", NodeType.LOAD_BALANCER, 5).build(),
                    TestGraphs.builder("api", NodeType.SERVICE, 1).build()),
                    List.of(TestGraphs.edge("lb", "api")));

            assertThatThrownBy(() -> simulate(graph))
                    .isInstanceOf(InvalidGraphException.class)
                    .hasMessageContaining("topological order");
        }
    }

    @Nested
    @DisplayName("graphs that must be accepted")
    class NotFalsePositives {

        @Test
        @DisplayName("a diamond is valid: two components may share a rank")
        void diamondAccepted() {
            // lb -> {a, b} -> sink. a and b are peers, so the compiler legitimately
            // assigns them the same rank. A rule that required distinct ranks rather
            // than strictly increasing ones would reject every branching design in the
            // product -- which is to say, all of them.
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Diamond", List.of(
                    TestGraphs.builder("lb", NodeType.LOAD_BALANCER, 0).build(),
                    TestGraphs.builder("a", NodeType.SERVICE, 1).build(),
                    TestGraphs.builder("b", NodeType.SERVICE, 1).build(),
                    TestGraphs.builder("sink", NodeType.DATABASE, 2).build()),
                    List.of(TestGraphs.edge("lb", "a"), TestGraphs.edge("lb", "b"),
                            TestGraphs.edge("a", "sink"), TestGraphs.edge("b", "sink")));

            assertThatCode(() -> simulate(graph).run(5, (node, state, metric, value,
                    tick, phase, failing) -> { })).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a disconnected component is valid: an isolated box is a legal design")
        void disconnectedAccepted() {
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Disconnected", List.of(
                    TestGraphs.builder("lb", NodeType.LOAD_BALANCER, 0).build(),
                    TestGraphs.builder("api", NodeType.SERVICE, 1).build(),
                    TestGraphs.builder("orphan", NodeType.SERVICE, 0).build()),
                    List.of(TestGraphs.edge("lb", "api")));

            assertThatCode(() -> simulate(graph).run(5, (node, state, metric, value,
                    tick, phase, failing) -> { })).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a graph with no edges at all is valid")
        void edgelessAccepted() {
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Lonely",
                    List.of(TestGraphs.service("only")), List.of());

            assertThatCode(() -> simulate(graph).run(5, (node, state, metric, value,
                    tick, phase, failing) -> { })).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the refusal has to be legible")
    class MessageQuality {

        @Test
        @DisplayName("the exception is an IllegalArgumentException, so the consumer's catch is the obvious one")
        void isAnIllegalArgument() {
            CompiledGraph graph = TestGraphs.graphWithDeclaredRanks("Bad", List.of(
                    TestGraphs.builder("a", NodeType.SERVICE, 1).build(),
                    TestGraphs.builder("b", NodeType.SERVICE, 0).build()),
                    List.of(TestGraphs.edge("a", "b")));

            assertThatThrownBy(() -> simulate(graph)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an edge with no explicit id is still named")
        void namesEdgesWithoutIds() {
            // CompiledEdge derives its id as source->target when the producer omits one.
            // A message that printed a null id would leave an operator with no way to find
            // the offending edge in the graph they submitted.
            CompiledEdge unnamed = CompiledEdge.builder()
                    .source("a").target("b").latencyMs(1).build();

            assertThat(unnamed.getId()).isEqualTo("a->b");

            CompiledGraph graph = CompiledGraph.builder()
                    .name("Unnamed edge")
                    .nodes(List.of(TestGraphs.builder("a", NodeType.SERVICE, 0).build(),
                            TestGraphs.builder("b", NodeType.SERVICE, 0).build()))
                    .edges(List.of(unnamed))
                    .graphMetadata(Map.of())
                    .build();

            assertThatThrownBy(() -> simulate(graph))
                    .isInstanceOf(InvalidGraphException.class)
                    .hasMessageContaining("a->b");
        }

        @Test
        @DisplayName("the refusal happens in the constructor, before any tick executes")
        void refusedBeforeAnyWork() {
            // The refusal is not a property of the tick loop, it is a property of the
            // constructor — so the proof is that constructing already throws, and that a
            // valid graph of the same shape then runs normally. Asserting a successful run
            // here would have tested nothing about refusal.
            CompiledGraph bad = TestGraphs.graphWithDeclaredRanks("Inverted", List.of(
                    TestGraphs.builder("a", NodeType.SERVICE, 1).build(),
                    TestGraphs.builder("b", NodeType.SERVICE, 0).build()),
                    List.of(TestGraphs.edge("a", "b")));

            assertThatThrownBy(() -> simulate(bad))
                    .as("no observer ever runs, so no telemetry can be produced")
                    .isInstanceOf(InvalidGraphException.class);

            CompiledGraph good = TestGraphs.deepChain();
            DiscreteEventSimulator running = simulate(good);
            running.run(3, (node, state, metric, value, tick, phase, failing) -> { });

            assertThat(running.ticksExecuted()).isEqualTo(3);
        }
    }
}