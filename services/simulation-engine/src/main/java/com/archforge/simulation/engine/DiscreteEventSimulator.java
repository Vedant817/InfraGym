package com.archforge.simulation.engine;

import com.archforge.contracts.stats.Distribution;

import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.simulation.failure.FailureInjector;
import com.archforge.simulation.workload.WorkloadProfile;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The simulation: a per-component queueing model over a compiled DAG.
 *
 * <h2>What "simulating an architecture" means here</h2>
 * Each tick, offered load is routed along the DAG in topological order. Every
 * component accepts up to what its declared capacity and concurrency allow in one
 * tick; whatever it cannot take either waits (a queue or a cache) or fails (a service,
 * a database, a function). That one rule, applied uniformly, produces the behaviour
 * the product is about without any component knowing about any other:
 *
 * <ul>
 *   <li>A database asked for more than its IOPS allow accumulates a backlog, and the
 *       backlog is reported as {@code queue_depth} and {@code queue_age_ms} in its own
 *       right. It is deliberately <em>not</em> folded into latency: a backlog is a
 *       property of the queue in front of a component, and charging it to the component
 *       would report the store as slow when the honest statement is that its callers are
 *       queued behind it. The path a request actually took is what latency measures.</li>
 *   <li>A cache that stops answering forwards every request to the store behind it,
 *       which is exactly how a cache outage becomes a database outage.</li>
 *   <li>A failing component stops draining its queue, so its downstream starves while
 *       its upstream's latency rises. Failure propagation is derived, not declared.</li>
 *   <li>A queue absorbs a burst that would otherwise be an error spike, so a flash
 *       sale is a lag problem for a queued design and a rejection problem for a
 *       synchronous one — from the same model.</li>
 * </ul>
 *
 * <h2>What was wrong before</h2>
 * The previous engine read only node id, type, capacity and one latency key, and
 * derived every metric from {@code Math.random()} and a set of magic multipliers. It
 * ignored {@code latencyProfile} (so databases and workers all reported a hard-coded
 * 20 ms), ignored edge latency entirely (so the network was free), gave every node the
 * same request rate regardless of the graph, and computed failure impact by
 * constructing a fresh injector per node with a null graph. The result was a green
 * health check producing numbers with no relationship to the architecture under test.
 *
 * <h2>Determinism</h2>
 * All randomness comes from one seeded generator, drawn in a fixed order —
 * topological order within a tick, one draw per tick per node. The same task
 * therefore produces byte-identical telemetry, so a reported P99 is reproducible and
 * the AI's advice can be checked against the numbers it was given.
 */
@Slf4j
public class DiscreteEventSimulator {

    /**
     * Per-tick fluctuation in the sampled cache miss fraction.
     *
     * <p>A cache's hit rate is a property of the key distribution, not a coin flip
     * per request. Sampling it as a Bernoulli per tick would make a declared 80% hit
     * rate land on 0% or 100% in any given tick; perturbing the fraction around the
     * declared value keeps per-tick variation visible while the run-wide rate
     * converges to what the node actually declares.
     */
    static final double MISS_SAMPLE_JITTER = 0.1;

    private final CompiledGraph graph;
    private final WorkloadProfile workload;
    private final List<FailureInjector.Failure> failures;
    private final double nominalRps;
    private final long tickMillis;
    private final double tickSeconds;
    private final Random random;

    private final List<CompiledNode> order;
    private final Map<String, ComponentState> states = new LinkedHashMap<>();
    private final Map<String, CompiledNode> byId = new HashMap<>();
    private final Map<String, List<CompiledEdge>> incoming = new HashMap<>();
    private final Map<String, List<CompiledEdge>> outgoing = new HashMap<>();
    private final Map<String, List<FailureInjector.Failure>> failuresByNode = new HashMap<>();
    private final List<String> routingWarnings = new ArrayList<>();

    private double totalOffered;
    private double totalServed;
    private double totalFailed;
    private long ticksExecuted;
    private String currentPhase = "";

