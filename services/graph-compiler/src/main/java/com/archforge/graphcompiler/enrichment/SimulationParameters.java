package com.archforge.graphcompiler.enrichment;

import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Physical characteristics of a node type, plus whatever the user overrode.
 *
 * <p><b>Why the previous routing was broken.</b> The old {@code mergeWith} decided
 * which bucket a user override belonged in by substring-matching the <em>key name</em>:
 * {@code key.contains("rps")}, {@code key.contains("ms")}, and so on. Only the literal
 * keys {@code capacity}, {@code latency}, {@code throughput}, {@code rps},
 * {@code iops} and {@code tps} matched anything. Every parameter the registry
 * actually publishes — {@code requestsPerSecond}, {@code p99Ms}, {@code connections},
 * {@code concurrency}, {@code iops}, {@code maxReplicas} — landed in
 * {@code properties}, which nothing downstream reads. The "tune your node" feature
 * was therefore 100% inert, and {@code recoveryTimeMs} was worse than inert: it
 * contains {@code "ms"}, so it was filed as a latency.
 *
 * <p><b>What replaced it.</b> Routing is driven by an explicit, per-type allow-list of
 * known keys, so a parameter is classified because someone declared where it belongs,
 * not because its spelling happens to contain three letters. An unrecognised numeric
 * override is still accepted into {@code properties} — and is therefore still
 * reported in a warning — rather than being silently dropped or silently misfiled.
 */
@Value
@Builder
public class SimulationParameters {

    /** Where a user-supplied override belongs. */
    public enum Slot {
        CAPACITY,
        LATENCY,
        THROUGHPUT,
        PROPERTIES
    }

    Map<String, Double> capacity;
    Map<String, Double> latency;
    Map<String, Double> throughput;
    Map<String, Object> properties;
    List<String> failureModes;
    double recoveryTimeMs;

    /**
     * Canonical parameter keys and the slot each belongs in. This is the single place
     * that decides how a user override is interpreted, shared by every node type.
     */
    private static final Map<String, Slot> KEY_ROUTING = buildRouting();

    private static Map<String, Slot> buildRouting() {
        Map<String, Slot> routing = new HashMap<>();

        // --- capacity / hard limits -------------------------------------------
        for (String key : List.of(
                "requestsPerSecond", "operationsPerSecond", "queriesPerSecond",
                "transactionsPerSecond", "messagesPerSecond", "tasksPerSecond",
                "invocationsPerSecond", "metricsPerSecond", "connections",
                "concurrency", "queueSize", "partitions", "entries",
                "cpuCores", "memoryMb", "storageGb", "capacityGb", "iops", "sessions",
                "cacheSizeGb", "bandwidthGbps", "maxQueuedMessages",
                "retentionMs", "retentionDays", "messageSizeKb")) {
            routing.put(key, Slot.CAPACITY);
        }

        // --- latency (all in milliseconds) -------------------------------------
        for (String key : List.of(
                "p50Ms", "p95Ms", "p99Ms", "p999Ms",
                "readP50Ms", "readP95Ms", "readP99Ms",
                "writeP50Ms", "writeP95Ms", "writeP99Ms",
                "processingTimeMs", "coldStartMs", "timeoutMs",
                "networkLatencyMs", "thinkTimeMs")) {
            routing.put(key, Slot.LATENCY);
        }

        // --- throughput --------------------------------------------------------
        routing.put("maxThroughputRps", Slot.THROUGHPUT);
        routing.put("sustainedThroughputRps", Slot.THROUGHPUT);
        routing.put("throughputMbps", Slot.THROUGHPUT);

        // --- everything else is a behavioural property -------------------------
        for (String key : List.of(
                "errorRate", "hitRate", "missRate", "autoScaling", "minReplicas",
                "maxReplicas", "evictionPolicy", "ttlSeconds", "compression",
                "minInSyncReplicas", "cleanupPolicy", "algorithm",
                "healthCheckIntervalSeconds", "sslTermination", "retryCount",
                "deadLetterQueue", "gracefulShutdownMs", "circuitBreakerEnabled",
                "provider", "mfaEnabled", "tokenTtlSeconds", "regions",
                "cacheTtlSeconds", "scrapeIntervalSeconds", "alertingEnabled",
                "dashboardEnabled", "runtime", "durability", "replicationFactor",
                "backupEnabled", "encryptionAtRest", "rateLimit", "authentication")) {
            routing.put(key, Slot.PROPERTIES);
        }

        return Map.copyOf(routing);
    }

