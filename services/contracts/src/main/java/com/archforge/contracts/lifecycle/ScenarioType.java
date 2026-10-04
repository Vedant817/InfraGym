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
 *   <li>{@code extremeLoadMultiplier} — the scenario's most extreme offered load
 *       relative to the graph's own capacity, so a scenario is meaningful for a
 *       50 rps design and a 500 000 rps design alike. Above 1 for a burst, below 1
 *       for a collapse; see {@link #getExtremeLoadMultiplier()};</li>
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
    private final double extremeLoadMultiplier;
    private final double failureFraction;
    private final FailureTarget failureTarget;
    private final String description;

    ScenarioType(String value, String displayName, double extremeLoadMultiplier,
                 double failureFraction, FailureTarget failureTarget, String description) {
        this.value = value;
        this.displayName = displayName;
        this.extremeLoadMultiplier = extremeLoadMultiplier;
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

    /**
     * The scenario's most extreme offered load, as a multiple of the graph's nominal
     * capacity.
     *
     * <p><b>Extreme, not peak.</b> For a burst scenario this is the peak: a 5x spike
     * reaches 5.0. For a collapse it is the trough: traffic falling to a tenth of
     * baseline is a departure of 0.1, and describing that as a "peak" would make a
     * scenario's most extreme moment its quietest. The evaluator reads this to say
     * "this run drove the architecture to N times its sustainable rate", so it has to
     * mean the same thing whichever direction the scenario pushes.
     *
     * <p>Previously named and documented as a peak, which is why every workload shape
     * had to be bent to fit it: a collapse profile starting at 1.0x and falling to 0.1x
     * has a true peak of 1.0x and could never satisfy a declared peak of 0.1x. The
     * generator's profiles were the casualty of the name, not the reverse.
     */
    public double getExtremeLoadMultiplier() {
        return extremeLoadMultiplier;
    }

    /**
     * Whether the extreme is a burst above baseline rather than a collapse below it.
     *
     * <p>So a caller saying "drive it to N times capacity" and one saying "drop it to N
     * times capacity" can share one code path without branching on the sign.
     */
    public boolean isBurst() {
        return extremeLoadMultiplier > 1.0;
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