    /**
     * @param graph     compiled DAG, treated as read-only input
     * @param workload  the offered-load phases for this run
     * @param failures  planned failures, empty for a steady-state run
     * @param nominalRps the graph's own sustainable capacity, from
     *                   {@link com.archforge.simulation.workload.WorkloadGenerator};
     *                   the workload profile's multipliers are relative to it
     * @param seed      makes every random draw reproducible
     * @param tickMillis simulated time per tick
     */
    public DiscreteEventSimulator(CompiledGraph graph,
                                  WorkloadProfile workload,
                                  List<FailureInjector.Failure> failures,
                                  double nominalRps,
                                  long seed,
                                  long tickMillis) {
        this.graph = graph;
        this.workload = workload;
        this.failures = failures == null ? List.of() : failures;
        this.nominalRps = Math.max(1.0, nominalRps);
        this.tickMillis = tickMillis > 0 ? tickMillis : 1000;
        this.tickSeconds = this.tickMillis / 1000.0;
        this.random = new Random(seed);

        // Cached once: inTopologicalOrder() copies and sorts on every call, and it is
        // called once per node per tick.
        this.order = graph.inTopologicalOrder();

        // Duplicate ids would collapse in `states` while `order` kept both occurrences,
        // so every node would be evaluated twice per tick against one shared state:
        // each (node, metric, tick) key published twice, sample counts doubled, and the
        // aggregator's percentiles computed over a stream with every sample in it
        // twice. Rejected rather than de-duplicated, because a graph with two nodes of
        // one id is not a graph the author drew.
        //
        // Tracked in a local set, not in `states`: this check runs before the population
        // loop below, and asking a map that is still empty whether it contains a key is
        // asking the wrong question. The answer was always no, so the check never fired.
        Set<String> seenIds = new HashSet<>();
        for (CompiledNode node : order) {
            if (!seenIds.add(node.getId())) {
                throw new InvalidGraphException("Two components share the id '" + node.getId()
                        + "'. Every metric would be counted twice.");
            }
        }

        for (CompiledNode node : order) {
            NodeCharacteristics characteristics = NodeCharacteristics.of(node);
            states.put(node.getId(), new ComponentState(node, characteristics));
            byId.put(node.getId(), node);
            if (!characteristics.declaresThroughput()) {
                routingWarnings.add("Component '" + node.getId() + "' (" + node.getType().getValue()
                        + ") declares no throughput figure; simulated at "
                        + NodeCharacteristics.UNDECLARED_RATE_RPS + " rps");
            }
            if (!characteristics.declaresLatency()) {
                routingWarnings.add("Component '" + node.getId() + "' (" + node.getType().getValue()
                        + ") declares no latency figure; simulated at "
                        + NodeCharacteristics.UNDECLARED_LATENCY_MS + " ms");
            }
        }

        for (CompiledEdge edge : graph.getEdges()) {
            outgoing.computeIfAbsent(edge.getSource(), key -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.getTarget(), key -> new ArrayList<>()).add(edge);
        }

        // Failures are indexed by node id rather than scanned per node per tick. The
        // scan allocated a Stream per node per tick and cost O(V x F) each time, which
        // at the documented limits -- 500 storage nodes, a third of them taken down,
        // 20 000 ticks -- is over a billion lambda invocations and ten million Stream
        // allocations for one run.
        for (FailureInjector.Failure failure : this.failures) {
            failuresByNode.computeIfAbsent(failure.nodeId(), key -> new ArrayList<>()).add(failure);
        }

        validateTopology();
        detectUnroutableCaches();
    }

    /**
     * Rejects a graph the tick loop cannot evaluate honestly.
     *
     * <p>The simulator's one-pass-per-tick design depends entirely on
     * {@code topologicalOrder} being correct, and it does not verify it. A node that
     * reads a not-yet-evaluated upstream reads a <em>zero</em> rather than the previous
     * tick's value, because {@code resetTickState} runs at the start of each node's
     * evaluation. So a wrong order does not produce a slightly wrong number — it
     * starves every non-entry node <em>permanently</em>, and the run reports
     * {@code COMPLETED} with all load offered and none served.
     *
     * <p>Verified: {@code lb -> api -> mid -> db} with the ranks reversed leaves the
     * database offered 0.0 requests on every tick and it does not self-heal, because
     * the cause is the order and not a transient.
     *
     * <p>The graph compiler's {@code TopologicalSorter} guarantees all of this, so these
     * conditions are unreachable from the compiler. They are reachable from a replayed
     * record, a hand-published task, or any future producer that skips the compiler — and
     * in each case the failure is silent, which is the reason to check.
     *
     * <p>Also rejected: an edge referencing a node that is not present. It makes the
     * source non-terminal, so nothing is ever counted as served and the run completes
     * reporting 100% loss with no explanation.
     */
    private void validateTopology() {
        for (CompiledEdge edge : graph.getEdges()) {
            if (!byId.containsKey(edge.getSource())) {
                throw new InvalidGraphException("Edge '" + edge.getId() + "' starts at '"
                        + edge.getSource() + "', which is not a component in this graph.");
            }
            if (!byId.containsKey(edge.getTarget())) {
                throw new InvalidGraphException("Edge '" + edge.getId() + "' ends at '"
                        + edge.getTarget() + "', which is not a component in this graph.");
            }
            int from = rankOf(edge.getSource());
            int to = rankOf(edge.getTarget());
            if (from >= to) {
                throw new InvalidGraphException("Edge '" + edge.getId() + "': component '"
                        + edge.getSource() + "' (rank " + from + ") calls '" + edge.getTarget()
                        + "' (rank " + to + "), so the graph is not in topological order. The "
                        + "upstream's load is reset before the downstream reads it, which would "
                        + "starve " + edge.getTarget() + " for the whole run.");
            }
        }
    }

    private int rankOf(String nodeId) {
        CompiledNode node = byId.get(nodeId);
        return node == null ? Integer.MAX_VALUE : node.getTopologicalOrder();
    }

    /**
     * Flags a cache whose misses reach no downstream component.
     *
     * <p>A cache's misses are the load it puts on the store behind it, so the read
     * path has to be drawn as {@code service -> cache -> database} for the number to
     * mean anything. Drawn as a fan-out ({@code service -> cache} and
     * {@code service -> database} as siblings) the misses have nowhere to go, and the
     * cache's contribution to database load silently vanishes.
     *
     * <p>The alternatives are both worse. Forwarding the misses to a sibling invents
     * topology the user did not draw and reports a number for an architecture that
     * does not exist; dropping them quietly understates the database and makes a
     * cache outage look free. So the volume is counted and reported instead.
     */
    private void detectUnroutableCaches() {
        for (CompiledNode node : order) {
            if (node.getType() != com.archforge.contracts.canvas.NodeType.CACHE) {
                continue;
            }
            if (outgoing.getOrDefault(node.getId(), List.of()).isEmpty()) {
                routingWarnings.add("Cache '" + node.getId()
                        + "' has no downstream component, so its misses reach nothing. "
                        + "Draw " + node.getId() + " -> <database> to model the read path; "
                        + "cache load on the store is being under-counted.");
            }
        }
    }