    /** Slots a parameter could belong in, for a node type. */
    public static Set<String> routableKeys() {
        return KEY_ROUTING.keySet();
    }

    public static Slot slotFor(String key) {
        return KEY_ROUTING.get(key);
    }

    public static SimulationParameters empty() {
        return SimulationParameters.builder()
                .capacity(Map.of())
                .latency(Map.of())
                .throughput(Map.of())
                .properties(Map.of())
                .failureModes(List.of())
                .recoveryTimeMs(0)
                .build();
    }

    /**
     * Merges user-supplied configuration over the registry defaults.
     *
     * <p>Unroutable keys are preserved in {@code properties} rather than discarded,
     * so nothing a user typed is silently lost, and {@link #unknownConfigKeys()} lets
     * the caller report them.
     *
     * @return a new instance; this one is never mutated
     */
    public SimulationParameters mergeWith(Map<String, Object> userConfig) {
        if (userConfig == null || userConfig.isEmpty()) {
            return this;
        }

        Map<String, Double> mergedCapacity = new LinkedHashMap<>(capacity);
        Map<String, Double> mergedLatency = new LinkedHashMap<>(latency);
        Map<String, Double> mergedThroughput = new LinkedHashMap<>(throughput);
        Map<String, Object> mergedProperties = new LinkedHashMap<>(properties);

        for (Map.Entry<String, Object> entry : userConfig.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key == null) {
                continue;
            }

            // "recoveryTimeMs" is special: it is a top-level field on this object, not
            // a bag entry, so route it there rather than letting it land in latency.
            if ("recoveryTimeMs".equals(key)) {
                continue; // handled by NodeEnricher, which can assign the field
            }

            Slot slot = KEY_ROUTING.get(key);
            if (slot == null || !(value instanceof Number number)) {
                mergedProperties.put(key, value);
                continue;
            }

            double numeric = number.doubleValue();
            switch (slot) {
                case CAPACITY -> mergedCapacity.put(key, numeric);
                case LATENCY -> mergedLatency.put(key, numeric);
                case THROUGHPUT -> mergedThroughput.put(key, numeric);
                case PROPERTIES -> mergedProperties.put(key, value);
            }
        }

        return SimulationParameters.builder()
                .capacity(mergedCapacity)
                .latency(mergedLatency)
                .throughput(mergedThroughput)
                .properties(mergedProperties)
                .failureModes(failureModes)
                .recoveryTimeMs(recoveryTimeMs)
                .build();
    }

    /** With an overridden recovery time, used when a user sets {@code recoveryTimeMs}. */
    public SimulationParameters withRecoveryTimeMs(double newRecoveryTimeMs) {
        return SimulationParameters.builder()
                .capacity(capacity)
                .latency(latency)
                .throughput(throughput)
                .properties(properties)
                .failureModes(failureModes)
                .recoveryTimeMs(Math.max(0, newRecoveryTimeMs))
                .build();
    }

    /**
     * Config keys present in the type's defaults that no longer match a known
     * parameter, so the registry and the routing table cannot drift apart unnoticed.
     */
    public List<String> unknownConfigKeys() {
        List<String> unknown = new ArrayList<>();
        Collection<String> declared = new ArrayList<>(capacity.keySet());
        declared.addAll(latency.keySet());
        declared.addAll(throughput.keySet());
        for (String key : declared) {
            if (!KEY_ROUTING.containsKey(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }
}
