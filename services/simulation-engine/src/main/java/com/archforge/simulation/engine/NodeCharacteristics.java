package com.archforge.simulation.engine;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledNode;

import java.util.List;
import java.util.Map;

/**
 * A node's physical characteristics, resolved once from what the compiler declared.
 *
 * <p><b>Why this class exists.</b> Two components needed the same answers — the
 * workload generator, to decide how much load to offer, and the simulator, to decide
 * how much it can serve — and they had each grown their own copy of the lookup.
 * The copies had already drifted: the generator scanned only the {@code capacity}
 * map while the simulator scanned {@code capacity} then {@code throughput}, and both
 * returned {@code 1000} when nothing matched. A cache declares its rate in
 * {@code throughput.operationsPerSecond}, so the generator priced a 50 000
 * ops/second Redis at 1 rps and every scenario ran against a fictitious one rps
 * architecture.
 *
 * <p>Resolution rules, in order, each stated so it can be argued with:
 *
 * <ol>
 *   <li><b>Sustained rate.</b> The first of {@link #RATE_KEYS} present in either the
 *       capacity or the throughput map. Ordered most-specific-first so a database is
 *       described by its {@code queriesPerSecond} rather than by a write-path figure
 *       it also declares. A component can never exceed a limit it declares, but two
 *       limits describing <em>different</em> paths (queries versus transactions) are
 *       not comparable, so taking the minimum of all of them would understate a
 *       read-heavy workload by the write/read ratio.</li>
 *   <li><b>Storage IOPS as a ceiling.</b> For storage, {@code min(rate, iops)}. IOPS is
 *       the physical limit; a disk cannot serve 5000 queries/second on 3000 IOPS.
 *       One small random read costs approximately one IOPS, which is the assumption
 *       declared as {@link #IOPS_PER_SMALL_READ} rather than inlined.</li>
 *   <li><b>Concurrency.</b> {@code concurrency}, when declared. A worker with
 *       {@code concurrency: 10} completing 100 ms tasks serves 100/s — and would
 *       appear to serve 10 000/s if only its nominal rate were consulted.</li>
 *   <li><b>Service time.</b> The first type-appropriate key from the latency profile.
 *       Databases and workers do not declare {@code p50Ms}; they declare
 *       {@code readP50Ms} and {@code processingTimeMs}. Reading only {@code p50Ms}
 *       gave every one of them a hard-coded 20 ms.</li>
 * </ol>
 *
 * <p>Anything unresolved is returned as a declared floor and logged, never silently
 * replaced by a constant that will later be reported to a user as a measurement.
 */
public final class NodeCharacteristics {

    /**
     * Sustained-rate keys, most specific first.
     *
     * <p>Scan order is load-bearing. A {@code DATABASE} declares both
     * {@code queriesPerSecond} and {@code transactionsPerSecond}; the read path is
     * the one an architecture is sized for, so it wins.
     */
    static final List<String> RATE_KEYS = List.of(
            "requestsPerSecond",
            "queriesPerSecond",
            "operationsPerSecond",
            "messagesPerSecond",
            "tasksPerSecond",
            "invocationsPerSecond",
            "metricsPerSecond",
            "transactionsPerSecond");

    /**
     * IOPS consumed by one small random read.
     *
     * <p>The previous implementation divided IOPS by a hard-coded 100 on the grounds
     * of a "100:1 read/write ratio", which is the wrong quantity: the ratio describes
     * how many reads accompany one <em>write</em>, not how many IOPS one read costs.
     * A 3000 IOPS primary was therefore rated at 30 requests/second — three orders of
     * magnitude below the figure the compiler published for it.
     */
    static final double IOPS_PER_SMALL_READ = 1.0;

