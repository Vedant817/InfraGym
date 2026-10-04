package com.archforge.simulation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Phase lookup and interpolation over simulated time.
 *
 * <p>Phases are what make a scenario mean something: "a flash sale" is not "high
 * throughput", it is a short extreme burst followed by collapse, and the difference is
 * visible in the queue-depth trace. The previous generator produced one flat number per
 * scenario, which made the shape unrepresentable.
 */
class WorkloadProfileTest {

    private static WorkloadProfile twoRamps() {
        return new WorkloadProfile(List.of(
                new WorkloadProfile.Phase("baseline", 10, 1.0, 1.0, false),
                new WorkloadProfile.Phase("spike", 10, 5.0, 1.0, true)), 20);
    }

    @Test
    @DisplayName("a flat phase holds its rate for the whole phase")
    void flatPhaseHolds() {
        WorkloadProfile profile = twoRamps();

        assertThat(profile.multiplierAt(0)).isEqualTo(1.0);
        assertThat(profile.multiplierAt(5)).isEqualTo(1.0);
        assertThat(profile.multiplierAt(9.99)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a ramp interpolates linearly, so a burst has a leading edge")
    void rampInterpolates() {
        WorkloadProfile profile = twoRamps();

        // The spike runs from 1x at second 10 to 5x at second 20.
        assertThat(profile.multiplierAt(10)).isEqualTo(1.0);
        assertThat(profile.multiplierAt(12.5)).isEqualTo(2.0);
        assertThat(profile.multiplierAt(15)).isEqualTo(3.0);
        assertThat(profile.multiplierAt(17.5)).isEqualTo(4.0);
    }

    @Test
    @DisplayName("a ramp can also fall, for traffic collapse")
    void rampFalls() {
        // The collapse runs from second 10 to second 20, falling 1.0 -> 0.1.
        WorkloadProfile profile = new WorkloadProfile(List.of(
                new WorkloadProfile.Phase("baseline", 10, 1.0, 1.0, false),
                new WorkloadProfile.Phase("collapse", 10, 0.1, 1.0, true)), 20);

        assertThat(profile.multiplierAt(10)).isEqualTo(1.0);
        // Quarter of the way through a 10-second collapse: 1.0 + (0.1 - 1.0) * 0.25.
        assertThat(profile.multiplierAt(12.5)).isCloseTo(0.775, offset(1e-9));
        assertThat(profile.multiplierAt(15)).isCloseTo(0.55, offset(1e-9));
        assertThat(profile.multiplierAt(20)).isCloseTo(0.1, offset(1e-9));
    }

    @Test
    @DisplayName("the phase name identifies where a regression belongs")
    void phaseNamesTheWindow() {
        WorkloadProfile profile = twoRamps();

        assertThat(profile.phaseAt(0)).isEqualTo("baseline");
        assertThat(profile.phaseAt(9.9)).isEqualTo("baseline");
        assertThat(profile.phaseAt(10)).isEqualTo("spike");
        assertThat(profile.phaseAt(19.9)).isEqualTo("spike");
    }

    @Test
    @DisplayName("past the end of the profile the last rate is held")
    void holdsTheLastRate() {
        WorkloadProfile profile = twoRamps();

        // A run longer than its profile must not fall to zero: that reads as "the
        // design served nothing at the end of the run", which is a bug report nobody
        // can act on.
        assertThat(profile.multiplierAt(25)).isEqualTo(5.0);
        assertThat(profile.multiplierAt(1000)).isEqualTo(5.0);
        assertThat(profile.phaseAt(1000)).isEqualTo("spike");
    }

    @Test
    @DisplayName("offered load scales the profile's multiplier by the nominal rate")
    void offeredLoadScales() {
        WorkloadProfile profile = twoRamps();

        assertThat(profile.offeredRpsAt(100, 0)).isEqualTo(100);
        assertThat(profile.offeredRpsAt(100, 15)).isEqualTo(300);
        assertThat(profile.offeredRpsAt(10_000, 15)).isEqualTo(30_000);
    }

    @Test
    @DisplayName("a phase boundary is exclusive of the next phase")
    void boundariesDoNotOverlap() {
        WorkloadProfile profile = twoRamps();

        // Second 10 belongs to the spike, not the baseline. Overlapping windows would
        // make a spike's samples appear in both phases.
        assertThat(profile.phaseAt(10)).isEqualTo("spike");
        assertThat(profile.multiplierAt(10)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("total duration is the sum of the phases")
    void totalDuration() {
        assertThat(twoRamps().totalDurationSeconds()).isEqualTo(20);
    }

    @Test
    @DisplayName("a negative elapsed time is treated as the start")
    void negativeTimeIsTheStart() {
        assertThat(twoRamps().multiplierAt(-5)).isEqualTo(1.0);
        assertThat(twoRamps().phaseAt(-5)).isEqualTo("baseline");
    }

    @Test
    @DisplayName("an empty profile is rejected rather than silently serving nothing")
    void emptyProfileRejected() {
        assertThatThrownBy(() -> new WorkloadProfile(List.of(), 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one phase");
    }

    @Test
    @DisplayName("a null phase list is rejected, not treated as empty")
    void nullProfileRejected() {
        assertThatThrownBy(() -> new WorkloadProfile(null, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a zero or negative phase duration is rejected")
    void invalidPhaseDurationRejected() {
        assertThatThrownBy(() -> new WorkloadProfile.Phase("zero", 0, 1.0, 1.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive duration");
    }

    @Test
    @DisplayName("a negative start multiplier is rejected")
    void negativeStartMultiplierRejected() {
        assertThatThrownBy(() -> new WorkloadProfile.Phase("bad", 10, 1.0, -1.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative start multiplier");
    }

    @Test
    @DisplayName("a negative target multiplier is rejected")
    void negativeTargetMultiplierRejected() {
        assertThatThrownBy(() -> new WorkloadProfile.Phase("bad", 10, -1.0, 0.0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative target multiplier");
    }

    /**
     * Regression test for the bug that made every reported number out by a factor of a
     * thousand. A profile level is a <em>multiplier</em> of the graph's capacity, not an
     * absolute rate; the shapes used to bake nominal in and {@code offeredRpsAt} then
     * multiplied by nominal again.
     */
    @Test
    @DisplayName("levels are multipliers of nominal, so they are not squared")
    void levelsAreMultipliersNotRates() {
        WorkloadProfile profile = new WorkloadProfile(List.of(
                new WorkloadProfile.Phase("steady", 10, 2.0, 2.0, false)), 10);

        assertThat(profile.offeredRpsAt(1000, 5)).isEqualTo(2000);
        assertThat(profile.peakMultiplier()).isEqualTo(2.0);
        assertThat(profile.peakRps(1000)).isEqualTo(2000);

        // Scaling the design scales the load proportionally, and nothing else.
        assertThat(profile.offeredRpsAt(10, 5)).isEqualTo(20);
        assertThat(profile.offeredRpsAt(50_000, 5)).isEqualTo(100_000);
    }

    @Test
    @DisplayName("the peak is the higher of a phase's two levels, so a ramp counts")
    void peakIsTheHigherLevel() {
        // A recovery phase starts at 10x and ends at 1x. Its peak is the 10x it came
        // from, so the scenario's headline is not understated.
        WorkloadProfile profile = new WorkloadProfile(List.of(
                new WorkloadProfile.Phase("spike", 10, 5.0, 1.0, true),
                new WorkloadProfile.Phase("recovery", 10, 1.0, 5.0, true)), 20);

        assertThat(profile.peakMultiplier()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("phases are immutable once constructed")
    void phasesAreImmutable() {
        java.util.List<WorkloadProfile.Phase> mutable = new java.util.ArrayList<>();
        mutable.add(new WorkloadProfile.Phase("a", 10, 1.0, 1.0, false));
        WorkloadProfile profile = new WorkloadProfile(mutable, 10);

        mutable.add(new WorkloadProfile.Phase("b", 10, 1.0, 1.0, false));

        assertThat(profile.phases()).hasSize(1);
        assertThatThrownBy(() -> profile.phases().add(
                new WorkloadProfile.Phase("c", 1, 1.0, 1.0, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
