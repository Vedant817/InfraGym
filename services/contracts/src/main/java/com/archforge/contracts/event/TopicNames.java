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
import java.util.List;

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
    public static final String SIMULATION_LIFECYCLE_DLT = "simulation.lifecycle.dlt";
    public static final String AGGREGATED_METRICS_DLT = "aggregated.metrics.dlt";
    public static final String AGGREGATED_SUMMARY_DLT = "aggregated.summary.dlt";
    public static final String EVALUATION_RESULTS_DLT = "evaluation.results.dlt";
    public static final String EVALUATION_LIFECYCLE_DLT = "evaluation.lifecycle.dlt";
    public static final String SUBMISSION_LIFECYCLE_DLT = "submission.lifecycle.dlt";
    public static final String DESIGN_UPDATES_DLT = "design.updates.dlt";

    public static final String DESIGN_SUBMISSIONS_DLT_ENV = "TOPIC_DESIGN_SUBMISSIONS_DLT";
    public static final String SIMULATION_TASKS_DLT_ENV = "TOPIC_SIMULATION_TASKS_DLT";
    public static final String SIMULATION_TELEMETRY_DLT_ENV = "TOPIC_SIMULATION_TELEMETRY_DLT";
    public static final String SIMULATION_LIFECYCLE_DLT_ENV = "TOPIC_SIMULATION_LIFECYCLE_DLT";
    public static final String AGGREGATED_METRICS_DLT_ENV = "TOPIC_AGGREGATED_METRICS_DLT";
    public static final String AGGREGATED_SUMMARY_DLT_ENV = "TOPIC_AGGREGATED_SUMMARY_DLT";
    public static final String EVALUATION_RESULTS_DLT_ENV = "TOPIC_EVALUATION_RESULTS_DLT";
    public static final String EVALUATION_LIFECYCLE_DLT_ENV = "TOPIC_EVALUATION_LIFECYCLE_DLT";
    public static final String SUBMISSION_LIFECYCLE_DLT_ENV = "TOPIC_SUBMISSION_LIFECYCLE_DLT";
    public static final String DESIGN_UPDATES_DLT_ENV = "TOPIC_DESIGN_UPDATES_DLT";

    /**
     * Every declared topic, so a deployment can be checked against the catalogue.
     *
     * <p>The topic bootstrapper reads {@code infrastructure/kafka/topics.conf}; this is
     * the same list in code. Used by the health check to report a topic that this
     * codebase expects but the cluster does not have.
     */
    public static List<String> all() {
        return List.of(DESIGN_SUBMISSIONS, DESIGN_UPDATES, SUBMISSION_LIFECYCLE,
                SIMULATION_TASKS, SIMULATION_TELEMETRY, SIMULATION_LIFECYCLE,
                AGGREGATED_METRICS, AGGREGATED_SUMMARY,
                EVALUATION_RESULTS, EVALUATION_LIFECYCLE,
                DESIGN_SUBMISSIONS_DLT, SIMULATION_TASKS_DLT, SIMULATION_TELEMETRY_DLT,
                SIMULATION_LIFECYCLE_DLT, AGGREGATED_METRICS_DLT, AGGREGATED_SUMMARY_DLT,
                EVALUATION_RESULTS_DLT, EVALUATION_LIFECYCLE_DLT,
                SUBMISSION_LIFECYCLE_DLT, DESIGN_UPDATES_DLT);
    }

    /**
     * Maps a source topic to its dead-letter topic.
     *
     * <p><b>Throws for a topic with no declared dead-letter queue</b>, rather than
     * composing {@code sourceTopic + ".dlt"}.
     *
     * <p>That is not hypothetical caution. Four topics once had consumers and no declared
     * DLQ: {@code simulation.lifecycle}, {@code aggregated.summary},
     * {@code evaluation.lifecycle} and {@code design.updates}. The composed names were not
     * in {@code topics.conf}, so with auto-creation disabled the dead-letter send failed
     * with {@code UNKNOWN_TOPIC_OR_PARTITION}; the recoverer retried, the retry
     * dead-lettered to the same missing topic, and the partition looped on
     * "Record in retry and not yet recovered" indefinitely. All four now exist in the
     * catalogue and are mapped below. The throw remains, because it is what turns the next
     * consumer of a new topic into a startup failure rather than an infinite retry.
     *
     * <p><b>Throws rather than guessing.</b> This is the contract for code that decides
     * where to write — a producer must not silently invent a destination. Callers on a
     * recovery path must use {@link KafkaTopics#deadLetterFor} instead, which resolves
     * without throwing precisely because an exception from a
     * {@code DeadLetterPublishingRecoverer} resolver does not report a bad configuration:
     * it aborts the recovery, the offset is not committed, and the poison record is
     * redelivered on every poll forever.
     *
     * @throws IllegalArgumentException if the source topic has no declared DLT
     */
    public static String deadLetterFor(String sourceTopic) {
        if (sourceTopic == null) {
            throw new IllegalArgumentException("Cannot resolve a dead-letter topic for a null source");
        }
        return switch (sourceTopic) {
            case DESIGN_SUBMISSIONS -> DESIGN_SUBMISSIONS_DLT;
            case SIMULATION_TASKS -> SIMULATION_TASKS_DLT;
            case SIMULATION_TELEMETRY -> SIMULATION_TELEMETRY_DLT;
            case SIMULATION_LIFECYCLE -> SIMULATION_LIFECYCLE_DLT;
            case AGGREGATED_METRICS -> AGGREGATED_METRICS_DLT;
            case AGGREGATED_SUMMARY -> AGGREGATED_SUMMARY_DLT;
            case EVALUATION_RESULTS -> EVALUATION_RESULTS_DLT;
            case EVALUATION_LIFECYCLE -> EVALUATION_LIFECYCLE_DLT;
            case SUBMISSION_LIFECYCLE -> SUBMISSION_LIFECYCLE_DLT;
            case DESIGN_UPDATES -> DESIGN_UPDATES_DLT;
            default -> throw new IllegalArgumentException(
                    "No dead-letter topic is declared for '" + sourceTopic
                            + "'. Either add one to TopicNames and topics.conf, or handle the "
                            + "failure without dead-lettering it. Composing the name here would "
                            + "publish to a topic that does not exist.");
        };
    }

    /**
     * Whether a dead-letter topic is declared for this source.
     *
     * <p>Every topic with a {@code @KafkaListener} must answer true here. A container on a
     * topic that does not is the configuration this guards against: recovery has nowhere
     * declared to write, and every option is bad — see
     * {@code KafkaTopics.deadLetterFor} for how the recovery path copes without looping.
     */
    public static boolean hasDeadLetterFor(String sourceTopic) {
        if (sourceTopic == null) {
            return false;
        }
        return switch (sourceTopic) {
            case DESIGN_SUBMISSIONS, SIMULATION_TASKS, SIMULATION_TELEMETRY,
                 SIMULATION_LIFECYCLE, AGGREGATED_METRICS, AGGREGATED_SUMMARY,
                 EVALUATION_RESULTS, EVALUATION_LIFECYCLE, SUBMISSION_LIFECYCLE,
                 DESIGN_UPDATES -> true;
            default -> false;
        };
    }
}