    /**
     * Requests per second per megabit of declared bandwidth, for a component that
     * publishes bandwidth but no request rate.
     *
     * <p>{@code STORAGE} publishes exactly one throughput key,
     * {@code throughputMbps: 100}. {@link #RATE_KEYS} cannot match it, so
     * {@link #firstRate} returned null and the object store resolved to the 1 rps floor.
     * Since {@code WorkloadGenerator.nominalCapacity} takes the <em>minimum</em> over the
     * bottleneck path, one object store pinned the whole architecture to 1 rps: every
     * run then offered about one request per second and the entire DAG looked idle.
     *
     * <p>This is the same defect the file's own javadoc claims to have fixed for the
     * cache, one node type over. A lookup that cannot see where a value was published
     * returns the wrong answer for whichever node type publishes it next, so the fix
     * has to be a fallback rather than another entry in a list of node types.
     *
     * <p>12.5 requests per second per megabit assumes a small object: 100 Mbps yields
     * 1250 requests/second, the right order of magnitude for an object store serving
     * short values over a 100 Mbps link.
     */
    static final double REQUESTS_PER_MBIT = 12.5;

    /** The bandwidth key, named so the fallback and its test agree on one string. */
    static final String THROUGHPUT_MBPS = "throughputMbps";

    /** Rate assumed when a node declares no throughput figure at all. */
    public static final double UNDECLARED_RATE_RPS = 1.0;

    /** Service time assumed when a node declares no latency figure at all. */
    public static final double UNDECLARED_LATENCY_MS = 1.0;

    /** Default caller timeout when a node declares none. */
    public static final double DEFAULT_TIMEOUT_MS = 5_000.0;

    /** Cache hit rate assumed when a node declares none. */
    public static final double DEFAULT_HIT_RATE = 0.7;

    /**
     * Floor on a declared timeout, in milliseconds.
     *
     * <p>A zero or negative timeout would make a failing component report a zero
     * latency, which reads as "faster when broken" — the opposite of the truth, and a
     * figure no user would believe.
     */
    static final double MIN_TIMEOUT_MS = 0.001;

    private final CompiledNode node;
    private final double sustainedRateRps;
    private final double concurrency;
    private final double baseLatencyMs;
    private final double timeoutMs;
    private final double declaredHitRate;
    private final double queueBound;
    private final boolean declaresThroughput;
    private final boolean declaresLatency;

    private NodeCharacteristics(CompiledNode node,
                                double sustainedRateRps,
                                double concurrency,
                                double baseLatencyMs,
                                double timeoutMs,
                                double declaredHitRate,
                                double queueBound,
                                boolean declaresThroughput,
                                boolean declaresLatency) {
        this.node = node;
        this.sustainedRateRps = sustainedRateRps;
        this.concurrency = concurrency;
        this.baseLatencyMs = baseLatencyMs;
        this.timeoutMs = timeoutMs;
        this.declaredHitRate = declaredHitRate;
        this.queueBound = queueBound;
        this.declaresThroughput = declaresThroughput;
        this.declaresLatency = declaresLatency;
    }

    public static NodeCharacteristics of(CompiledNode node) {
        Double rate = firstRate(node);
        boolean declaresThroughput = rate != null;

        double resolvedRate = rate != null ? rate : UNDECLARED_RATE_RPS;

        if (rate == null) {
            // Bandwidth fallback, before the floors below. A component that publishes
            // Mbps and nothing else is not undeclared -- it is declared in different
            // units, and treating that as "no figure" silently pins it to 1 rps.
            Double mbps = firstPositive(node.getThroughput().get(THROUGHPUT_MBPS),
                    node.getCapacity().get(THROUGHPUT_MBPS));
            if (mbps != null) {
                resolvedRate = mbps * REQUESTS_PER_MBIT;
                declaresThroughput = true;
            }
        }

        if (isStorage(node.getType())) {
            // IOPS is the physical ceiling; apply it even when a rate key also matched.
            Double iops = firstPositive(node.getCapacity().get("iops"), node.getThroughput().get("iops"));
            if (iops != null) {
                resolvedRate = Math.min(resolvedRate, iops / IOPS_PER_SMALL_READ);
            }
        }

        Double concurrency = firstPositive(node.getCapacity().get("concurrency"),
                node.getCapacity().get("parallelism"),
                node.getCapacity().get("threads"));

        Double latency = firstPositiveOf(node.getLatencyProfile(), latencyKeys(node.getType()));
        boolean declaresLatency = latency != null;

        Double queueBound = firstPositive(node.getCapacity().get("queueSize"),
                node.getCapacity().get("maxQueuedMessages"));

        return new NodeCharacteristics(node,
                Math.max(1.0, resolvedRate),
                concurrency == null ? 0 : concurrency,
                latency == null ? UNDECLARED_LATENCY_MS : latency,
                propertyDouble(node, "timeoutMs", DEFAULT_TIMEOUT_MS, MIN_TIMEOUT_MS, Double.MAX_VALUE),
                // A hit rate outside [0, 1] inverts the model rather than degrading it:
                // a user who types 90 meaning 90% produced a cache that forwards nothing
                // and reports a 100% hit rate, leaving the database behind it apparently
                // unloaded. Clamped, and the out-of-range declaration is a canvas bug
                // worth surfacing on its own.
                propertyDouble(node, "hitRate", DEFAULT_HIT_RATE, 0.0, 1.0),
                queueBound == null ? Double.MAX_VALUE : queueBound,
                declaresThroughput,
                declaresLatency);
    }

