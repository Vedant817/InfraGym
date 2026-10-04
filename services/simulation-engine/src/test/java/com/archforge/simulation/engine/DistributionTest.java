package com.archforge.simulation.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Percentile and summary statistics over a sample set.
 *
 * <p>These are the numbers the whole product rests on. The README builds its worked
 * example around a P99 of 850 ms, and no percentile existed anywhere in the pipeline
 * before this class — the aggregator accumulated only min, max and average, so any
 * tail-latency claim was invented. Max is not a substitute: it is a different
 * statistic, and reporting it as a description of the tail makes a design with one
 * 3-second outlier look identical to one that is uniformly slow.
 */
class DistributionTest {

    @Nested
    @DisplayName("nearest-rank percentiles")
    class Percentiles {

        @Test
        @DisplayName("p50 of 1..100 is 50, p95 is 95, p99 is 99")
        void exactPercentiles() {
            Distribution distribution = Distribution.of(range(1, 100));

            assertThat(distribution.p50()).isEqualTo(50);
            assertThat(distribution.p95()).isEqualTo(95);
            assertThat(distribution.p99()).isEqualTo(99);
        }

        @Test
        @DisplayName("every percentile returns an observed value, never an interpolation")
        void returnsObservedValues() {
            Distribution distribution = Distribution.of(new double[]{10, 10, 10, 900});

            // With 4 samples, p50 is rank 2 and p95 is rank 4 -- both real observations.
            assertThat(distribution.p50()).isEqualTo(10);
            assertThat(distribution.p95()).isEqualTo(900);
        }

        @Test
        @DisplayName("input order does not matter")
        void sortsBeforeRanking() {
            double[] ascending = range(1, 50);

            Distribution ascendingDistribution = Distribution.of(ascending);
            Distribution descending = Distribution.of(reverse(ascending));

            assertThat(descending.p50()).isEqualTo(ascendingDistribution.p50());
            assertThat(descending.p99()).isEqualTo(ascendingDistribution.p99());
            assertThat(descending.min()).isEqualTo(ascendingDistribution.min());
            assertThat(descending.max()).isEqualTo(ascendingDistribution.max());
        }

        @Test
        @DisplayName("clamps out-of-range percentiles instead of throwing")
        void clampsPercentileRange() {
            Distribution distribution = Distribution.of(range(1, 100));

            assertThat(distribution.percentile(-50)).isEqualTo(1);
            assertThat(distribution.percentile(500)).isEqualTo(100);
        }

        @Test
        @DisplayName("a single sample is every percentile")
        void singleSample() {
            Distribution distribution = Distribution.of(new double[]{42});

            assertThat(distribution.p50()).isEqualTo(42);
            assertThat(distribution.p95()).isEqualTo(42);
            assertThat(distribution.p99()).isEqualTo(42);
            assertThat(distribution.max()).isEqualTo(42);
            assertThat(distribution.stdDev()).isEqualTo(0);
        }

        @Test
        @DisplayName("two samples: p50 lands on the lower one")
        void twoSamples() {
            Distribution distribution = Distribution.of(new double[]{5, 95});

            // ceil(0.50 * 2) = rank 1.
            assertThat(distribution.p50()).isEqualTo(5);
            assertThat(distribution.max()).isEqualTo(95);
        }
    }

    @Nested
    @DisplayName("summary statistics")
    class Summary {