    public Map<String, ComponentState> states() {
        return states;
    }

    public List<String> routingWarnings() {
        return List.copyOf(routingWarnings);
    }

    public double nominalRps() {
        return nominalRps;
    }

    public long ticksExecuted() {
        return ticksExecuted;
    }

    public String currentPhase() {
        return currentPhase;
    }

    /** Requests offered at the graph's entry points over the whole run. */
    public double totalOffered() {
        return totalOffered;
    }

    /** Requests that traversed the whole graph, at a terminal component. */
    public double totalServed() {
        return totalServed;
    }

    /** Requests lost at any component, over the whole run. */
    public double totalFailed() {
        return totalFailed;
    }

    /**
     * Requests still sitting in a queue when the run ended.
     *
     * <p>The third term of the accounting identity, and the reason a completion rate can
     * fall short of 100% without anything having failed:
     *
     * <pre>
     *   offered = served + failed + residual backlog
     * </pre>
     *
     * <p>Without it the difference is unexplainable. A live flash-sale run reported 17 260
     * offered, 15 607 served and 0 failed — a completion rate of 90.4% with no failure
     * anywhere to account for the missing 9.6%. The 1 653 requests were still queued
     * behind a consumer that could take 100 rps. That is consumer lag, the single most
     * important number in a queueing design, and the run summary was hiding it.
     */
    public double residualBacklog() {
        return states.values().stream().mapToDouble(ComponentState::residualBacklog).sum();
    }

    /**
     * Executes every tick, handing each measurement to the observer.
     *
     * @param tickCount total ticks to execute
     * @param observer  invoked once per tick per component per metric
     */
    public void run(long tickCount, TickObserver observer) {
        run(tickCount, observer, Long.MAX_VALUE);
    }

    /**
     * Runs ticks, abandoning the run if the deadline passes.
     *
     * <p>The deadline is checked here rather than by the caller afterwards. Checked
     * afterwards it bounded nothing at all: the run had already finished, so the check
     * could only discard a <em>completed</em> run's results rather than stop a slow one.
     * Checked between ticks it actually stops work — at the cost of leaving the trace
     * partial, which is why the caller is told rather than left to infer completion from
     * a tick count.
     *
     * @param deadlineNanos {@link System#nanoTime()} value to abandon at, or
     *                      {@link Long#MAX_VALUE} for no deadline
     * @throws SimulationAbandonedException if the deadline passes
     */
    public void run(long tickCount, TickObserver observer, long deadlineNanos) {
        // Every DEADLINE_CHECK_INTERVAL ticks rather than every tick: this loop is
        // otherwise pure arithmetic, so a per-tick clock read would cost more than the
        // work it was guarding.
        for (long tick = 0; tick < tickCount; tick++) {
            if (tick % DEADLINE_CHECK_INTERVAL == 0 && System.nanoTime() > deadlineNanos) {
                throw new SimulationAbandonedException("abandoned after " + tick + " of "
                        + tickCount + " ticks: the wall-clock budget was exhausted");
            }
            step(tick, observer);
            ticksExecuted = tick + 1;
        }
    }

    /** Ticks between wall-clock budget checks. */
    static final long DEADLINE_CHECK_INTERVAL = 16;

    /**
     * One tick, in three ordered phases.
     *
     * <p>Failures are applied for the whole graph before any node is evaluated, so a
     * node's upstream cannot observe a pre-failure state during a post-failure tick —
     * which is what made an injected failure look like it started one tick late.
     */
    private void step(long tick, TickObserver observer) {
        double elapsedSeconds = tick * tickSeconds;
        currentPhase = workload.phaseAt(elapsedSeconds);
        double offeredRps = workload.offeredRpsAt(nominalRps, elapsedSeconds);

        applyFailures(tick);

        for (CompiledNode node : order) {
            evaluate(node, states.get(node.getId()), tick, offeredRps, observer);
        }
    }

