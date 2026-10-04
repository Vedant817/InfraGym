package com.archforge.contracts.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A workload + failure scenario the simulation engine can execute.
 *
 * <p>Each constant declares, declaratively:
 * <ul>
 *   <li>{@code baselineMultiplier} — peak offered load relative to the graph's
 *       own capacity, so a scenario is meaningful for a 50 rps design and a
 *       500 000 rps design alike;</li>
 *   <li>{@code targets} — which node categories the failure plan aims at, so a
 *       scenario degrades gracefully when the graph contains no such node;</li>
 *   <li>{@code description} — surfaced verbatim in the UI and fed to the AI
 *       evaluator, which is why it lives here rather than in a UI string table.</li>
 * </ul>
 *
 * <p>Adding a scenario is additive: the frontend renders the enum, the simulator
 * reads the multipliers, the evaluator reads the description. No other change
 * needed, which is the whole point of declaring the data next to the constant.
 */
public enum ScenarioType {

    NORMAL("normal", "Steady state", 1.0, 0.0, FailureTarget.NONE,
            "Constant baseline traffic at the architecture's nominal capacity."),

    TRAFFIC_SPIKE("traffic_spike", "Traffic spike", 5.0, 0.0, FailureTarget.NONE,
            "A sudden 5x burst of traffic followed by partial recovery, to expose "
                    + "components that cannot absorb a step change in load."),

    FLASH_SALE("flash_sale", "Flash sale", 10.0, 0.0, FailureTarget.NONE,
            "A 10x burst concentrated into a short window, the classic "
                    + "traffic-shaping failure case for synchronous database writes."),

    TRAFFIC_DROP("traffic_drop", "Traffic collapse", 0.1, 0.0, FailureTarget.NONE,
            "Traffic falls to 10% of baseline, to reveal components that fail when idle: "
                    + "connection pools, cold caches and scaled-to-zero capacity."),

    SERVICE_FAILURE("service_failure", "Service outage", 1.0, 0.15, FailureTarget.COMPUTE,
            "A compute node becomes fail-stop mid-run. Reveals whether callers retry, "
                    + "shed load, or silently lose requests."),

    DATABASE_OVERLOAD("database_overload", "Database saturation", 3.0, 0.25, FailureTarget.STORAGE,
            "Storage is driven past its IOPS ceiling. Reveals missing read replicas, "
                    + "caching or write batching."),

    CACHE_FAILURE("cache_failure", "Cache outage", 1.0, 0.20, FailureTarget.CACHE,
            "A cache stops serving, forcing every read through to the backing store. "
                    + "Reveals cache-stampedes on the origin."),

    KAFKA_CONSUMER_FAILURE("kafka_consumer_failure", "Consumer lag", 1.0, 0.20, FailureTarget.MESSAGING,
            "Queue consumers stall, so backlog grows without bound. Reveals whether "
                    + "the design tolerates unbounded lag."),

    NETWORK_DEGRADATION("network_degradation", "Network partition", 1.0, 0.20, FailureTarget.NETWORK,
            "Links between components degrade, adding latency and jitter. Reveals "
                    + "tight timeout coupling and retry storms."),

    REGION_FAILURE("region_failure", "Availability-zone outage", 1.0, 0.33, FailureTarget.STORAGE,
            "A third of capacity disappears at once. Reveals single points of failure "
                    + "that incremental load testing never surfaces.");

    /** Which components a scenario's failure plan aims at. */
    public enum FailureTarget {
        NONE, COMPUTE, STORAGE, CACHE, MESSAGING, NETWORK, ALL
    }

    private final String value;
    private final String displayName;
    private final double baselineMultiplier;
    private final double failureFraction;
    private final FailureTarget failureTarget;
    private final String description;

    ScenarioType(String value, String displayName, double baselineMultiplier,
                 double failureFraction, FailureTarget failureTarget, String description) {
        this.value = value;
        this.displayName = displayName;
        this.baselineMultiplier = baselineMultiplier;
        this.failureFraction = failureFraction;
        this.failureTarget = failureTarget;
        this.description = description;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Peak offered load as a multiple of the graph's nominal capacity. */
    public double getBaselineMultiplier() {
        return baselineMultiplier;
    }

    /** Fraction of matching nodes the failure plan takes down, in [0, 1]. */
    public double getFailureFraction() {
        return failureFraction;
    }

    public FailureTarget getFailureTarget() {
        return failureTarget;
    }

    /** Prose description handed to the AI evaluator as scenario context. */
    public String getDescription() {
        return description;
    }

    public boolean injectsFailures() {
        return failureTarget != FailureTarget.NONE && failureFraction > 0;
    }

    @JsonCreator
    public static ScenarioType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        String needle = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(s -> s.value.equals(needle) || s.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown scenario '" + value + "'. Valid scenarios: " + String.join(", ", allValues())));
    }

    public static ScenarioType findByValue(String value) {
        try {
            return fromValue(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static List<ScenarioType> all() {
        return List.of(values());
    }

    public static List<String> allValues() {
        return Arrays.stream(values()).map(ScenarioType::getValue).toList();
    }
}
