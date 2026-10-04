package com.archforge.contracts.lifecycle;

/**
 * Canonical telemetry metric identifiers.
 *
 * <p>These strings are a wire contract: they appear as the {@code metricName}
 * field on {@code simulation.telemetry}, they are re-emitted verbatim by the
 * aggregator on {@code aggregated.metrics}, and the frontend and the AI tool
 * layer switch on them. Defining them once here means a typo is a compile error
 * rather than a chart that silently never renders.
 */
public final class MetricNames {

    /** Requests (or messages) offered to the component per second. */
    public static final String THROUGHPUT_RPS = "throughput_rps";

    /** Requests successfully completed per second, after failures. */
    public static final String SUCCESS_RPS = "success_rps";

    /** Fraction of offered requests that failed, in [0, 1]. */
    public static final String ERROR_RATE = "error_rate";

    /** End-to-end latency as seen by this component, in milliseconds. */
    public static final String LATENCY_MS = "latency_ms";

    public static final String P50_LATENCY_MS = "p50_latency_ms";
    public static final String P95_LATENCY_MS = "p95_latency_ms";
    public static final String P99_LATENCY_MS = "p99_latency_ms";

    /** Mean time to repair while a failure was active, in milliseconds. */
    public static final String RECOVERY_TIME_MS = "recovery_time_ms";

    /** Messages waiting in a queue or topic. */
    public static final String QUEUE_DEPTH = "queue_depth";

    /** Oldest unprocessed message age, in milliseconds. */
    public static final String QUEUE_AGE_MS = "queue_age_ms";

    /** Fraction of cache lookups served from memory, in [0, 1]. */
    public static final String CACHE_HIT_RATE = "cache_hit_rate";

    /** Storage utilisation against its declared capacity, in [0, 1+]. */
    public static final String DB_UTILIZATION = "db_utilization";

    /** CPU utilisation, in [0, 1+]. */
    public static final String CPU_UTILIZATION = "cpu_utilization";

    /** Memory utilisation, in [0, 1+]. */
    public static final String MEMORY_UTILIZATION = "memory_utilization";

    /** Active connection-pool occupancy for storage or upstream calls. */
    public static final String CONNECTION_POOL_USAGE = "connection_pool_usage";

    /** Number of in-flight requests this component is currently serving. */
    public static final String CONCURRENCY = "concurrency";

    /** Backlog of work waiting for a worker slot. */
    public static final String WORKER_QUEUE_DEPTH = "worker_queue_depth";

    /** 0 or 1 for the duration of an injected failure. */
    public static final String FAILURE_ACTIVE = "failure_active";

    private MetricNames() {
    }
}
