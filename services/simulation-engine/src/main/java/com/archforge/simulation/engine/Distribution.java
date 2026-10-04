package com.archforge.simulation.engine;

import java.util.Arrays;

/**
 * Percentiles over a sample set, plus the summary statistics the evaluator needs.
 *
 * <p><b>Why this exists.</b> The README promises P95 and P99 latency and builds an entire
 * worked example around a P99 of 850 ms. The aggregator accumulated only min/max/avg —
 * there was no percentile anywhere in the pipeline — so any tail-latency assessment was
 * hallucinated. Max is not a substitute: it is a different statistic, and reporting it
 * as though it described the tail is how a design with one 3-second outlier looks
 * identical to one that is uniformly slow.
 *
 * <p>Percentiles use the nearest-rank method on a sorted copy. It needs no histogram
 * or quantile sketch, is exact for the sample set at hand, and returns an observed
 * value rather than an interpolated one — which is what a reader expects when the
 * report says "the P99 you observed was 840 ms".
 */
public final class Distribution {

    private final double[] sorted;
    private final double sum;
    private final double sumOfSquares;

    private Distribution(double[] sorted, double sum, double sumOfSquares) {
        this.sorted = sorted;
        this.sum = sum;
        this.sumOfSquares = sumOfSquares;
    }

    /**
     * @param samples latency samples in milliseconds; the array is copied and sorted,
     *                so the caller's array is untouched
     */
    public static Distribution of(double[] samples) {
        if (samples == null || samples.length == 0) {
            return new Distribution(new double[0], 0, 0);
        }
        double[] sorted = Arrays.copyOf(samples, samples.length);
        Arrays.sort(sorted);

        double sum = 0;
        double sumOfSquares = 0;
        for (double value : sorted) {
            sum += value;
            sumOfSquares += value * value;
        }
        return new Distribution(sorted, sum, sumOfSquares);
    }

    public int count() {
        return sorted.length;
    }

    public boolean isEmpty() {
        return sorted.length == 0;
    }

    public double min() {
        return sorted.length == 0 ? 0 : sorted[0];
    }

    public double max() {
        return sorted.length == 0 ? 0 : sorted[sorted.length - 1];
    }

    public double avg() {
        return sorted.length == 0 ? 0 : sum / sorted.length;
    }

    /** Population standard deviation; 0 for fewer than two samples. */
    public double stdDev() {
        if (sorted.length < 2) {
            return 0;
        }
        double mean = sum / sorted.length;
        double variance = (sumOfSquares / sorted.length) - (mean * mean);
        return Math.sqrt(Math.max(0, variance));
    }

    public double p50() {
        return percentile(50);
    }

    public double p95() {
        return percentile(95);
    }

    public double p99() {
        return percentile(99);
    }

    /**
     * Nearest-rank percentile.
     *
     * @param percentile 0-100
     * @return an observed sample value, or 0 when there are none
     */
    public double percentile(double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        double clamped = Math.max(0, Math.min(100, percentile));
        // Nearest rank: ceil(p/100 * n), 1-indexed.
        int rank = (int) Math.ceil(clamped / 100.0 * sorted.length);
        int index = Math.max(0, Math.min(sorted.length - 1, rank - 1));
        return sorted[index];
    }

    /**
     * Fraction of samples at or below a threshold.
     *
     * <p>The error-rate companion to the percentiles: a design can have a healthy P99
     * and still fail 8% of requests, and only the distribution shows both.
     */
    public double fractionAtOrBelow(double threshold) {
        if (sorted.length == 0) {
            return 0;
        }
        int count = 0;
        for (double value : sorted) {
            if (value <= threshold) {
                count++;
            }
        }
        return (double) count / sorted.length;
    }
}
