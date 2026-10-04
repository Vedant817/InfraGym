package com.archforge.simulation.workload;

import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.simulation.engine.NodeCharacteristics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives a workload from a compiled graph and a scenario.
 *
 * <h2>Why nominal capacity comes from the graph</h2>
 * The previous generator read {@code requestsPerSecond} from the first
 * {@code SERVICE} node and defaulted to 1000 for everything else — so a database
 * with 3000 IOPS and one with 10000 reported the same throughput, and a graph with
 * no service node silently got an arbitrary 1000 rps. Nominal capacity is now the
 * <em>bottleneck</em> of the graph's entry path, which is what "this architecture's
 * capacity" actually means and is what makes two different designs comparable.
 *
 * <h2>Why scenarios are declarative</h2>
 * Each scenario's phase structure lives here rather than being derived from magic
 * multipliers in seven near-identical methods. The multipliers a scenario needs are
 * already declared on {@link ScenarioType}; what remains is the <em>shape</em> over
 * time, which is the part worth reading in one place.
 */
@Slf4j
@Component
public class WorkloadGenerator {

    /**
     * Floor on nominal capacity.
     *
     * <p>A three-node design can declare 1 rps; simulating that produces a
     * statistically meaningless run where every percentile is 0. The floor keeps a
     * full run long enough to have samples.
     */
    static final double MIN_NOMINAL_RPS = 1.0;

    public WorkloadProfile generate(CompiledGraph graph, ScenarioType scenario, long durationSeconds) {
        double nominal = nominalCapacity(graph);
        long duration = durationSeconds > 0 ? durationSeconds : 60;

        List<WorkloadProfile.Phase> phases = switch (scenario) {
            case NORMAL -> steady(duration);
            case TRAFFIC_SPIKE -> trafficSpike(duration);
            case FLASH_SALE -> flashSale(duration);
            case TRAFFIC_DROP -> trafficDrop(duration);
            case SERVICE_FAILURE -> steadyWithFailure(duration);
            case DATABASE_OVERLOAD -> saturation(duration);
            case CACHE_FAILURE -> steadyWithFailure(duration);
            case KAFKA_CONSUMER_FAILURE -> steadyWithFailure(duration);
            case NETWORK_DEGRADATION -> steadyWithFailure(duration);
            case REGION_FAILURE -> capacityLoss(duration);
        };

        WorkloadProfile profile = new WorkloadProfile(phases, duration);
        log.info("Workload for {}: nominal {} rps, peak {} rps ({}x), {} phases over {}s",
                scenario.getValue(), round(nominal), round(profile.peakRps(nominal)),
                profile.peakMultiplier(), duration);
        return profile;
    }

    /**
     * The graph's sustainable capacity: the least capable element on the path from an
     * entry point to a terminal.
     *
     * <p>A pipeline cannot exceed its slowest stage, so the bottleneck is the correct
     * reference point. Computing it this way also means an architecture that adds a
     * slow database is correctly simulated as slower than one that does not — which is
     * the whole point of the product.
     */
    public double nominalCapacity(CompiledGraph graph) {
        if (graph == null || graph.nodeCount() == 0) {
            return MIN_NOMINAL_RPS;
        }

        List<CompiledNode> nodes = graph.inTopologicalOrder();
        List<CompiledNode> entryPoints = nodes.stream()
                .filter(node -> node.getType().canBeEntryPoint())
                .toList();

        // No entry point: fall back to the whole graph rather than guessing an
        // entry, and say so, because it changes what the numbers mean.
        List<CompiledNode> path = entryPoints.isEmpty()
                ? nodes
                : bottleneckPath(nodes, entryPoints);

        double capacity = Double.MAX_VALUE;
        for (CompiledNode node : path) {
            capacity = Math.min(capacity, nodeCapacity(node));
        }

        if (capacity == Double.MAX_VALUE) {
            capacity = MIN_NOMINAL_RPS;
        }
        return Math.max(MIN_NOMINAL_RPS, capacity);
    }