    /**
     * Advances one component by one tick.
     *
     * <p>Called in topological order, so a component's upstream has already recorded
     * this tick's bypass volume by the time the component is evaluated, and one pass
     * is sufficient.
     */
    private void evaluate(CompiledNode node,
                          ComponentState state,
                          long tick,
                          double offeredRps,
                          TickObserver observer) {
        if (state == null) {
            return;
        }
        state.resetTickState();

        NodeCharacteristics traits = state.getCharacteristics();
        double arrivalsRps = arrivalsFor(node, offeredRps);
        double arrivalCount = arrivalsRps * tickSeconds;
        state.setLastOfferedRps(arrivalsRps);

        double upstreamPathMs = worstIncomingPathMs(node);
        double serviceable = serviceableThisTick(node, traits);
        state.observeCeilingRps(serviceable / tickSeconds);
        double served;
        double failedThisTick;
        double backlog;

        if (state.isFailing()) {
            state.setFailureTicks(state.getFailureTicks() + 1);
            served = 0;
            backlog = applyFailure(state, traits, arrivalCount, upstreamPathMs);
            failedThisTick = traits.buffers() ? 0 : arrivalCount;
        } else {
            double demand = state.getQueueDepth() + arrivalCount;
            served = Math.min(demand, serviceable);
            failedThisTick = 0;
            backlog = demand - served;

            if (traits.buffers()) {
                // A queue or a cache holds the overflow rather than rejecting it. Its
                // bound, where declared, is where the difference shows up.
                if (backlog > traits.queueBound()) {
                    double shed = backlog - traits.queueBound();
                    backlog = traits.queueBound();
                    failedThisTick += shed;
                }
            } else {
                // A synchronous component cannot hold work: the overflow is an error.
                failedThisTick = backlog;
                backlog = 0;
            }

            state.setQueueDepth(backlog);
            state.setQueueAgeMs(backlog > 0 ? state.getQueueAgeMs() + tickMillis : 0);
        }

        // Settle this tick's rates before deriving the bypass volume: a cache's miss
        // rate is a fraction of what it served, and a non-cache's bypass *is* its
        // served rate. Reading lastServedRps before it is assigned yields zero and
        // silently starves every downstream component.
        state.setLastServedRps(served / tickSeconds);

        // A cache's misses are what continue downstream and its hits are what complete
        // here, and both come from one draw. Taking it twice -- once for the forwarded
        // volume, once as a coin flip for the hit-rate statistic -- described two
        // different ticks and left the two figures unable to sum to the served volume.
        double missFraction = traits.type() == com.archforge.contracts.canvas.NodeType.CACHE
                ? sampledMissFraction(traits)
                : 0.0;
        double cacheHits = recordCacheOutcome(node, state, traits, arrivalCount, missFraction);

        // Divided across downstream branches, so a fan-out conserves the request count
        // instead of replicating it. See downstreamShare for why.
        double bypassRps = recordBypass(node, state, traits, served, missFraction)
                * downstreamShare(node);
        double latencyMs = pathLatency(state, traits, upstreamPathMs, served);

        state.setLastBypassRps(bypassRps);
        state.setLastPathLatencyMs(latencyMs);
        state.setInFlight(inFlightFor(traits, served));
        // Saturation, not throughput: offered over what this component could have
        // served. Measuring served/capacity instead pins the value at exactly 1.0 the
        // moment a component is overloaded — because it cannot serve more than its
        // ceiling — which makes a saturated component indistinguishable from one
        // running exactly at capacity. Offered/capacity crosses 1 and keeps going, so
        // "asked for twice what it can do" is visible as such.
        state.setLastUtilization(utilisation(arrivalCount, serviceable));
        state.setLastErrorRate(arrivalCount > 0 ? failedThisTick / arrivalCount : 0);

        if (state.isFailing()) {
            state.setInFlight(0);
            state.setLastUtilization(0);
            state.setLastErrorRate(arrivalCount > 0 ? failedThisTick / arrivalCount : 0);
        }

        state.recordLatency(latencyMs);
        state.accumulateTick(served, arrivalCount, failedThisTick,
                state.getLastServedRps(), state.getLastErrorRate(), state.getLastUtilization());

        if (isTrafficRoot(node)) {
            // Counted at the roots only. Counting once per entry-point-typed node
            // double-counted a CDN in front of a gateway in front of a load balancer --
            // a completely normal three-tier front end -- and reported three times the
            // external load. A node is a root only if nothing calls it, which is the
            // definition that survives an entry point appearing mid-graph.
            totalOffered += arrivalCount;
        }
        if (isTerminal(node)) {
            // A request completes where it has nowhere left to go, so served belongs at
            // the terminals.
            totalServed += served;
        } else if (traits.type() == com.archforge.contracts.canvas.NodeType.CACHE) {
            // A cache hit is a completed request too. It is answered in memory and never
            // reaches the store behind it, so counting only the terminals omitted every
            // request the cache did its job on: a read-through chain offered 16 000 and
            // reported 3 280 served — a 20% completion rate, with nothing failed and
            // nothing queued to explain it. The cache was working perfectly.
            totalServed += cacheHits;
        }
        // Failures are counted at *every* node, not only the terminals. A component that
        // sheds load or fails stops a request there and it never reaches a terminal, so
        // accumulating only at the boundaries reported a run with 17 260 requests offered,
        // 15 607 served and **zero** failures — the missing 1 653 having been rejected
        // somewhere in the middle of the graph and then forgotten.
        //
        // No double counting: a request lost at one node was never delivered to the next,
        // so it cannot also be lost further along. The three totals now reconcile as
        // offered = served + failed + whatever is still sitting in a backlog when the run
        // ends, and the residual is the only unaccounted part.
        totalFailed += failedThisTick;

        publish(node, state, tick, observer);
    }

    /**
     * Settles the queues of a failing component and reports the backlog left behind.
     *
     * <p>Three different physical pictures, one per component role, because a cache
     * outage and a database outage are not the same event:
     * <ul>
     *   <li><b>Cache</b> — nothing to hold. Every request is forwarded to whatever the
     *       cache fronts, so the outage shows up as load on the store and no error
     *       rate of its own.</li>
     *   <li><b>Queue</b> — the enqueue still works; only consumption stops. Backlog
     *       grows without bound and the producer sees nothing. This is consumer lag,
     *       and it is invisible to the producer in production too.</li>
     *   <li><b>Everything else</b> — fail-stop. Backlog is shed as errors.</li>
     * </ul>
     */
    private double applyFailure(ComponentState state,
                                NodeCharacteristics traits,
                                double arrivalCount,
                                double upstreamPathMs) {
        if (traits.passesThroughOnFailure()) {
            state.setQueueDepth(0);
            state.setQueueAgeMs(0);
            return 0;
        }
        if (traits.buffers()) {
            double backlog = state.getQueueDepth() + arrivalCount;
            state.setQueueDepth(backlog);
            state.setQueueAgeMs(state.getQueueAgeMs() + tickMillis);
            return backlog;
        }
        state.setQueueDepth(0);
        state.setQueueAgeMs(0);
        return 0;
    }

