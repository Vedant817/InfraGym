package com.archforge.telemetry.aggregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Window arithmetic on ticks.
 *
 * <p>Every test here pins a rule the aggregator's whole shape depends on. The arithmetic is
 * trivial enough to get right by inspection and easy to get subtly wrong, and the failure
 * is not an exception: a window boundary one tick out merges or splits series in a way no
 * downstream consumer can detect.
 */
class TickWindowTest {

    @Test
    @DisplayName("a tick belongs to the window its index names")
    void tickBelongsToItsWindow() {
        assertThat(TickWindow.indexOf(0, 10)).isZero();
        assertThat(TickWindow.indexOf(9, 10)).isZero();
        assertThat(TickWindow.indexOf(10, 10)).isEqualTo(1);
        assertThat(TickWindow.indexOf(19, 10)).isEqualTo(1);
        assertThat(TickWindow.indexOf(20, 10)).isEqualTo(2);
    }

    @Test
    @DisplayName("a window's start is the tick that opens it")
    void startOf() {
        assertThat(TickWindow.startOf(0, 10)).isZero();
        assertThat(TickWindow.startOf(3, 10)).isEqualTo(30);
    }

    @Test
    @DisplayName("a window's end is one tick past its last, so windows tile without gaps")
    void endOf() {
        assertThat(TickWindow.endOf(0, 10)).isEqualTo(10);
        assertThat(TickWindow.endOf(3, 10)).isEqualTo(40);

        // The property that matters: consecutive windows share a boundary exactly. An
        // off-by-one here drops or double-counts the sample on the boundary, and the
        // affected tick is the one where a component crosses its saturation threshold --
        // the tick a reader most wants to see.
        for (long index = 0; index < 20; index++) {
            assertThat(TickWindow.startOf(index + 1, 10))
                    .as("window %d abuts window %d", index + 1, index)
                    .isEqualTo(TickWindow.endOf(index, 10));
        }
    }

    @Test
    @DisplayName("every tick in [0, ticks) lands in exactly one window")
    void windowsTileTheRun() {
        // The conservation property. Counted rather than asserted per tick so a change in
        // the arithmetic that shifts a boundary shows up as a wrong count.
        long windowSize = 7;
        long ticks = 50;
        int[] coverage = new int[(int) ticks];
        for (long tick = 0; tick < ticks; tick++) {
            long index = TickWindow.indexOf(tick, windowSize);
            coverage[(int) index]++;
        }

        for (int window = 0; window < coverage.length; window++) {
            if (coverage[window] == 0) {
                continue;
            }
            long start = TickWindow.startOf(window, windowSize);
            long end = TickWindow.endOf(window, windowSize);
            long expected = Math.min(end, ticks) - start;
            assertThat(coverage[window])
                    .as("window %d covers [%d, %d)", window, start, end)
                    .isEqualTo((int) expected);
        }
    }

    @Test
    @DisplayName("window size 1 puts every tick in its own window")
    void unitWindows() {
        assertThat(TickWindow.indexOf(7, 1)).isEqualTo(7);
        assertThat(TickWindow.startOf(7, 1)).isEqualTo(7);
        assertThat(TickWindow.endOf(7, 1)).isEqualTo(8);
    }

    @Test
    @DisplayName("a window size of zero is refused rather than dividing by zero")
    void zeroWindowSizeRefused() {
        // Reachable from configuration. A zero would make indexOf return
        // Long.MIN_VALUE / 0 and every sample would claim to be in a window that does not
        // exist, so the run would publish nothing and say nothing about why.
        assertThatThrownBy(() -> TickWindow.indexOf(5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("a negative window size is refused too")
    void negativeWindowSizeRefused() {
        assertThatThrownBy(() -> TickWindow.indexOf(5, -10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a window's simulated start is derived from the tick, not the wall clock")
    void simulatedStart() {
        // Two runs of the same design must place the same tick at the same simulated time
        // however long each took in real life. A 60-tick run executes in ~350ms of wall
        // clock, so a wall-clock axis would show it as a spike and a 4-minute run as a
        // plateau for the same simulated second.
        assertThat(TickWindow.startMillisOf(0, 10, 1000L)).isZero();
        assertThat(TickWindow.startMillisOf(3, 10, 1000L)).isEqualTo(30_000L);
        assertThat(TickWindow.startMillisOf(3, 10, 100L)).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("a window count spans from one index to another inclusive")
    void countThrough() {
        assertThat(TickWindow.countThrough(0, 0)).isEqualTo(1);
        assertThat(TickWindow.countThrough(0, 5)).isEqualTo(6);
        assertThat(TickWindow.countThrough(3, 1)).as("an inverted range is empty, not negative")
                .isZero();
    }
}