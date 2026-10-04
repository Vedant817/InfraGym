package com.archforge.simulation.workload;

import java.util.List;

/**
 * The offered load over a run: a sequence of phases, each with a target arrival
 * rate.
 *
 * <p>Phases are what make a scenario mean something. "A flash sale" is not "high
 * throughput" — it is a short, extreme burst followed by collapse, and the
 * difference is visible in the queue-depth and latency traces. A single constant
 * rate cannot express that, which is why the previous generator produced one flat
 * number per scenario and left the interesting behaviour unrepresentable.
 *
 * @param phases ordered phases; the first starts at tick 0
 */
public record WorkloadProfile(List<Phase> phases, long durationSeconds) {

    public WorkloadProfile {
        if (phases == null || phases.isEmpty()) {
            throw new IllegalArgumentException("A workload profile needs at least one phase");
        }
        phases = List.copyOf(phases);
    }

    /**
     * One phase of the workload.
     *
     * <p>Both levels are <b>multipliers</b> of the graph's own nominal capacity, never
     * absolute rates. A profile is the <em>shape</em> of the offered load; the rate it
     * is applied to depends on the architecture being tested. That matters here because
     * a 10x burst has to mean ten times <em>this design's</em> capacity — the same
     * scenario has to be meaningful for a design rated at 5 rps and one rated at
     * 50 000. Storing absolute rates instead double-counted: a graph already rated at
     * 1000 rps had its phases multiplied by 1000 a second time, so a nominal run
     * offered a million requests per second and the reports were nonsense by a factor
     * of a thousand.
     *
     * @param name             phase label, published on every telemetry sample so a
     *                         chart can shade it and the evaluator can attribute a
     *                         regression to "the spike" rather than "on average"
     * @param durationSeconds  length in seconds
     * @param targetMultiplier offered load at the end of this phase, relative to the
     *                         graph's nominal capacity; 1.0 means "exactly its
     *                         sustainable rate"
     * @param startMultiplier  offered load at the start of this phase
     * @param ramp             whether the phase ramps linearly between the two levels or
     *                         holds {@code targetMultiplier} flat for its whole duration
     */
    public record Phase(
            String name,
            int durationSeconds,
            double targetMultiplier,
            double startMultiplier,
            boolean ramp) {

        public Phase {
            if (durationSeconds <= 0) {
                throw new IllegalArgumentException("Phase '" + name + "' must have a positive duration");
            }
            if (startMultiplier < 0) {
                throw new IllegalArgumentException("Phase '" + name + "' has a negative start multiplier");
            }
            if (targetMultiplier < 0) {
                throw new IllegalArgumentException("Phase '" + name + "' has a negative target multiplier");
            }
        }

        public double endMultiplier() {
            return targetMultiplier;
        }

        /** Highest offered load this phase reaches, in requests per second. */
        public double peakRps(double nominalRps) {
            return nominalRps * Math.max(startMultiplier, targetMultiplier);
        }
    }

    public long totalDurationSeconds() {
        return phases.stream().mapToLong(Phase::durationSeconds).sum();
    }

    /**
     * Offered arrivals per second at a given simulated second.
     *
     * <p>Ramps interpolate linearly between the phase's start and end multipliers, so a
     * traffic spike has a real leading edge rather than appearing instantaneously.
     * Time past the end of the profile holds the last phase's rate — dropping to zero
     * would read as "the design served nothing at the end of the run", which is a bug
     * report nobody can act on.
     */
    public double offeredRpsAt(double nominalRps, double elapsedSeconds) {
        return nominalRps * multiplierAt(elapsedSeconds);
    }

    /** The phase multiplier at a given simulated second. */
    public double multiplierAt(double elapsedSeconds) {
        double elapsed = 0;
        for (Phase phase : phases) {
            if (elapsedSeconds < elapsed + phase.durationSeconds()) {
                double intoPhase = elapsedSeconds - elapsed;
                double progress = intoPhase / phase.durationSeconds();

                if (!phase.ramp()) {
                    return phase.targetMultiplier();
                }

                double start = phase.startMultiplier();
                double end = phase.endMultiplier();
                return start + (end - start) * clamp(progress, 0, 1);
            }
            elapsed += phase.durationSeconds();
        }
        // Past the end of the profile: hold the final rate.
        return phases.get(phases.size() - 1).targetMultiplier();
    }

    /**
     * Peak offered load over the whole profile, in requests per second.
     *
     * <p>The number a scenario's headline figure is measured against.
     */
    public double peakRps(double nominalRps) {
        return phases.stream()
                .mapToDouble(phase -> phase.peakRps(nominalRps))
                .max()
                .orElse(0);
    }

    /**
     * Highest multiplier over the profile's <em>scenario</em> phases, ignoring warmup.
 *
 * <p>Every shape opens by ramping up from a low fraction of capacity so the design's
 * cold state is measured too. That ramp is a property of how a run starts, not of the
 * scenario, and including it makes a scenario declared at 1.0x report a 0.2x minimum —
 * so a traffic collapse to 0.1x becomes indistinguishable from the warmup that precedes
 * every run, and a comparison against {@link ScenarioType#getExtremeLoadMultiplier()}
 * cannot tell whether a scenario reached the load it promised.
 *
 * <p>A leading phase counts as warmup when it ramps <em>upward</em> from below its own
 * target. A collapse's first phase also ramps, but downward, so it is never excluded.
 */
    public double peakMultiplier() {
        return scenarioPhases().stream()
                .mapToDouble(phase -> Math.max(phase.startMultiplier(), phase.targetMultiplier()))
                .max()
                .orElse(0);
    }

    /**
     * Lowest multiplier over the profile's scenario phases, ignoring warmup.
     *
     * <p>For a collapse this is its trough — the most extreme moment in the run, which
     * is its quietest one.
     */
    public double minimumMultiplier() {
        return scenarioPhases().stream()
                .mapToDouble(phase -> Math.min(phase.startMultiplier(), phase.targetMultiplier()))
                .min()
                .orElse(0);
    }

    /** Multiplier at the highest and lowest second of the whole run, warmup included. */
    public double absolutePeakMultiplier() {
        return phases.stream()
                .mapToDouble(phase -> Math.max(phase.startMultiplier(), phase.targetMultiplier()))
                .max()
                .orElse(0);
    }

    /** Phases excluding a leading upward ramp-in. Never empty. */
    private List<Phase> scenarioPhases() {
        if (phases.size() > 1 && isWarmupRamp(phases.get(0))) {
            return phases.subList(1, phases.size());
        }
        return phases;
    }

    private static boolean isWarmupRamp(Phase phase) {
        return phase.ramp() && phase.startMultiplier() < phase.targetMultiplier();
    }

    /** Name of the phase containing a given simulated second. */
    public String phaseAt(double elapsedSeconds) {
        double elapsed = 0;
        for (Phase phase : phases) {
            if (elapsedSeconds < elapsed + phase.durationSeconds()) {
                return phase.name();
            }
            elapsed += phase.durationSeconds();
        }
        return phases.get(phases.size() - 1).name();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
