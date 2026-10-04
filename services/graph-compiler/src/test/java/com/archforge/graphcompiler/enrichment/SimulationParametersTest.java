package com.archforge.graphcompiler.enrichment;

import com.archforge.contracts.canvas.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SimulationParameters} config merging.
 *
 * <p>These are the tests whose absence made the "tune your node" feature invisible.
 * The old {@code mergeWith} routed an override by substring-matching its key against
 * {@code "capacity"}, {@code "rps"}, {@code "iops"}, {@code "latency"}, {@code "ms"},
 * {@code "throughput"} and {@code "tps"}. Only those literal names matched; every
 * parameter the registry actually publishes — {@code requestsPerSecond},
 * {@code operationsPerSecond}, {@code connections}, {@code p99Ms}, {@code iops},
 * {@code concurrency} — was filed under {@code properties}, which nothing downstream
 * reads. Worse, {@code recoveryTimeMs} contains {@code "ms"} and so was filed as a
 * <em>latency</em>.
 */
class SimulationParametersTest {

    private final SimulationParameterRegistry registry = new SimulationParameterRegistry();

    @Test
    @DisplayName("a requestsPerSecond override reaches capacity, not properties")
    void routesRequestsPerSecondToCapacity() {
        SimulationParameters defaults = registry.getParameters(NodeType.SERVICE);

        SimulationParameters merged = defaults.mergeWith(Map.of("requestsPerSecond", 5000));

        assertEquals(5000.0, merged.getCapacity().get("requestsPerSecond"),
                "the override must land in capacity");
        assertTrue(!merged.getProperties().containsKey("requestsPerSecond"),
                "the override must not be filed under properties, which nothing reads");
    }

    @Test
    @DisplayName("a p99Ms override reaches latency, not capacity or properties")
    void routesP99ToLatency() {
        SimulationParameters defaults = registry.getParameters(NodeType.SERVICE);

        SimulationParameters merged = defaults.mergeWith(Map.of("p99Ms", 42));

        assertEquals(42.0, merged.getLatency().get("p99Ms"));
        assertTrue(!merged.getProperties().containsKey("p99Ms"));
    }

    @Test
    @DisplayName("a recoveryTimeMs override is not filed as a latency")
    void recoveryTimeIsNotALatency() {
        // Regression: the key contains "ms", so the old substring routing filed it under
        // latency, silently corrupting the node's latency profile.
        SimulationParameters defaults = registry.getParameters(NodeType.DATABASE);

        SimulationParameters merged = defaults.mergeWith(Map.of("recoveryTimeMs", 9000));

        assertTrue(!merged.getLatency().containsKey("recoveryTimeMs"),
                "recoveryTimeMs must not become a latency entry; got " + merged.getLatency());
        assertTrue(!merged.getCapacity().containsKey("recoveryTimeMs"));

        SimulationParameters withRecovery = merged.withRecoveryTimeMs(9000);
        assertEquals(9000.0, withRecovery.getRecoveryTimeMs(),
                "it must be assignable as the recovery time instead");
    }

    @Test
    @DisplayName("per-type parameter names all route to a known slot")
    void everyRegistryKeyIsRoutable() {
        // If the registry publishes a key the routing table does not know, a user
        // overriding it gets it silently dropped. This test fails when the two drift.
        for (NodeType type : NodeType.values()) {
            SimulationParameters params = registry.getParameters(type);

            assertTrue(params.unknownConfigKeys().isEmpty(),
                    type.getValue() + " publishes unroutable keys: " + params.unknownConfigKeys());
        }
    }

    @Test
    @DisplayName("an unrecognised key is preserved rather than discarded")
    void preservesUnknownKeys() {
        SimulationParameters defaults = registry.getParameters(NodeType.SERVICE);

        SimulationParameters merged = defaults.mergeWith(Map.of("someCustomThing", "value"));

        assertEquals("value", merged.getProperties().get("someCustomThing"),
                "a user-supplied value must never be silently lost");
    }

    @Test
    @DisplayName("does not mutate the registry defaults")
    void doesNotMutateDefaults() {
        SimulationParameters defaults = registry.getParameters(NodeType.SERVICE);
        double originalRps = defaults.getCapacity().get("requestsPerSecond");

        SimulationParameters merged = defaults.mergeWith(Map.of("requestsPerSecond", 9999));

        assertNotSame(defaults, merged);
        assertEquals(originalRps, defaults.getCapacity().get("requestsPerSecond"),
                "merging must not write through to the shared registry instance");
        assertEquals(9999.0, merged.getCapacity().get("requestsPerSecond"));
    }

    @Test
    @DisplayName("cache hitRate override reaches properties, not capacity")
    void routesCacheHitRate() {
        SimulationParameters defaults = registry.getParameters(NodeType.CACHE);

        SimulationParameters merged = defaults.mergeWith(Map.of("hitRate", 0.95));

        assertEquals(0.95, merged.getProperties().get("hitRate"));
        assertTrue(!merged.getCapacity().containsKey("hitRate"));
    }

    @Test
    @DisplayName("a non-numeric override is kept without crashing")
    void toleratesNonNumericOverrides() {
        SimulationParameters defaults = registry.getParameters(NodeType.SERVICE);

        SimulationParameters merged = defaults.mergeWith(
                Map.of("requestsPerSecond", "lots", "hitRate", true));

        assertEquals("lots", merged.getProperties().get("requestsPerSecond"),
                "an unparseable override is preserved, not thrown");
        assertTrue(merged.getCapacity().get("requestsPerSecond") > 0,
                "the registry default must survive an unusable override");
    }
}