    /**
     * Volume passed to downstream components this tick, per second.
     *
     * <p>For most components this is what they accepted. For a cache it is only the
     * misses, because the hits were answered in memory. For a failing cache it is
     * everything, because nothing was answered.
     *
     * <p>A cache's miss fraction is drawn here, once, and reused by
     * {@link #recordCacheOutcome}. It used to be drawn twice — once for the forwarded
     * volume and again as a coin flip for the hit-rate statistic — so the published hit
     * rate and the load actually put on the store described two different ticks. Worse,
     * the two could not be reconciled at all: a run offered 16 000 requests through a
     * read-through cache and reported 16 880 served, because the hit count was rounded up
     * to whole lookups per tick independently of the volume that was forwarded.
     */
    private double recordBypass(CompiledNode node,
                                ComponentState state,
                                NodeCharacteristics traits,
                                double served,
                                double missFraction) {
        if (state.isFailing()) {
            if (!traits.passesThroughOnFailure()) {
                return 0;
            }
            // A failed cache forwards everything it was offered, which is the entire
            // point: the outage's cost is that all of this load now lands on the store
            // behind it. With several stores behind the cache the volume is divided
            // between them -- see downstreamShare.
            return state.getLastOfferedRps();
        }
        if (traits.type() == com.archforge.contracts.canvas.NodeType.CACHE) {
            double misses = served * missFraction;
            if (outgoing.getOrDefault(node.getId(), List.of()).isEmpty()) {
                state.setUnroutedBypassRequests(state.getUnroutedBypassRequests() + misses);
            }
            return misses / tickSeconds;
        }
        return state.getLastServedRps();
    }

    /**
     * The share of this node's served load that reaches each downstream component.
     *
     * <p><b>Why a split, and not full-rate fan-out.</b> A DAG edge means "calls", and a
     * call can legitimately produce several — a scatter to three replicas really is
     * three calls. But the load on a component is what the product reports, and a
     * component behind a three-way scatter is receiving <em>one request's worth of
     * traffic divided three ways</em>, not three times the offered load. Sending each
     * branch the full upstream throughput made every branch report the upstream's whole
     * load, which produced the two figures a user would first notice and neither could
     * be true of:
     *
     * <pre>
     *   lb -&gt; api -&gt; {db1, db2},  offered 100 rps
     *   totalOffered 1000   totalServed 2000      (served &gt; offered)
     *   db1 offered 1000    db2 offered 1000      (each saw the entire load)
     * </pre>
     *
     * <p>Equal shares is the only defensible default without edge weights, and it
     * conserves the request count: served can no longer exceed offered, which is the
     * invariant a reader relies on when they see a completion rate.
     *
     * <p>A cache is not exempt, and the exemption it used to carry was doing nothing for
     * the case it was written for. A cache in front of <em>one</em> store already gets
     * the whole of its misses: {@code edges <= 1} returns 1.0 without reference to the
     * node type. The exemption only changed behaviour for a cache with two or more
     * downstream stores, where it made each store receive the full miss volume and
     * therefore <b>duplicated</b> the load:
     *
     * <pre>
     *   lb -&gt; api -&gt; cache -&gt; {db1, db2}
     *   cache serves S, hit rate 0.8  -&gt;  misses = 0.2 S
     *   db1 offered 0.2 S   db2 offered 0.2 S   total 0.4 S &gt; 0.2 S offered downstream
     * </pre>
     *
     * <p>That is the cache-outage scenario's headline number doubled, and it pushed
     * {@code completionRate()} above 1.0 — the very invariant this method exists to
     * protect. Two stores behind one cache is an unusual shape, but a user can draw it
     * and {@code detectUnroutableCaches} only rejects a cache with <em>zero</em>
     * downstream, so it was accepted silently.
     */
    private double downstreamShare(CompiledNode node) {
        int edges = outgoing.getOrDefault(node.getId(), List.of()).size();
        return edges <= 1 ? 1.0 : 1.0 / edges;
    }

    /**
     * Whether a node is where external traffic enters: nothing calls it.
     *
     * <p>Used instead of {@code canBeEntryPoint()} to decide who to count offered load
     * against, because the two disagree on a normal front end. A CDN in front of a
     * gateway in front of a load balancer has three entry-point-typed nodes, and
     * counting the external load at each one reported it three times over.
     *
     * <p>As a *routing* question the type is still the right one — an entry point takes
     * external traffic even with no upstream, which is what {@code arrivalsFor} relies
     * on. Only the accounting needs the structural definition.
     */
    private boolean isTrafficRoot(CompiledNode node) {
        return incoming.getOrDefault(node.getId(), List.of()).isEmpty();
    }

    /**
     * Whether a node ends a request path.
     *
     * <p>Structural: nothing it calls. This is the sink test, and using
     * {@code canBeTerminal()} instead would miss a service that fans out to two
     * databases — the requests end at the databases, not at the service.
     */
    private boolean isTerminal(CompiledNode node) {
        return outgoing.getOrDefault(node.getId(), List.of()).isEmpty();
    }

    /**
     * Cache miss fraction for this tick, perturbed around the declared hit rate.
     *
     * <p>One draw per tick per cache, so the sequence is reproducible from the seed.
     */
    private double sampledMissFraction(NodeCharacteristics traits) {
        double miss = 1.0 - traits.hitRate();
        double perturbed = miss + (random.nextDouble() - 0.5) * MISS_SAMPLE_JITTER;
        return Math.max(0, Math.min(1, perturbed));
    }

