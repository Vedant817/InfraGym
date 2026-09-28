package com.archforge.graphcompiler.enrichment;

import com.archforge.graphcompiler.schema.NodeType;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SimulationParameterRegistry {

    private final Map<NodeType, SimulationParameters> registry = new EnumMap<>(NodeType.class);

    public SimulationParameterRegistry() {
        initializeRegistry();
    }

    public SimulationParameters getParameters(NodeType type) {
        return registry.getOrDefault(type, SimulationParameters.empty());
    }

    private void initializeRegistry() {
        registry.put(NodeType.SERVICE, SimulationParameters.builder()
                .capacity(Map.of("requestsPerSecond", 1000.0, "cpuCores", 2.0, "memoryMb", 512.0))
                .latency(Map.of("p50Ms", 20.0, "p95Ms", 100.0, "p99Ms", 200.0))
                .throughput(Map.of("requestsPerSecond", 1000.0))
                .properties(Map.of("errorRate", 0.001, "autoScaling", true, "minReplicas", 1, "maxReplicas", 10))
                .failureModes(List.of("fail-stop", "fail-slow", "resource-exhaustion"))
                .recoveryTimeMs(30000)
                .build());

        registry.put(NodeType.CACHE, SimulationParameters.builder()
                .capacity(Map.of("entries", 10000.0, "memoryMb", 256.0))
                .latency(Map.of("p50Ms", 1.0, "p95Ms", 5.0, "p99Ms", 10.0))
                .throughput(Map.of("operationsPerSecond", 50000.0))
                .properties(Map.of("hitRate", 0.8, "evictionPolicy", "LRU", "ttlSeconds", 300))
                .failureModes(List.of("cache-miss-storm", "eviction-cascade", "memory-pressure"))
                .recoveryTimeMs(5000)
                .build());

        registry.put(NodeType.DATABASE, SimulationParameters.builder()
                .capacity(Map.of("connections", 100.0, "storageGb", 100.0, "iops", 3000.0))
                .latency(Map.of("readP50Ms", 5.0, "readP95Ms", 20.0, "writeP50Ms", 10.0, "writeP95Ms", 50.0))
                .throughput(Map.of("queriesPerSecond", 5000.0, "transactionsPerSecond", 1000.0))
                .properties(Map.of("replicationFactor", 1, "backupEnabled", true, "encryptionAtRest", true))
                .failureModes(List.of("connection-pool-exhaustion", "disk-full", "replication-lag", "deadlock"))
                .recoveryTimeMs(60000)
                .build());

        registry.put(NodeType.QUEUE, SimulationParameters.builder()
                .capacity(Map.of("partitions", 3.0, "retentionMs", 604800000.0, "messageSizeKb", 16.0))
                .latency(Map.of("p50Ms", 5.0, "p95Ms", 50.0, "p99Ms", 200.0))
                .throughput(Map.of("messagesPerSecond", 10000.0))
                .properties(Map.of("compression", "snappy", "minInSyncReplicas", 2, "cleanupPolicy", "delete"))
                .failureModes(List.of("partition-leader-unavailable", "consumer-lag", "disk-full", "broker-down"))
                .recoveryTimeMs(45000)
                .build());

        registry.put(NodeType.LOAD_BALANCER, SimulationParameters.builder()
                .capacity(Map.of("connections", 10000.0, "requestsPerSecond", 50000.0))
                .latency(Map.of("p50Ms", 1.0, "p95Ms", 5.0, "p99Ms", 15.0))
                .throughput(Map.of("requestsPerSecond", 50000.0))
                .properties(Map.of("algorithm", "round_robin", "healthCheckIntervalSeconds", 30, "sslTermination", true))
                .failureModes(List.of("health-check-failure", "backend-unavailable", "ssl-handshake-failure"))
                .recoveryTimeMs(10000)
                .build());

        registry.put(NodeType.CDN, SimulationParameters.builder()
                .capacity(Map.of("bandwidthGbps", 10.0, "cacheSizeGb", 500.0))
                .latency(Map.of("p50Ms", 10.0, "p95Ms", 50.0, "p99Ms", 100.0))
                .throughput(Map.of("requestsPerSecond", 100000.0))
                .properties(Map.of("cacheTtlSeconds", 3600, "regions", List.of("us-east-1", "us-west-2", "eu-west-1")))
                .failureModes(List.of("origin-unreachable", "cache-purge-failure", "ssl-certificate-expired"))
                .recoveryTimeMs(15000)
                .build());

        registry.put(NodeType.WORKER, SimulationParameters.builder()
                .capacity(Map.of("concurrency", 10.0, "queueSize", 1000.0, "memoryMb", 256.0))
                .latency(Map.of("processingTimeMs", 100.0, "p95Ms", 500.0, "p99Ms", 1000.0))
                .throughput(Map.of("tasksPerSecond", 100.0))
                .properties(Map.of("retryCount", 3, "deadLetterQueue", true, "gracefulShutdownMs", 30000))
                .failureModes(List.of("task-timeout", "queue-full", "processing-error", "worker-crash"))
                .recoveryTimeMs(20000)
                .build());

        registry.put(NodeType.EXTERNAL, SimulationParameters.builder()
                .capacity(Map.of("requestsPerSecond", 100.0))
                .latency(Map.of("p50Ms", 100.0, "p95Ms", 500.0, "p99Ms", 2000.0))
                .throughput(Map.of("requestsPerSecond", 100.0))
                .properties(Map.of("timeoutMs", 5000, "retryCount", 3, "circuitBreakerEnabled", true))
                .failureModes(List.of("timeout", "rate-limited", "service-unavailable", "dns-failure"))
                .recoveryTimeMs(60000)
                .build());

        registry.put(NodeType.GATEWAY, SimulationParameters.builder()
                .capacity(Map.of("requestsPerSecond", 10000.0, "connections", 5000.0))
                .latency(Map.of("p50Ms", 5.0, "p95Ms", 50.0, "p99Ms", 200.0))
                .throughput(Map.of("requestsPerSecond", 10000.0))
                .properties(Map.of("rateLimit", 10000, "timeoutMs", 30000, "authentication", true))
                .failureModes(List.of("rate-limit-exceeded", "authentication-failure", "backend-timeout"))
                .recoveryTimeMs(15000)
                .build());

        registry.put(NodeType.AUTH, SimulationParameters.builder()
                .capacity(Map.of("requestsPerSecond", 1000.0, "sessions", 10000.0))
                .latency(Map.of("p50Ms", 10.0, "p95Ms", 100.0, "p99Ms", 500.0))
                .throughput(Map.of("requestsPerSecond", 1000.0))
                .properties(Map.of("tokenTtlSeconds", 3600, "provider", "oauth2", "mfaEnabled", false))
                .failureModes(List.of("token-expired", "provider-unavailable", "brute-force-attack"))
                .recoveryTimeMs(30000)
                .build());

        registry.put(NodeType.MONITORING, SimulationParameters.builder()
                .capacity(Map.of("metricsPerSecond", 1000.0, "retentionDays", 30.0))
                .latency(Map.of("p50Ms", 50.0, "p95Ms", 200.0, "p99Ms", 1000.0))
                .throughput(Map.of("metricsPerSecond", 1000.0))
                .properties(Map.of("scrapeIntervalSeconds", 15, "alertingEnabled", true, "dashboardEnabled", true))
                .failureModes(List.of("metrics-pipeline-down", "storage-full", "alert-fatigue"))
                .recoveryTimeMs(30000)
                .build());

        registry.put(NodeType.FUNCTION, SimulationParameters.builder()
                .capacity(Map.of("concurrency", 100.0, "memoryMb", 128.0, "invocationsPerSecond", 1000.0))
                .latency(Map.of("p50Ms", 50.0, "p95Ms", 500.0, "p99Ms", 2000.0))
                .throughput(Map.of("invocationsPerSecond", 1000.0))
                .properties(Map.of("timeoutMs", 30000, "runtime", "java21", "coldStartMs", 500))
                .failureModes(List.of("timeout", "cold-start-latency", "memory-limit", "concurrency-limit"))
                .recoveryTimeMs(10000)
                .build());

        registry.put(NodeType.STORAGE, SimulationParameters.builder()
                .capacity(Map.of("capacityGb", 1000.0, "throughputMbps", 100.0, "iops", 10000.0))
                .latency(Map.of("p50Ms", 5.0, "p95Ms", 20.0, "p99Ms", 100.0))
                .throughput(Map.of("throughputMbps", 100.0))
                .properties(Map.of("durability", 99.999999999, "replicationFactor", 3, "encryptionAtRest", true))
                .failureModes(List.of("disk-full", "replication-lag", "corruption", "availability-zone-outage"))
                .recoveryTimeMs(120000)
                .build());
    }
}