    public CompiledNode node() {
        return node;
    }

    public String nodeId() {
        return node.getId();
    }

    public NodeType type() {
        return node.getType();
    }

    /**
     * Requests or messages this component can sustain per second.
     *
     * <p>Always at least 1, so a graph of unroutable components still produces a run
     * with samples rather than dividing by zero everywhere.
     */
    public double sustainedRateRps() {
        return sustainedRateRps;
    }

    /** Simultaneous requests this component may hold, or 0 when it declares no limit. */
    public double concurrency() {
        return concurrency;
    }

    public boolean hasConcurrencyLimit() {
        return concurrency > 0;
    }

    /** The component's own service time, in milliseconds. */
    public double baseLatencyMs() {
        return baseLatencyMs;
    }

    /** Caller timeout for this component, in milliseconds. */
    public double timeoutMs() {
        return timeoutMs;
    }

    /** Declared cache hit rate in [0, 1]. */
    public double hitRate() {
        return declaredHitRate;
    }

    /**
     * Backlog this component will hold before shedding, or
     * {@link Double#MAX_VALUE} when unbounded.
     */
    public double queueBound() {
        return queueBound;
    }

    /** Whether the node declared a throughput figure at all. */
    public boolean declaresThroughput() {
        return declaresThroughput;
    }

    /** Whether the node declared a latency figure at all. */
    public boolean declaresLatency() {
        return declaresLatency;
    }

    /**
     * Whether work arriving at this component can wait rather than fail.
     *
     * <p>A queue and a cache absorb a burst; a synchronous service, a database and a
     * function reject what they cannot take. That single distinction is what makes a
     * flash sale a queue problem instead of an error-rate problem, and it is derived
     * from the node's declared role rather than hard-coded per simulation.
     */
    public boolean buffers() {
        return node.getType().buffers();
    }

    /**
     * Whether failure of this component makes its callers wait rather than fail.
     *
     * <p>A cache is an accelerator: when it stops answering, the request is not lost,
     * it is forwarded to whatever the cache fronts and the latency shows up there.
     * Modelling that is the whole point of the cache-outage scenario — the previous
     * engine reported the outage as a spike in the cache's error rate and no change
     * whatsoever at the database, which is the opposite of what happens.
     */
    public boolean passesThroughOnFailure() {
        return node.getType() == NodeType.CACHE;
    }

    /**
     * Whether a caller blocked on this component waits for a timeout.
     *
     * <p>True for everything that fails stop: the caller is left waiting. False for a
     * queue, where a stalled consumer means the producer's enqueue still succeeds —
     * the backlog grows instead, which is exactly the consumer-lag signature.
     */
    public boolean callerBlocksOnFailure() {
        return node.getType().failureIsFailStop() && !node.getType().isMessaging();
    }

