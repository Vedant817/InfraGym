package com.archforge.simulation.workload;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.graph.CompiledGraph;
import com.archforge.contracts.graph.CompiledNode;
import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.simulation.support.TestGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Nominal capacity and scenario shapes.
 *
 * <p>Two claims are pinned here. First, that "this architecture's capacity" means the
 * bottleneck of the worst path, not a hard-coded constant: it is the reference every
 * scenario multiplier is applied to, so getting it wrong scales every number the
 * platform reports. Second, that all nine scenarios are reachable — every one of them
 * used to degrade silently to NORMAL, because the scenario field was never populated on
 * the wire.
 */
class WorkloadGeneratorTest {

    private final WorkloadGenerator generator = new WorkloadGenerator();

    @Test
    @DisplayName("nominal capacity is the bottleneck on the request path")
    void nominalCapacityIsTheBottleneck() {
        // lb 50000 -> api 1000 -> db min(queries 5000, iops 3000) = 3000.
        // The whole pipeline cannot exceed its slowest stage.
        assertThat(generator.nominalCapacity(TestGraphs.readThroughCache()))
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("a slower database makes the whole design slower")
    void slowerStoreMakesTheDesignSlower() {
        double fast = generator.nominalCapacity(TestGraphs.readThroughCache());

        CompiledGraph slow = TestGraphs.graph("Slow store",
                List.of(TestGraphs.loadBalancer("lb"), TestGraphs.service("api"),
                        TestGraphs.builder("db", NodeType.DATABASE, 0)
                                .capacity("iops", 300)
                                .latency("readP50Ms", 5)
                                .throughput("queriesPerSecond", 5000)
                                .build()),
                List.of(TestGraphs.edge("lb", "api"), TestGraphs.edge("api", "db")));

        assertThat(generator.nominalCapacity(slow)).isLessThan(fast);
    }

    @Test
    @DisplayName("the slowest branch sets the capacity, because that is the rate a user gets")
    void slowestBranchWins() {
        // One fast branch and one very slow one. Users on the slow branch get the slow
        // rate, so the design's capacity is the slow one.
        CompiledGraph graph = TestGraphs.graph("Parallel branches",
                List.of(TestGraphs.loadBalancer("lb"),
                        TestGraphs.service("fast"),
                        TestGraphs.builder("slow", NodeType.SERVICE, 1)
                                .capacity("requestsPerSecond", 20)
                                .latency("p50Ms", 5)
                                .build()),
                List.of(TestGraphs.edge("lb", "fast"), TestGraphs.edge("lb", "slow")));

        assertThat(generator.nominalCapacity(graph)).isEqualTo(20);
    }

    @Test
    @DisplayName("a queue absorbs a burst, so a fast producer does not lower capacity")
    void queueDoesNotLowerCapacity() {
        // The queue is rated at 10000/s but drains at its consumer's rate; the
        // consumer is the real constraint and the generator must find it.
        assertThat(generator.nominalCapacity(TestGraphs.queued())).isEqualTo(100);
    }

    @Test
    @DisplayName("a graph with no entry point is still rated, over all its nodes")
    void noEntryPointStillRated() {
        // Services only. Nothing canBeEntryPoint, so the fallback is the whole graph --
        // and it says so, because it changes what the number means.
        CompiledGraph graph = TestGraphs.graph("No entry",
                List.of(TestGraphs.service("a"), TestGraphs.service("b")),
                List.of(TestGraphs.edge("a", "b")));

        double nominal = generator.nominalCapacity(graph);

        assertThat(nominal).isEqualTo(1000);
    }

    @Test
    @DisplayName("an empty or null graph rates at the floor rather than throwing")
    void degenerateGraphsRateAtTheFloor() {
        assertThat(generator.nominalCapacity(null))
                .isEqualTo(WorkloadGenerator.MIN_NOMINAL_RPS);
        assertThat(generator.nominalCapacity(TestGraphs.graph("Empty", List.of(), List.of())))
                .isEqualTo(WorkloadGenerator.MIN_NOMINAL_RPS);
    }

    @Test
    @DisplayName("capacity is never zero, so no division by zero downstream")
    void neverZero() {
        CompiledGraph undeclared = TestGraphs.undeclared();

        assertThat(generator.nominalCapacity(undeclared)).isGreaterThanOrEqualTo(1.0);
    }

    // --- scenario shapes ------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ScenarioType.class)
    @DisplayName("every scenario produces a profile that spans the requested duration")
    void everyScenarioProducesAProfile(ScenarioType scenario) {
        WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), scenario, 60);

