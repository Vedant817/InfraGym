package com.archforge.simulation.engine;

import com.archforge.contracts.graph.CompiledEdge;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.MetricNames;
import com.archforge.simulation.failure.FailureInjector;
import com.archforge.simulation.workload.WorkloadProfile;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
 *       backlog converts into latency for everything upstream of it — via Little's law,
 *       not a multiplier.</li>
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

        detectUnroutableCaches();
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

    /** Requests that failed at a terminal component, over the whole run. */
    public double totalFailed() {
        return totalFailed;
    }

    /**
     * Executes every tick, handing each measurement to the observer.
     *
     * @param tickCount total ticks to execute
     * @param observer  invoked once per tick per component per metric
     */
    public void run(long tickCount, TickObserver observer) {
        for (long tick = 0; tick < tickCount; tick++) {
            step(tick, observer);
            ticksExecuted = tick + 1;
        }
    }

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

        double bypassRps = recordBypass(node, state, traits, served);
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
        state.setLastUtilization(serviceable > 0 ? arrivalCount / serviceable : 0);
        state.setLastErrorRate(arrivalCount > 0 ? failedThisTick / arrivalCount : 0);

        if (state.isFailing()) {
            state.setInFlight(0);
            state.setLastUtilization(0);
            state.setLastErrorRate(arrivalCount > 0 ? failedThisTick / arrivalCount : 0);
        }

        recordCacheOutcome(node, state, traits, served);
        state.recordLatency(latencyMs);
        state.accumulateTick(served, arrivalCount, failedThisTick,
                state.getLastServedRps(), state.getLastErrorRate(), state.getLastUtilization());
        if (node.getType().canBeEntryPoint()) {
            totalOffered += arrivalCount;
        }
        if (outgoing.getOrDefault(node.getId(), List.of()).isEmpty()) {
            totalServed += served;
            totalFailed += failedThisTick;
        }

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
     */
    private double recordBypass(CompiledNode node,
                                ComponentState state,
                                NodeCharacteristics traits,
                                double served) {
        if (state.isFailing()) {
            if (!traits.passesThroughOnFailure()) {
                return 0;
            }
            return state.getLastOfferedRps();
        }
        if (traits.type() == com.archforge.contracts.canvas.NodeType.CACHE) {
            double misses = served * (sampledMissFraction(traits));
            if (outgoing.getOrDefault(node.getId(), List.of()).isEmpty()) {
                state.setUnroutedBypassRequests(state.getUnroutedBypassRequests() + misses);
            }
            return misses / tickSeconds;
        }
        return state.getLastServedRps();
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

    private void recordCacheOutcome(CompiledNode node,
                                    ComponentState state,
                                    NodeCharacteristics traits,
                                    double served) {
        if (traits.type() != com.archforge.contracts.canvas.NodeType.CACHE) {
            return;
        }
        if (state.isFailing()) {
            // Every lookup missed: the cache answered none of them.
            state.recordCacheLookup(Math.max(1, (long) Math.round(served)), false);
            return;
        }
        boolean hit = served > 0 && random.nextDouble() >= (1.0 - traits.hitRate());
        state.recordCacheLookup(Math.max(1, (long) Math.round(served)), hit);
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
                // A cache that is not answering still forwards; the cost of the
                // outage lands on the component that now has to do the work.
                return upstreamPathMs + propagationMs(state.getNodeId());
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

    /** Sum of propagation delays into a node; used for pass-through components. */
    private double propagationMs(String nodeId) {
        double total = 0;
        for (CompiledEdge edge : incoming.getOrDefault(nodeId, List.of())) {
            total += Math.max(0, edge.getLatencyMs());
        }
        return total;
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
            boolean shouldFail = failures.stream()
                    .anyMatch(failure -> failure.nodeId().equals(node.getId())
                            && failure.isActiveAt(tick));

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
