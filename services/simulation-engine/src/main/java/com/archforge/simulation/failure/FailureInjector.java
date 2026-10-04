package com.archforge.simulation.failure;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.ScenarioType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Chooses which components fail and how.
 *
 * <h2>Determinism is the whole point</h2>
 * The previous injector used an unseeded {@code Random} and a {@code new
 * FailureInjector()} constructed inside a per-node loop, passing {@code null} as the
 * graph — so as soon as a scenario actually injected a failure, the first call threw
 * {@code NullPointerException}, the tick loop died, and the run was still reported as
 * {@code COMPLETED}. Because {@code scenarioType} was never populated, no scenario ever
 * injected a failure, so the bug stayed invisible.
 *
 * <p>Site selection is now driven by {@code Random(seed)} where the seed comes from the
 * task. Submitting the same design twice therefore produces the same failure sites, so
 * a P99 can be compared against a P99 and the AI's advice can be checked against the
 * numbers it was given.
 *
 * <h2>Selection targets, then degrades</h2>
 * A scenario asks for a category of component. If the graph has none, the injector
 * widens the search rather than silently injecting nothing — a user who picks
 * "database overload" on a graph with no database should be told the graph has no
 * database, not handed a NORMAL run labelled DATABASE_OVERLOAD.
 */
@Slf4j
@Component
public class FailureInjector {

    /** Ticks, at and after which a target is considered failed. */
    static final int DEFAULT_START_TICK = 20;

    /** Default failure duration in ticks. */
    static final int DEFAULT_DURATION_TICKS = 25;

    /**
     * Builds a failure plan for a scenario.
     *
     * @param graph   compiled graph, used to pick realistic targets
     * @param scenario scenario whose {@code failureTarget} decides what breaks
     * @param seed    makes site selection reproducible
     * @param ticks   total ticks in the run, used to keep failures inside the run
     * @return the plan; empty when the scenario injects nothing, or when nothing in the
     *         graph can break
     */
    public List<Failure> plan(CompiledGraph graph, ScenarioType scenario, long seed, long ticks) {
        if (graph == null || graph.nodeCount() == 0 || !scenario.injectsFailures()) {
            return List.of();
        }

        Random random = new Random(seed);
        int startTick = Math.min(DEFAULT_START_TICK, Math.max(1, (int) (ticks / 3)));
        int duration = Math.min(DEFAULT_DURATION_TICKS, Math.max(1, (int) (ticks / 2)));

        List<Target> targets = resolveTargets(graph, scenario.getFailureTarget());
        if (targets.isEmpty()) {
            log.warn("Scenario {} requests a {} failure but the graph contains no such component; "
                    + "the run will not inject a failure", scenario.getValue(), scenario.getFailureTarget());
            return List.of();
        }

        int count = Math.max(1, (int) Math.ceil(targets.size() * scenario.getFailureFraction()));
        count = Math.min(count, targets.size());

        // Shuffle a copy so a fraction below 1 picks a random subset, and the choice
        // depends only on the seed.
        List<Target> shuffled = new ArrayList<>(targets);
        shuffled.sort(Comparator.comparingInt(target -> random.nextInt()));

        List<Failure> plan = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Target target = shuffled.get(i);
            plan.add(new Failure(
                    scenario.getValue(),
                    target.nodeId(),
                    target.nodeType().getValue(),
                    target.failureMode(),
                    startTick,
                    startTick + duration,
                    affectedComponents(graph, target.nodeId()),
                    target.blastRadius()));
        }