        @Test
        @DisplayName("min, max, average and standard deviation")
        void basics() {
            Distribution distribution = Distribution.of(new double[]{2, 4, 4, 4, 5, 5, 7, 9});

            assertThat(distribution.count()).isEqualTo(8);
            assertThat(distribution.min()).isEqualTo(2);
            assertThat(distribution.max()).isEqualTo(9);
            assertThat(distribution.avg()).isEqualTo(5);
            // Population stddev of that classic sequence is 2.
            assertThat(distribution.stdDev()).isCloseTo(2, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("max is not a tail statistic: it hides where P99 sits")
        void maxIsNotAP99() {
            // 98 requests at 10 ms, 2 at 3000 ms: 2% of traffic, so the tail really is
            // inside the top 1%.
            double[] samples = new double[100];
            java.util.Arrays.fill(samples, 10);
            samples[98] = 3000;
            samples[99] = 3000;

            Distribution distribution = Distribution.of(samples);

            assertThat(distribution.avg()).isCloseTo(70.0, org.assertj.core.data.Offset.offset(5.0));
            // ceil(0.99 * 100) = rank 99, which is one of the two slow samples.
            assertThat(distribution.p99()).isEqualTo(3000);
            // Rank 95 is still inside the fast population.
            assertThat(distribution.p95()).isEqualTo(10);
            // The whole point: a design that is fast 98% of the time and
            // catastrophically slow otherwise has an average that looks fine, and only
            // the tail percentile says anything useful about it.
            assertThat(distribution.avg()).isLessThan(100);
            assertThat(distribution.p99()).isGreaterThan(2900);
        }

        @Test
        @DisplayName("one bad request in a hundred is the maximum, not the P99")
        void singleOutlierIsTheMaxNotTheP99() {
            // Nearest rank makes P99 of 100 samples the 99th smallest. One bad
            // request in a hundred is the 100th, so it appears as max. Recorded
            // here because anyone reading a P99 has to know this.
            double[] samples = new double[100];
            java.util.Arrays.fill(samples, 10);
            samples[99] = 3000;

            Distribution distribution = Distribution.of(samples);

            assertThat(distribution.p99()).isEqualTo(10);
            assertThat(distribution.max()).isEqualTo(3000);
        }
    }

    @Nested
    @DisplayName("threshold fraction")
    class Fraction {

        @Test
        @DisplayName("counts samples at or below the threshold")
        void countsAtOrBelow() {
            Distribution distribution = Distribution.of(new double[]{1, 2, 3, 4, 5});

            assertThat(distribution.fractionAtOrBelow(3)).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(distribution.fractionAtOrBelow(5)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(distribution.fractionAtOrBelow(0)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("is the companion that catches a design P99 cannot see")
        void catchesHighErrorRate() {
            Distribution distribution = Distribution.of(new double[]{10, 10, 10, 10, 500, 500});

            assertThat(distribution.p99()).isEqualTo(500);
            assertThat(distribution.fractionAtOrBelow(100)).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("an empty distribution reports zeros rather than NaN")
        void emptyReportsZero() {
            for (Distribution distribution : new Distribution[]{
                    Distribution.of(null), Distribution.of(new double[0])}) {
                assertThat(distribution.isEmpty()).isTrue();
                assertThat(distribution.count()).isZero();
                assertThat(distribution.min()).isZero();
                assertThat(distribution.max()).isZero();
                assertThat(distribution.avg()).isZero();
                assertThat(distribution.stdDev()).isZero();
                assertThat(distribution.p50()).isZero();
                assertThat(distribution.p95()).isZero();
                assertThat(distribution.p99()).isZero();
                assertThat(distribution.fractionAtOrBelow(10)).isZero();
            }
        }

        @Test
        @DisplayName("fewer than two samples have no standard deviation")
        void noStdDevForOneSample() {
            assertThat(Distribution.of(new double[]{7}).stdDev()).isEqualTo(0);
        }

        @Test
        @DisplayName("a constant sample set has zero variance, not NaN")
        void constantSetHasNoVariance() {
            Distribution distribution = Distribution.of(new double[]{5, 5, 5, 5});

            assertThat(distribution.stdDev()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(distribution.avg()).isCloseTo(5, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("does not mutate or alias the caller's array")
        void doesNotMutateInput() {
            double[] input = {5, 1, 3};
            Distribution.of(input);

            assertThat(input).containsExactly(5, 1, 3);
        }

        @Test
        @DisplayName("two far-apart samples give the wider standard deviation")
        void spreadWidensStdDev() {
            double narrow = Distribution.of(new double[]{99, 101}).stdDev();
            double wide = Distribution.of(new double[]{0, 200}).stdDev();

            assertThat(narrow).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(wide).isCloseTo(100, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(wide).isGreaterThan(narrow);
        }

        @Test
        @DisplayName("variance never goes negative from floating-point rounding")
        void varianceIsNotNegative() {
            // Values whose mean is not exactly representable; a naive
            // E[x^2] - E[x]^2 goes slightly negative here and Math.sqrt returns NaN.
            Distribution distribution = Distribution.of(new double[]{0.1, 0.2, 0.3, 0.4, 0.5});

            assertThat(distribution.stdDev()).isFinite();
            assertThat(distribution.stdDev()).isCloseTo(0.1414, within(0.001));
        }
    }

    private static double[] range(int from, int to) {
        double[] values = new double[to - from + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = from + i;
        }
        return values;
    }

    private static double[] reverse(double[] values) {
        double[] copy = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[values.length - 1 - i];
        }
        return copy;
    }
}



