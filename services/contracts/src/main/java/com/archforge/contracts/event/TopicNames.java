package com.archforge.contracts.event;

/**
 * Every Kafka topic the platform uses, plus the environment variable that
 * overrides it.
 *
 * <p>Centralised for one reason: a topic name is a wire contract between two
 * independently deployed services, and a typo produces a service that silently
 * publishes to nothing. With the name in one place, and the value injectable,
 * a staging cluster can rename a topic without a rebuild.
 *
 * <p>Pair every constant with its {@code *_ENV} override. Services must never
 * hard-code a topic literal.
 */
public final class TopicNames {

    private TopicNames() {
    }

    // --- design --------------------------------------------------------------

    /** Gateway -> compiler. One message per submission. Keyed by submissionId. */
    public static final String DESIGN_SUBMISSIONS = "design.submissions";
    public static final String DESIGN_SUBMISSIONS_ENV = "TOPIC_DESIGN_SUBMISSIONS";

    /** Canvas-session -> compacted state stream. Keyed by sessionId. */
    public static final String DESIGN_UPDATES = "design.updates";
    public static final String DESIGN_UPDATES_ENV = "TOPIC_DESIGN_UPDATES";

    /**
     * Submission progress, published by every stage and consumed only by the
     * design service. Keyed by submissionId so all transitions for one submission
     * are ordered on a single partition.
     */
    public static final String SUBMISSION_LIFECYCLE = "submission.lifecycle";
    public static final String SUBMISSION_LIFECYCLE_ENV = "TOPIC_SUBMISSION_LIFECYCLE";

    // --- compilation ---------------------------------------------------------

    /** Compiler -> simulation-engine. Keyed by simulationId. */
    public static final String SIMULATION_TASKS = "simulation.tasks";
    public static final String SIMULATION_TASKS_ENV = "TOPIC_SIMULATION_TASKS";

    // --- simulation ----------------------------------------------------------

    /** Engine -> aggregator. One message per metric per node per tick. */
    public static final String SIMULATION_TELEMETRY = "simulation.telemetry";
    public static final String SIMULATION_TELEMETRY_ENV = "TOPIC_SIMULATION_TELEMETRY";

    /** Engine -> aggregator/design-service. Run start and terminal transitions. */
    public static final String SIMULATION_LIFECYCLE = "simulation.lifecycle";
    public static final String SIMULATION_LIFECYCLE_ENV = "TOPIC_SIMULATION_LIFECYCLE";

    // --- aggregation ---------------------------------------------------------

    /** Aggregator -> frontend/design-service. Per-window time series. */
    public static final String AGGREGATED_METRICS = "aggregated.metrics";
    public static final String AGGREGATED_METRICS_ENV = "TOPIC_AGGREGATED_METRICS";

    /** Aggregator -> ai-evaluator. Final per-run rollup; triggers evaluation. */
    public static final String AGGREGATED_SUMMARY = "aggregated.summary";
    public static final String AGGREGATED_SUMMARY_ENV = "TOPIC_AGGREGATED_SUMMARY";

    // --- evaluation ----------------------------------------------------------

    /** Evaluator -> design-service/gateway. The structured critique. */
    public static final String EVALUATION_RESULTS = "evaluation.results";
    public static final String EVALUATION_RESULTS_ENV = "TOPIC_EVALUATION_RESULTS";

    /** Evaluator -> design-service. Evaluation progress. */
    public static final String EVALUATION_LIFECYCLE = "evaluation.lifecycle";
    public static final String EVALUATION_LIFECYCLE_ENV = "TOPIC_EVALUATION_LIFECYCLE";

    // --- dead letter queues --------------------------------------------------

    public static final String DESIGN_SUBMISSIONS_DLT = "design.submissions.dlt";
    public static final String SIMULATION_TASKS_DLT = "simulation.tasks.dlt";
    public static final String SIMULATION_TELEMETRY_DLT = "simulation.telemetry.dlt";
    public static final String AGGREGATED_METRICS_DLT = "aggregated.metrics.dlt";
    public static final String EVALUATION_RESULTS_DLT = "evaluation.results.dlt";
    public static final String SUBMISSION_LIFECYCLE_DLT = "submission.lifecycle.dlt";

    public static final String DESIGN_SUBMISSIONS_DLT_ENV = "TOPIC_DESIGN_SUBMISSIONS_DLT";
    public static final String SIMULATION_TASKS_DLT_ENV = "TOPIC_SIMULATION_TASKS_DLT";
    public static final String SIMULATION_TELEMETRY_DLT_ENV = "TOPIC_SIMULATION_TELEMETRY_DLT";
    public static final String AGGREGATED_METRICS_DLT_ENV = "TOPIC_AGGREGATED_METRICS_DLT";
    public static final String EVALUATION_RESULTS_DLT_ENV = "TOPIC_EVALUATION_RESULTS_DLT";
    public static final String SUBMISSION_LIFECYCLE_DLT_ENV = "TOPIC_SUBMISSION_LIFECYCLE_DLT";

    /** Maps a source topic to its dead-letter topic. */
    public static String deadLetterFor(String sourceTopic) {
        if (sourceTopic == null) {
            return null;
        }
        return switch (sourceTopic) {
            case DESIGN_SUBMISSIONS -> DESIGN_SUBMISSIONS_DLT;
            case SIMULATION_TASKS -> SIMULATION_TASKS_DLT;
            case SIMULATION_TELEMETRY -> SIMULATION_TELEMETRY_DLT;
            case AGGREGATED_METRICS -> AGGREGATED_METRICS_DLT;
            case EVALUATION_RESULTS -> EVALUATION_RESULTS_DLT;
            case SUBMISSION_LIFECYCLE -> SUBMISSION_LIFECYCLE_DLT;
            default -> sourceTopic + ".dlt";
        };
    }
}