        log.info("Failure plan for {}: {} of {} component(s) fail from tick {} for {} ticks -- {}",
                scenario.getValue(), count, targets.size(), startTick, duration,
                plan.stream().map(Failure::nodeId).toList());
        return List.copyOf(plan);
    }

    /**
     * Components matching the requested category, plus the blast radius of each.
     *
     * <p>Ordered by id so the seed yields a reproducible selection regardless of the
     * order the compiler happened to emit nodes in.
     */
    private List<Target> resolveTargets(CompiledGraph graph, ScenarioType.FailureTarget requested) {
        List<CompiledNode> candidates = graph.inTopologicalOrder().stream()
                // Monitoring nodes do not fail in a way a caller can observe, so they
                // are never chosen even for an ALL request.
                .filter(node -> !node.getType().isObservability())
                .toList();

        List<CompiledNode> matched = candidates.stream()
                .filter(node -> matches(node.getType(), requested))
                .toList();

        if (matched.isEmpty() && requested != ScenarioType.FailureTarget.ALL) {
            // Widen: a COMPUTE request on a graph of only storage still has to break
            // something, or the run silently measures nothing. The widened set excludes
            // entry points, because killing the ingress means nothing downstream is
            // offered any load and the run reports a clean result for an architecture
            // that never received a request.
            List<CompiledNode> widened = candidates.stream()
                    .filter(node -> !node.getType().canBeEntryPoint())
                    .toList();
            if (widened.isEmpty()) {
                log.warn("Scenario {} found no {} component and the graph has no interior "
                                + "component to widen to; falling back to an entry point, so the "
                                + "run will report no traffic", requested, requested);
                matched = candidates;
            } else {
                log.info("No component matches {}; widening the failure search to the {} "
                                + "non-observability interior component(s)", requested, widened.size());
                matched = widened;
            }
        }

        return matched.stream()
                .sorted(Comparator.comparing(CompiledNode::getId))
                .map(node -> new Target(
                        node.getId(),
                        node.getType(),
                        failureModeFor(node),
                        blastRadiusOf(graph, node.getId())))
                .toList();
    }

    private boolean matches(NodeType type, ScenarioType.FailureTarget requested) {
        return switch (requested) {
            case NONE -> false;
            case ALL -> true;
            case COMPUTE -> type.isCompute();
            case STORAGE -> type.isStorage() && type != NodeType.CACHE;
            case CACHE -> type == NodeType.CACHE;
            case MESSAGING -> type.isMessaging();
            case NETWORK -> type.isNetwork();
        };
    }

    /**
     * Picks a failure mode the node actually declares.
     *
     * <p>Falling back to a generic mode is honest: the alternative is inventing a mode
     * the node type does not support and reporting an impact that cannot occur.
     */
    private String failureModeFor(CompiledNode node) {
        if (!node.getFailureModes().isEmpty()) {
            return node.getFailureModes().iterator().next();
        }
        return node.getType().isStorage() ? "resource-exhaustion" : "fail-stop";
    }

    /**
     * How many components lose their dependency when this one fails.
     *
     * <p>Direct dependents only. Transitive impact is already implied by the propagation
     * the simulator performs; counting it here as well would double-count it in the
     * reported blast radius.
     */
    private int blastRadiusOf(CompiledGraph graph, String nodeId) {
        return graph.inTopologicalOrder().stream()
                .filter(node -> node.getDependencies().contains(nodeId))
                .mapToInt(node -> 1)
                .sum();
    }

    /**
     * Every component reachable downstream of a failure.
     *
     * <p>Computed from the compiled graph rather than passed in as a task. The previous
     * implementation called {@code getAffectedNodes(failure, null)} and dereferenced the
     * null argument, which is why enabling any scenario aborted the entire simulation
     * with an NPE that then reported itself as a completed run.
     */
    public Set<String> affectedComponents(CompiledGraph graph, String failedNodeId) {
        Set<String> affected = new LinkedHashSet<>();
        if (graph == null || failedNodeId == null) {
            return affected;
        }
        affected.add(failedNodeId);

        // BFS over the dependents relation, which is precomputed on the graph.
        java.util.Deque<String> frontier = new java.util.ArrayDeque<>();
        frontier.add(failedNodeId);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            graph.node(current).ifPresent(node -> {
                for (String dependent : node.getDependents()) {
                    if (affected.add(dependent)) {
                        frontier.add(dependent);
                    }
                }
            });
        }
        return affected;
    }
    private record Target(String nodeId, NodeType nodeType, String failureMode, int blastRadius) {
    }

    /**
     * One planned failure.
     *
     * @param mode            scenario-level failure type, e.g. {@code database_overload}
     * @param nodeId          component that fails
     * @param nodeType        its type, recorded for the evaluator
     * @param failureMode     the specific mode it exhibits
     * @param startTick       first tick of failure, inclusive
     * @param endTick         first tick after the failure ends, exclusive
     * @param affectedNodeIds everything downstream that loses its dependency
     * @param blastRadius     count of direct dependents
     */
    public record Failure(
            String mode,
            String nodeId,
            String nodeType,
            String failureMode,
            int startTick,
            int endTick,
            Set<String> affectedNodeIds,
            int blastRadius) {

        public boolean isActiveAt(long tick) {
            return tick >= startTick && tick < endTick;
        }

        public int durationTicks() {
            return Math.max(0, endTick - startTick);
        }
    }
}