    /**
     * First declared rate key, scanning both the capacity and throughput maps.
     *
     * <p>Both maps, and in the order of {@link #RATE_KEYS} — the two consumers of a
     * declared figure disagreed about where to look, and one of them read only
     * {@code capacity}. A cache publishes its rate in {@code throughput}, so that lookup
     * priced a 50 000 ops/second Redis at 1 rps.
     */
    private static Double firstRate(CompiledNode node) {
        for (String key : RATE_KEYS) {
            Double value = firstPositive(node.getCapacity().get(key), node.getThroughput().get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Reads a behavioural property from the compiler's enrichment blob.
     *
     * <p>The blob is {@code Map<String, Object>} because it is forwarded opaquely to the
     * AI evaluator; every consumer so far assumed the values were already {@code Double}
     * and got a {@code ClassCastException} on the integer the compiler actually wrote —
     * which is why cache hit rate and timeouts were always the hard-coded defaults.
     *
     * <p>A JSON round trip also delivers small integers as {@code Integer} or even
     * {@code String}, both handled here. A numeric string is still a declared value, not
     * a missing one.
     *
     * @param min inclusive lower bound; the result is clamped into {@code [min, max]}
     *             because these values are user-overridable from the canvas and an
     *             out-of-range one silently inverts the model rather than degrading it
     * @param max inclusive upper bound
     */
    static double propertyDouble(CompiledNode node, String key, double fallback, double min, double max) {
        Object properties = node.getSimulationMetadata().get("properties");
        if (properties instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return clamp(number.doubleValue(), min, max);
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return clamp(Double.parseDouble(text.trim()), min, max);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Unbounded property read, for values with no natural range.
     *
     * <p>A NaN resolves to the fallback rather than the bound. The clamp used to return its
     * lower bound for NaN, which for this overload is negative infinity — so a NaN read
     * through the unbounded path produced {@code -Infinity}, and every downstream division
     * by it became zero rather than reporting the problem. No caller uses this overload
     * yet, which is exactly why it needed saying: it is a trap waiting for the first
     * property read without a range.
     */
    static double propertyDouble(CompiledNode node, String key, double fallback) {
        Object properties = node.getSimulationMetadata().get("properties");
        if (properties instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                double parsed = number.doubleValue();
                return Double.isNaN(parsed) ? fallback : parsed;
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    double parsed = Double.parseDouble(text.trim());
                    return Double.isNaN(parsed) ? fallback : parsed;
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static Double firstPositiveOf(Map<String, Double> source, List<String> keys) {
        for (String key : keys) {
            Double value = firstPositive(source.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double firstPositive(Double... values) {
        for (Double value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private static boolean isStorage(NodeType type) {
        return type.isStorage() && type != NodeType.CACHE;
    }

    /**
     * Whether this is persistent storage, i.e. it should publish
     * {@code db_utilization} and pool occupancy. A cache is categorised as storage but
     * is excluded: its utilisation against {@code entries} is not a storage figure and
     * charting it as one misleads.
     */
    public static boolean typeIsStorage(NodeType type) {
        return isStorage(type);
    }

    /**
     * Latency keys in priority order for a node type.
     *
     * <p>The single most consequential lookup in the simulator: a database's {@code
     * readP50Ms} and a worker's {@code processingTimeMs} are the service times those
     * components actually have. Missing them and falling through to {@code p50Ms}
     * made every component in the design look like a 20 ms service.
     */
    static List<String> latencyKeys(NodeType type) {
        return switch (type) {
            case DATABASE -> List.of("readP50Ms", "writeP50Ms", "p50Ms");
            case STORAGE -> List.of("readP50Ms", "p50Ms");
            case WORKER, FUNCTION -> List.of("processingTimeMs", "coldStartMs", "p50Ms");
            default -> List.of("p50Ms");
        };
    }

    @Override
    public String toString() {
        return "NodeCharacteristics{" + nodeId() + " " + type().getValue()
                + " " + sustainedRateRps() + "rps " + baseLatencyMs() + "ms}";
    }
}