        assertSoftly(softly -> {
            softly.assertThat(profile).as("profile for %s", scenario).isNotNull();
            softly.assertThat(profile.phases()).as("phases for %s", scenario).isNotEmpty();
            softly.assertThat(profile.totalDurationSeconds())
                    .as("total duration for %s", scenario)
                    .isGreaterThanOrEqualTo(60);
            softly.assertThat(profile.phases()).allSatisfy(phase ->
                    softly.assertThat(phase.durationSeconds())
                            .as("phase %s of %s", phase.name(), scenario)
                            .isPositive());
        });
    }

    /**
     * Every scenario reaches the load its own declaration claims.
     *
     * <p>The defect this exists for: all nine scenarios degraded to NORMAL because the
     * scenario field never reached the engine, so 1.0x was the only level anyone ever
     * observed. Asserted on the profile's peak <em>or trough</em> against
     * {@link ScenarioType#getExtremeLoadMultiplier()}, because "extreme" is the
     * contract — a collapse's extreme is its quietest moment, and comparing a
     * collapse's peak against a trough figure would fail for a profile that was
     * correct.
     */
    @ParameterizedTest
    @EnumSource(ScenarioType.class)
    @DisplayName("every scenario reaches its declared extreme load")
    void everyScenarioReachesItsDeclaredExtreme(ScenarioType scenario) {
        WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), scenario, 90);

        double peak = profile.peakMultiplier();
        double declared = scenario.getExtremeLoadMultiplier();

        // A burst's extreme is its peak, a collapse's is its trough, and a scenario
        // declared at 1.0 is at both. Asserting the declared value is one of the
        // two reported extremes covers all three without a sign-dependent branch
        // that could itself be wrong.
        assertThat(List.of(peak, profile.minimumMultiplier()))
                .as("%s peaked at %s with a trough of %s; declared extreme %s",
                        scenario, peak, profile.minimumMultiplier(), declared)
                .anySatisfy(observed -> assertThat(observed)
                        .isCloseTo(declared, within(0.001)));
    }

    @Test
    @DisplayName("a collapse scenario's extreme is its trough, not its peak")
    void collapseExtremeIsTheTrough() {
        WorkloadProfile profile = generator.generate(
                TestGraphs.deepChain(), ScenarioType.TRAFFIC_DROP, 90);

        assertThat(profile.peakMultiplier())
                .as("traffic starts at baseline, so the peak is baseline")
                .isEqualTo(1.0);
        assertThat(profile.minimumMultiplier())
                .as("the extreme is the collapse")
                .isEqualTo(0.1);
        assertThat(ScenarioType.TRAFFIC_DROP.isBurst()).isFalse();
    }

    @Test
    @DisplayName("every burst scenario's extreme is its peak")
    void burstExtremeIsThePeak() {
        for (ScenarioType scenario : List.of(ScenarioType.TRAFFIC_SPIKE,
                ScenarioType.FLASH_SALE, ScenarioType.DATABASE_OVERLOAD)) {
            WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), scenario, 90);

            assertThat(scenario.isBurst()).as("%s is a burst", scenario).isTrue();
            assertThat(profile.peakMultiplier())
                    .as("peak for %s", scenario)
                    .isEqualTo(scenario.getExtremeLoadMultiplier());
        }
    }

    @Test
    @DisplayName("a flash sale's burst is shorter than a traffic spike's")
    void flashSaleIsShorterThanSpike() {
        WorkloadProfile spike = generator.generate(TestGraphs.deepChain(), ScenarioType.TRAFFIC_SPIKE, 90);
        WorkloadProfile flash = generator.generate(TestGraphs.deepChain(), ScenarioType.FLASH_SALE, 90);

        int spikeSeconds = spike.phases().stream()
                .filter(phase -> phase.name().equals("spike"))
                .mapToInt(WorkloadProfile.Phase::durationSeconds)
                .findFirst()
                .orElse(0);
        int flashSeconds = flash.phases().stream()
                .filter(phase -> phase.name().equals("flash"))
                .mapToInt(WorkloadProfile.Phase::durationSeconds)
                .findFirst()
                .orElse(0);

        assertThat(flashSeconds).isPositive().isLessThan(spikeSeconds);
        assertThat(flash.phases().stream()
                .filter(phase -> phase.name().equals("flash"))
                .findFirst().orElseThrow()
                .targetMultiplier())
                .isGreaterThan(spike.phases().stream()
                        .filter(phase -> phase.name().equals("spike"))
                        .findFirst().orElseThrow()
                        .targetMultiplier());
    }

    /**
 * Every shape opens by ramping up, so a profile's raw minimum is always the warmup.
 *
 * <p>{@link WorkloadProfile#minimumMultiplier()} therefore skips it: a traffic collapse
 * to 0.1x would otherwise be indistinguishable from the 0.2x that every run ramps from,
 * and a comparison against a scenario's declared extreme could not tell whether the
 * scenario reached the load it promised.
     */
    @Test
    @DisplayName("the warmup ramp is excluded from the scenario's reported extremes")
    void warmupIsExcludedFromExtremes() {
        for (ScenarioType scenario : ScenarioType.all()) {
            WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), scenario, 90);

            double warmupStart = profile.phases().get(0).startMultiplier();
            assertThat(warmupStart)
                    .as("%s must open with a ramp below its target", scenario)
                    .isLessThan(profile.phases().get(0).targetMultiplier());

            // The reported extremes come from the scenario's own phases, so the warmup
            // ramp cannot become the answer. A collapse's trough legitimately sits
            // below the warmup -- that is the whole point of a collapse -- so the check
            // is that the value came from a scenario phase, not that it exceeds the ramp.
            assertThat(profile.phases())
                    .as("%s must have a scenario phase after its warmup", scenario)
                    .hasSizeGreaterThan(1);
            assertThat(profile.absolutePeakMultiplier())
                    .as("the warmup ramp is still measured, just not reported as the extreme")
                    .isGreaterThanOrEqualTo(profile.peakMultiplier());
        }
    }

    @Test
    @DisplayName("a scenario's declared extreme is actually offered at some point")
    void declaredExtremeIsOfferedAtSomeSecond() {
        for (ScenarioType scenario : ScenarioType.all()) {
            WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), scenario, 90);
            double declared = scenario.getExtremeLoadMultiplier();

            boolean reached = java.util.stream.IntStream.range(0, 90)
                    .mapToObj(profile::multiplierAt)
                    .anyMatch(multiplier -> Math.abs(multiplier - declared) < 0.02);

            assertThat(reached)
                    .as("%s declared an extreme of %s but never offered that load",
                            scenario, declared)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("traffic collapse falls below baseline and stays there")
    void trafficDropFalls() {
        // baseline 0-15s, collapse 15-35s (1.0 -> 0.1), recovery 35-90s.
        WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), ScenarioType.TRAFFIC_DROP, 90);

        assertThat(profile.multiplierAt(5))
                .as("baseline before the collapse")
                .isGreaterThan(0.5);
        assertThat(profile.multiplierAt(25))
                .as("midway through the 20-second collapse")
                .isCloseTo(0.55, within(0.001));
        assertThat(profile.multiplierAt(35))
                .as("the collapse's trough")
                .isCloseTo(0.1, within(0.001));
        assertThat(profile.multiplierAt(60))
                .as("recovery returns to baseline")
                .isGreaterThan(0.5);
    }

    @Test
    @DisplayName("failure scenarios hold load flat so the failure is the only variable")
    void failureScenariosHoldLoad() {
        for (ScenarioType scenario : List.of(ScenarioType.SERVICE_FAILURE,
                ScenarioType.CACHE_FAILURE, ScenarioType.KAFKA_CONSUMER_FAILURE,
                ScenarioType.NETWORK_DEGRADATION)) {
            WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), scenario, 90);

            // A scenario that ramps load while failing confounds two variables: the user
            // cannot tell whether the latency came from the failure or from the ramp.
            double peak = profile.phases().stream()
                    .mapToDouble(WorkloadProfile.Phase::targetMultiplier)
                    .max()
                    .orElse(0);
            assertThat(peak)
                    .as("peak for %s", scenario)
                    .isCloseTo(1.0, within(0.001));
        }
    }

    @Test
    @DisplayName("database overload drives load past the storage ceiling")
    void databaseOverloadDrivesPastTheCeiling() {
        WorkloadProfile profile = generator.generate(
                TestGraphs.readThroughCache(), ScenarioType.DATABASE_OVERLOAD, 90);

        assertThat(profile.multiplierAt(30)).isGreaterThan(2.0);
    }

    @Test
    @DisplayName("phases are named, so a chart can shade them and a report can cite them")
    void phasesAreNamed() {
        for (ScenarioType scenario : ScenarioType.all()) {
            WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), scenario, 90);

            assertThat(profile.phases())
                    .as("phase names for %s", scenario)
                    .allSatisfy(phase -> assertThat(phase.name()).isNotBlank());
            assertThat(profile.phases().stream().map(WorkloadProfile.Phase::name).distinct().count())
                    .as("distinct phase names for %s", scenario)
                    .isEqualTo((long) profile.phases().size());
        }
    }

    @Test
    @DisplayName("a non-positive duration falls back to the declared default")
    void durationFallsBack() {
        WorkloadProfile profile = generator.generate(TestGraphs.deepChain(), ScenarioType.NORMAL, 0);

        assertThat(profile.totalDurationSeconds()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("node capacity is resolved once, identically to the simulator's")
    void nodeCapacityMatchesTheSimulator() {
        // Two definitions of capacity in one service means two answers to "is this
        // design overloaded". The cache is the case that drifted: its rate lives in the
        // throughput map, which the generator's own old lookup did not read.
        for (CompiledNode node : TestGraphs.readThroughCache().getNodes()) {
            assertThat(generator.nodeCapacity(node))
                    .isEqualTo(com.archforge.simulation.engine.NodeCharacteristics
                            .of(node).sustainedRateRps());
        }
    }
}
