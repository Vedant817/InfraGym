package com.archforge.contracts.lifecycle;

/**
 * Units carried on every telemetry sample.
 *
 * <p>Without a unit on the wire, the frontend has to hard-code a per-metric
 * lookup and the AI evaluator has to guess. Declaring them here lets a chart
 * formatter and the evaluator's tool layer both work from the event.
 */
public final class MetricUnits {

    public static final String MILLISECOND = "ms";
    public static final String REQUESTS_PER_SECOND = "rps";
    public static final String RATIO = "ratio";
    public static final String PERCENT = "percent";
    public static final String COUNT = "count";
    public static final String BOOLEAN = "boolean";
    public static final String BYTES = "bytes";
    public static final String MEGABIT_PER_SECOND = "mbps";

    private MetricUnits() {
    }

    /**
     * Canonical unit for a metric name.
     *
     * <p>Returns {@code null} for an unknown name rather than a wrong guess, so
     * callers are forced to decide how to render an unrecognised metric instead
     * of silently mislabelling an axis.
     */
    public static String forMetric(String metricName) {
        if (metricName == null) {
            return null;
        }
        return switch (metricName) {
            case MetricNames.THROUGHPUT_RPS, MetricNames.SUCCESS_RPS -> REQUESTS_PER_SECOND;
            case MetricNames.LATENCY_MS, MetricNames.P50_LATENCY_MS, MetricNames.P95_LATENCY_MS,
                 MetricNames.P99_LATENCY_MS, MetricNames.RECOVERY_TIME_MS, MetricNames.QUEUE_AGE_MS -> MILLISECOND;
            case MetricNames.ERROR_RATE, MetricNames.CACHE_HIT_RATE, MetricNames.DB_UTILIZATION,
                 MetricNames.CPU_UTILIZATION, MetricNames.MEMORY_UTILIZATION,
                 MetricNames.CONNECTION_POOL_USAGE -> RATIO;
            case MetricNames.QUEUE_DEPTH, MetricNames.WORKER_QUEUE_DEPTH, MetricNames.CONCURRENCY -> COUNT;
            case MetricNames.FAILURE_ACTIVE -> BOOLEAN;
            default -> null;
        };
    }
}