    /**
     * Books this tick's cache lookups and returns the volume answered from memory.
     *
     * <p>The returned figure is what the run's {@code served} total credits to the cache:
     * a hit is a completed request, and it never reaches anything downstream. Derived from
     * the same miss fraction that produced the forwarded volume, so hits and misses
     * partition the cache's served volume exactly and the three totals reconcile.
     *
     * <p>The <em>statistic</em> is deliberately a separate whole-number count of lookups,
     * because a cache counts operations and the published hit rate is a ratio of them.
     * Using one number for both let rounding drift the served total above what was
     * offered: a run offered 16 000 requests through a read-through cache and reported
     * 16 880 served.
     */
    private double recordCacheOutcome(CompiledNode node,
                                      ComponentState state,
                                      NodeCharacteristics traits,
                                      double offered,
                                      double missFraction) {
        if (traits.type() != com.archforge.contracts.canvas.NodeType.CACHE) {
            return 0;
        }
        if (state.isFailing()) {
            // Every lookup missed: the cache answered none of them, so nothing completed
            // here and the whole volume goes to the store behind it.
            state.recordCacheLookup(lookups(offered), false);
            return 0;
        }
        state.recordCacheLookup(lookups(offered), missFraction <= 0.5);
        return offered * (1.0 - missFraction);
    }

    /**
     * Whole lookups to book for a tick, never more than the tick's traffic.
     *
     * <p>The obvious {@code Math.max(1, round(served))} — used here until now — books a
     * lookup on a tick that served nothing. A failing cache always serves zero, so it
     * fabricated one miss per failing tick regardless of offered load: 25 invented
     * lookups for a 25-tick outage, and at a 1 rps / 60-tick run roughly 29% of the
     * denominator of the published {@code cache_hit_rate}. That dilutes the hit rate with
     * samples that never happened, in the one component whose whole purpose is to report
     * its hit rate.
     *
     * <p>Rounding to whole lookups is still right — a cache counts operations, not
     * fractions — but a tick with no traffic contributes no operations.
     */
    private static long lookups(double offered) {
        if (offered <= 0) {
            return 0;
        }
        return Math.max(1L, Math.round(offered));
    }

    /**
     * End-to-end latency for this component, along the slowest path.
     *
     * <p>Three components, all of them real:
     * <ul>
     *   <li><b>upstream path</b> — the worst latency already accumulated before this
     *       component, plus this link's propagation delay. Summing every incoming
     *       edge, as the previous engine did, is wrong: a request traverses one of
     *       them, not all of them.</li>
     *   <li><b>service time</b> — the component's declared latency with proportional
     *       jitter. Applied to a 1 ms cache and a 200 ms database alike, so variation
     *       scales with what the component actually does rather than being
     *       {@code Math.random() * 0.2} for everything.</li>
     *   <li><b>queueing delay</b> — Little's law on the current backlog, which is what
     *       turns a saturated store into a latency problem for its callers instead of
     *       a number invented by a multiplier.</li>
     * </ul>
     *
     * <p>A failing component's latency is its timeout, not infinity. Infinity is
     * meaningful as "did not complete" but meaningless inside a percentile: one
     * failure would make every P99 in the run {@code +Infinity} and the number would
     * be worthless precisely when it is most needed. The failure is counted in the
     * error rate instead, which is where it belongs.
     */
    private double pathLatency(ComponentState state,
                               NodeCharacteristics traits,
                               double upstreamPathMs,
                               double served) {
        if (state.isFailing()) {
            if (traits.passesThroughOnFailure()) {
                // A cache that is not answering still forwards; the cost of the outage
                // lands on the component that now has to do the work. The upstream path is
                // already this cache's worst incoming path, link delays included, so
                // nothing is added here.
                //
                // It used to add a sum of every incoming edge's propagation delay. That
                // contradicts the rule everywhere else in this class -- a request traverses
                // one incoming edge, not all of them -- and it only appeared on this branch,
                // so a cache recovered to a *higher* reported latency than it had while
                // healthy: with a 100 ms upstream path and two 5 ms links, healthy was
                // ~106 ms and failing was 100 + 5 + 10 = 115 ms, an entirely invented 10 ms
                // step that grew with every edge the design had.
                return upstreamPathMs;
            }
            if (traits.buffers()) {
                // A queue whose consumer is stalled still accepts messages at full
                // speed, so the producer's latency is unchanged. The backlog is the
                // signal, and it is published as queue_age_ms. Charging the age to
                // latency here made a 30-second stall report a 30-second response.
                return upstreamPathMs + traits.baseLatencyMs();
            }
            return upstreamPathMs + traits.timeoutMs();
        }

        // Deliberately no backlog term here.
        //
        // Little's law (wait = depth / service rate) is the right formula for a
        // *bounded* queue's per-request wait, and it is what this used to add. It is
        // the wrong formula for the backlog a queue accumulates when the drain rate is
        // below the arrival rate, because that backlog never drains: the quotient grows
        // with the run and a 60-second run reports a P99 of 26 000 000 ms — over seven
        // hours for a message that took 5 ms to publish. It is also physically wrong
        // about which request pays it. A message enqueued behind 50 000 others does not
        // wait for all 50 000 to clear; it waits behind however many were ahead of it,
        // and a consumer's own read cost is the worker's, not the queue's.
        //
        // A queue's backlog is therefore reported where it belongs, as
        // queue_depth and queue_age_ms, and a consumer's stall shows up as growing lag
        // rather than as an absurd latency figure. A component with a bounded queue
        // still loses work when it fills (shed against traits.queueBound()), and that
        // appears in the error rate — which is where a user should look for it.
        double latency = upstreamPathMs
                + traits.baseLatencyMs()
                + jitter(traits.baseLatencyMs());
        return Math.max(latency, traits.baseLatencyMs());
    }