    /**
     * The path whose tightest component is the weakest in the whole graph.
     *
     * <p>A longest-path DP over the topological order: the bottleneck of a path is
     * {@code min(capacity of its nodes)}, so the capacity that reaches a node is the
     * minimum over all paths to it, and the graph's capacity is the minimum of those.
     * O(V+E), and it accounts for parallel branches — a design with one fast and one
     * slow path is simulated at the slow one's rate, because that is the rate a user
     * actually experiences.
     *
     * <p>Named for what it computes. It was called {@code widestPath}, which describes
     * the opposite operation and gave no hint that the result is a minimum.
     */
    private List<CompiledNode> bottleneckPath(List<CompiledNode> nodes,
                                              List<CompiledNode> entryPoints) {
        Map<String, CompiledNode> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.getId(), node));

        Map<String, Double> best = new HashMap<>();
        Map<String, String> parent = new HashMap<>();

        for (CompiledNode entry : entryPoints) {
            best.put(entry.getId(), nodeCapacity(entry));
        }

        for (CompiledNode node : nodes) {
            Double current = best.get(node.getId());
            if (current == null) {
                continue;
            }
            for (String dependentId : node.getDependents()) {
                CompiledNode dependent = byId.get(dependentId);
                if (dependent == null) {
                    continue;
                }
                double candidate = Math.min(current, nodeCapacity(dependent));
                Double existing = best.get(dependentId);
                if (existing == null || candidate < existing) {
                    best.put(dependentId, candidate);
                    parent.put(dependentId, node.getId());
                }
            }
        }

        String tightest = null;
        double tightestCapacity = Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : best.entrySet()) {
            if (entry.getValue() < tightestCapacity) {
                tightestCapacity = entry.getValue();
                tightest = entry.getKey();
            }
        }

        List<CompiledNode> path = new ArrayList<>();
        Set<String> guard = new HashSet<>();
        String current = tightest;
        while (current != null && guard.add(current)) {
            CompiledNode node = byId.get(current);
            if (node != null) {
                path.add(node);
            }
            current = parent.get(current);
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * Sustainable throughput for one node.
     *
     * <p>Delegates to {@link NodeCharacteristics} rather than re-deriving it. The two
     * copies this replaced had already drifted: this one scanned only the
     * {@code capacity} map while the simulator scanned capacity and throughput, so a
     * cache — whose {@code operationsPerSecond} lives in throughput — was priced at
     * the 1 rps floor. Two definitions of "capacity" in one service means two
     * different answers to whether a design is overloaded.
     */
    double nodeCapacity(CompiledNode node) {
        return NodeCharacteristics.of(node).sustainedRateRps();
    }

    /** The whole graph's bottleneck capacity; the reference the workload scales against. */
    public double nominalCapacityOf(CompiledGraph graph) {
        return nominalCapacity(graph);
    }

    // --- scenario shapes -------------------------------------------------------
    //
    // Every level below is a MULTIPLIER of the graph's nominal capacity, never an
    // absolute rate. That is what makes a scenario mean the same thing for a design
    // rated at 5 rps and one rated at 50 000 — and it is the bug this replaced: the
    // shapes used to bake `nominal` into their levels while WorkloadProfile then
    // multiplied by `nominal` again, so a nominal run on a 1000 rps design offered
    // 1 000 000 requests per second. Every number the platform reported was then out
    // by a factor of a thousand, and no test caught it because each checked only that
    // the run completed.

    private List<WorkloadProfile.Phase> steady(long duration) {
        return List.of(
                new WorkloadProfile.Phase("warmup", 10, 0.5, 0.2, true),
                new WorkloadProfile.Phase("steady", (int) Math.max(1, duration - 15), 1.0, 0.9, true),
                new WorkloadProfile.Phase("cooldown", 5, 0.3, 0.5, false));
    }

    private List<WorkloadProfile.Phase> trafficSpike(long duration) {
        return List.of(
                new WorkloadProfile.Phase("baseline", 15, 1.0, 0.8, true),
                new WorkloadProfile.Phase("spike", 10, 5.0, 1.0, true),
                new WorkloadProfile.Phase("recovery", 15, 1.5, 5.0, true),
                new WorkloadProfile.Phase("steady", (int) Math.max(1, duration - 40), 1.0, 1.0, false));
    }

    private List<WorkloadProfile.Phase> flashSale(long duration) {
        // A genuine flash sale: low traffic, then a very short extreme burst, then
        // collapse. The brevity of the burst is what distinguishes it from a spike.
        return List.of(
                new WorkloadProfile.Phase("pre-sale", 20, 0.4, 0.3, true),
                new WorkloadProfile.Phase("flash", 8, 10.0, 0.5, true),
                new WorkloadProfile.Phase("post-flash", 20, 1.2, 10.0, true),
                new WorkloadProfile.Phase("steady", (int) Math.max(1, duration - 48), 1.0, 1.0, false));
    }

    private List<WorkloadProfile.Phase> trafficDrop(long duration) {
        return List.of(
                new WorkloadProfile.Phase("baseline", 15, 1.0, 0.8, true),
                new WorkloadProfile.Phase("collapse", 20, 0.1, 1.0, true),
                new WorkloadProfile.Phase("recovery", (int) Math.max(1, duration - 35), 1.0, 0.1, true));
    }

    /** Load held at baseline while a failure is injected. */
    private List<WorkloadProfile.Phase> steadyWithFailure(long duration) {
        return List.of(
                new WorkloadProfile.Phase("warmup", 10, 0.5, 0.3, true),
                new WorkloadProfile.Phase("pre-failure", 10, 1.0, 0.9, true),
                new WorkloadProfile.Phase("under-failure", 25, 1.0, 1.0, false),
                new WorkloadProfile.Phase("recovery", 15, 1.0, 1.0, false));
    }

    /** Load driven past the storage ceiling, so saturation is observable. */
    private List<WorkloadProfile.Phase> saturation(long duration) {
        return List.of(
                new WorkloadProfile.Phase("baseline", 15, 1.0, 0.8, true),
                new WorkloadProfile.Phase("saturating", 25, 3.0, 1.0, true),
                new WorkloadProfile.Phase("recovery", (int) Math.max(1, duration - 40), 1.0, 3.0, true));
    }

    /**
     * A step loss of capacity, as in an availability-zone outage.
     *
     * <p>Load stays flat at baseline throughout. The scenario is about losing capacity,
     * not about changing traffic, and the failure injector already takes the declared
     * fraction of storage offline. Ramping the load as well would confound the two:
     * a latency spike could then be caused by either the outage or the ramp, and the
     * run could not attribute it.
     */
    private List<WorkloadProfile.Phase> capacityLoss(long duration) {
        return List.of(
                new WorkloadProfile.Phase("before-outage", 15, 1.0, 0.8, true),
                new WorkloadProfile.Phase("outage", 25, 1.0, 1.0, false),
                new WorkloadProfile.Phase("restored", 15, 1.0, 1.0, false),
                new WorkloadProfile.Phase("steady", (int) Math.max(1, duration - 55), 1.0, 1.0, false));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