    /** Worst accumulated latency of any upstream, plus this node's link delay. */
    private double worstIncomingPathMs(CompiledNode node) {
        List<CompiledEdge> edges = incoming.getOrDefault(node.getId(), List.of());
        double worst = 0;
        for (CompiledEdge edge : edges) {
            ComponentState upstream = states.get(edge.getSource());
            double candidate = (upstream == null ? 0 : upstream.getLastPathLatencyMs())
                    + Math.max(0, edge.getLatencyMs());
            worst = Math.max(worst, candidate);
        }
        return worst;
    }

    /**
     * Saturation for one tick: offered over what the component could have served.
     *
     * <p>Two cases the plain ratio gets wrong, both of which reported a busy component as
     * an idle one:
     *
     * <ul>
     *   <li><b>Capacity resolves to zero.</b> A queue with no consumer, or one whose only
     *       consumer is failing, drains at nothing — {@code downstreamDrainLimit} returns
     *       0 and the serviceable volume is therefore 0. Dividing by it was guarded to
     *       return 0, so the most saturated component in the graph published
     *       {@code cpu_utilization = 0}, never crossed the saturation threshold, and drew
     *       no {@code saturated} finding at all. The backlog was visible only as
     *       {@code queue_depth}. It is reported as saturated instead: it cannot serve any
     *       of what is arriving, which is the definition.</li>
     *   <li><b>Nothing arrived.</b> A component offered nothing is genuinely
     *       unutilised, so that stays 0.</li>
     * </ul>
     */
    private static double utilisation(double arrivalCount, double serviceable) {
        if (serviceable > 0) {
            return arrivalCount / serviceable;
        }
        return arrivalCount > 0 ? 1.0 : 0.0;
    }

    /**
     * Requests this component can complete in one tick.
     *
     * <p>Two independent ceilings on an ordinary component, both of which matter:
     * <ul>
     *   <li>the declared sustained rate, converted to a per-tick volume — so a
     *       sub-second tick is not mistakenly allowed a whole second's worth of work,
     *       which is what treating the rate as a per-tick count does;</li>
     *   <li>the declared concurrency divided by the service time, which is how a worker
     *       with {@code concurrency: 10} and a 100 ms service time ends up at 100/s
     *       rather than the 10 000/s its concurrency alone implies.</li>
     * </ul>
     *
     * <p>A queue gets a third, and it is the one that makes consumer lag real:
     * <b>its drain rate is its consumer's, not its own.</b> A broker accepts messages
     * orders of magnitude faster than any consumer group reads them, so a queue that
     * drained at its own declared rate would never accumulate a backlog and a stalled
     * consumer would be invisible — the opposite of production, and the reason lag is
     * the signal people actually watch.
     */
    private double serviceableThisTick(CompiledNode node, NodeCharacteristics traits) {
        double limit = intrinsicServiceable(traits);
        return node.getType().isMessaging()
                ? Math.min(limit, downstreamDrainLimit(node))
                : limit;
    }

    /**
     * The rate and concurrency ceilings, ignoring what consumes this component.
     *
     * <p>Both are converted to <em>requests completable in one tick</em>, and the two
     * ceilings mix seconds and milliseconds differently, which is worth spelling out
     * because getting it wrong is invisible in code review and catastrophic in output:
     *
     * <ul>
     *   <li><b>Rate → per tick:</b> {@code ratePerSecond * tickSeconds}. A 1000 rps
     *       component completes 1000 requests in a one-second tick, 50 in a 50 ms one.</li>
     *   <li><b>Concurrency → per tick:</b> {@code concurrentRequests * tickMillis /
     *       serviceMillis}. Concurrency counts requests <em>in flight</em> and service
     *       time is in milliseconds, so the ratio is already in milliseconds and must be
     *       scaled by milliseconds. Dividing by {@code tickSeconds} instead makes it
     *       1000x too small: a worker with {@code concurrency: 20} and a 100 ms service
     *       time completed 0.2 requests per tick rather than 200, so it served 0.2 rps
     *       against a declared 100 — a thousandfold under-throughput that presented as a
     *       queue building without draining.</li>
     * </ul>
     */
    private double intrinsicServiceable(NodeCharacteristics traits) {
        double byRate = traits.sustainedRateRps() * tickSeconds;
        if (!traits.hasConcurrencyLimit()) {
            return byRate;
        }
        double byConcurrency = traits.concurrency() * tickMillis
                / Math.max(0.1, traits.baseLatencyMs());
        return Math.min(byRate, byConcurrency);
    }

    /**
     * How fast this queue's consumers can take messages, per tick.
     *
     * <p>Summed across consumers, because a topic read by two consumer groups drains at
     * the sum of their rates rather than the minimum. A consumer that is currently
     * failing contributes nothing, which is what turns a stall into an unbounded backlog
     * instead of a queue that keeps draining into a corpse.
     *
     * <p>One level deep on purpose: a chain of queues would otherwise recurse, and a
     * queue's real drain rate is bounded by the consumer group immediately behind it.
     */
    private double downstreamDrainLimit(CompiledNode node) {
        List<CompiledEdge> edges = outgoing.getOrDefault(node.getId(), List.of());
        if (edges.isEmpty()) {
            // A queue nothing consumes never drains. Lag grows forever, which is the
            // honest reading of a topic with no reader.
            return 0;
        }
        double total = 0;
        for (CompiledEdge edge : edges) {
            CompiledNode downstream = byId.get(edge.getTarget());
            if (downstream == null) {
                continue;
            }
            ComponentState downstreamState = states.get(edge.getTarget());
            if (downstreamState != null && downstreamState.isFailing()) {
                continue;
            }
            total += intrinsicServiceable(NodeCharacteristics.of(downstream));
        }
        return total;
    }

    /** In-flight requests, by Little's law on what was actually served. */
    private double inFlightFor(NodeCharacteristics traits, double served) {
        double concurrent = served * traits.baseLatencyMs() / 1000.0;
        if (traits.hasConcurrencyLimit()) {
            return Math.min(concurrent, traits.concurrency());
        }
        return concurrent;
    }

    /**
     * Arrivals for one component this tick, in requests per second.
     *
     * <p>Entry points take the external offered load; everything else takes what its
     * upstream forwarded. The previous engine gave every node the same request rate
     * regardless of the graph, so a leaf database and the load balancer in front of it
     * were recorded as handling identical traffic — which made the DAG's shape
     * irrelevant to the result.
     */
    private double arrivalsFor(CompiledNode node, double offeredRps) {
        if (node.getType().canBeEntryPoint()) {
            return offeredRps;
        }
        double forwarded = 0;
        for (CompiledEdge edge : incoming.getOrDefault(node.getId(), List.of())) {
            ComponentState upstream = states.get(edge.getSource());
            if (upstream != null) {
                forwarded += upstream.getLastBypassRps();
            }
        }
        return forwarded;
    }

    /**
     * Service-time jitter, proportional to the service time.
     *
     * <p>The previous engine applied {@code Math.random() * 0.2} to every node
     * regardless of type, so a 1 ms cache varied by up to 200 ms — larger than the
     * service it was modelling — while a 5 s batch job did not vary at all.
     */
    private double jitter(double baseLatencyMs) {
        return baseLatencyMs * random.nextDouble() * JITTER_FRACTION;
    }

    static final double JITTER_FRACTION = 0.3;

    /**
     * Turns declared failure windows into the per-tick failing flag.
     *
     * <p>Recovery is measured as the ticks spent draining after the failure ends, not
     * as the node's declared {@code recoveryTimeMs}: the declared figure is a
     * catalogue default and the drain is what the run actually observed, which for a
     * component with a backlog is longer than the declared restart time.
     */
    private void applyFailures(long tick) {
        for (CompiledNode node : order) {
            ComponentState state = states.get(node.getId());
            if (state == null) {
                continue;
            }
            // Looked up, not scanned: the previous form built a Stream per node per tick
            // and cost O(nodes x failures) each time.
            boolean shouldFail = false;
            for (FailureInjector.Failure failure : failuresByNode.getOrDefault(node.getId(), List.of())) {
                if (failure.isActiveAt(tick)) {
                    shouldFail = true;
                    break;
                }
            }

            if (shouldFail && !state.isFailing()) {
                state.setFailing(true);
            } else if (!shouldFail && state.isFailing()) {
                state.setFailing(false);
                state.setRecoveryTimeMs((double) state.getFailureTicks() * tickMillis);
            }
        }
    }

    /**
     * Emits one sample per metric per component per tick.
     *
     * <p>Every component reports the same core metric set so a chart never has to
     * special-case a node type; metrics that do not apply to a role read as 0 rather
     * than being absent, so a consumer can rely on the series existing.
     */
    private void publish(CompiledNode node, ComponentState state, long tick, TickObserver observer) {
        String phase = currentPhase;
        boolean failing = state.isFailing();

        observer.onSample(node, state, MetricNames.LATENCY_MS, state.getLastPathLatencyMs(), tick, phase, failing);
        observer.onSample(node, state, MetricNames.THROUGHPUT_RPS, state.getLastServedRps(), tick, phase, failing);
        observer.onSample(node, state, MetricNames.SUCCESS_RPS, state.getLastServedRps(), tick, phase, failing);
        observer.onSample(node, state, MetricNames.ERROR_RATE, state.getLastErrorRate(), tick, phase, failing);
        observer.onSample(node, state, MetricNames.CONCURRENCY, state.getInFlight(), tick, phase, failing);
        observer.onSample(node, state, MetricNames.CPU_UTILIZATION,
                state.getLastUtilization(), tick, phase, failing);
        observer.onSample(node, state, MetricNames.FAILURE_ACTIVE, failing ? 1 : 0, tick, phase, failing);

        if (node.getType().isMessaging() || node.getType().getCategory()
                == com.archforge.contracts.canvas.NodeCategory.MESSAGING) {
            observer.onSample(node, state, MetricNames.QUEUE_DEPTH, state.getQueueDepth(), tick, phase, failing);
            observer.onSample(node, state, MetricNames.QUEUE_AGE_MS, state.getQueueAgeMs(), tick, phase, failing);
        }
        if (node.getType() == com.archforge.contracts.canvas.NodeType.CACHE) {
            observer.onSample(node, state, MetricNames.CACHE_HIT_RATE,
                    Math.max(0, state.getCacheHitRate()), tick, phase, failing);
        }
        if (NodeCharacteristics.typeIsStorage(node.getType())) {
            observer.onSample(node, state, MetricNames.DB_UTILIZATION, state.getLastUtilization(), tick, phase, failing);
            observer.onSample(node, state, MetricNames.CONNECTION_POOL_USAGE,
                    Math.min(1.5, state.getLastUtilization()), tick, phase, failing);        }
    }
}
