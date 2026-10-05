package com.archforge.messaging;

import com.archforge.contracts.event.TopicNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves every topic name from configuration, with the contract default as
 * fallback.
 *
 * <p>Each setter-style accessor is a distinct bean property, so a service can
 * override exactly the topics it cares about without duplicating the catalogue.
 * Nothing in the codebase may pass a topic string literal to a Kafka client —
 * that rule is what makes this class the only place a topic name is read.
 *
 * <p>Every value is overridable by environment variable ({@code TOPIC_*} or, for
 * the bootstrap list, {@code KAFKA_BOOTSTRAP_SERVERS}) so the same artifact runs
 * against local, staging and production clusters.
 */
@Component
public class KafkaTopics {

    private final String designSubmissions;
    private final String designUpdates;
    private final String submissionLifecycle;
    private final String simulationTasks;
    private final String simulationTelemetry;
    private final String simulationLifecycle;
    private final String aggregatedMetrics;
    private final String aggregatedSummary;
    private final String evaluationResults;
    private final String evaluationLifecycle;

    private final String designSubmissionsDlt;
    private final String simulationTasksDlt;
    private final String simulationLifecycleDlt;
    private final String aggregatedSummaryDlt;
    private final String evaluationLifecycleDlt;
    private final String designUpdatesDlt;
    private final String simulationTelemetryDlt;
    private final String aggregatedMetricsDlt;
    private final String evaluationResultsDlt;
    private final String submissionLifecycleDlt;

    public KafkaTopics(
            @Value("${archforge.topics.design-submissions:" + TopicNames.DESIGN_SUBMISSIONS + "}") String designSubmissions,
            @Value("${archforge.topics.design-updates:" + TopicNames.DESIGN_UPDATES + "}") String designUpdates,
            @Value("${archforge.topics.submission-lifecycle:" + TopicNames.SUBMISSION_LIFECYCLE + "}") String submissionLifecycle,
            @Value("${archforge.topics.simulation-tasks:" + TopicNames.SIMULATION_TASKS + "}") String simulationTasks,
            @Value("${archforge.topics.simulation-telemetry:" + TopicNames.SIMULATION_TELEMETRY + "}") String simulationTelemetry,
            @Value("${archforge.topics.simulation-lifecycle:" + TopicNames.SIMULATION_LIFECYCLE + "}") String simulationLifecycle,
            @Value("${archforge.topics.aggregated-metrics:" + TopicNames.AGGREGATED_METRICS + "}") String aggregatedMetrics,
            @Value("${archforge.topics.aggregated-summary:" + TopicNames.AGGREGATED_SUMMARY + "}") String aggregatedSummary,
            @Value("${archforge.topics.evaluation-results:" + TopicNames.EVALUATION_RESULTS + "}") String evaluationResults,
            @Value("${archforge.topics.evaluation-lifecycle:" + TopicNames.EVALUATION_LIFECYCLE + "}") String evaluationLifecycle,
            @Value("${archforge.topics.design-submissions-dlt:" + TopicNames.DESIGN_SUBMISSIONS_DLT + "}") String designSubmissionsDlt,
            @Value("${archforge.topics.simulation-tasks-dlt:" + TopicNames.SIMULATION_TASKS_DLT + "}") String simulationTasksDlt,
        @Value("${archforge.topics.simulation-lifecycle-dlt:" + TopicNames.SIMULATION_LIFECYCLE_DLT + "}") String simulationLifecycleDlt,
        @Value("${archforge.topics.aggregated-summary-dlt:" + TopicNames.AGGREGATED_SUMMARY_DLT + "}") String aggregatedSummaryDlt,
        @Value("${archforge.topics.evaluation-lifecycle-dlt:" + TopicNames.EVALUATION_LIFECYCLE_DLT + "}") String evaluationLifecycleDlt,
        @Value("${archforge.topics.design-updates-dlt:" + TopicNames.DESIGN_UPDATES_DLT + "}") String designUpdatesDlt,
            @Value("${archforge.topics.simulation-telemetry-dlt:" + TopicNames.SIMULATION_TELEMETRY_DLT + "}") String simulationTelemetryDlt,
            @Value("${archforge.topics.aggregated-metrics-dlt:" + TopicNames.AGGREGATED_METRICS_DLT + "}") String aggregatedMetricsDlt,
            @Value("${archforge.topics.evaluation-results-dlt:" + TopicNames.EVALUATION_RESULTS_DLT + "}") String evaluationResultsDlt,
            @Value("${archforge.topics.submission-lifecycle-dlt:" + TopicNames.SUBMISSION_LIFECYCLE_DLT + "}") String submissionLifecycleDlt) {
        this.designSubmissions = designSubmissions;
        this.designUpdates = designUpdates;
        this.submissionLifecycle = submissionLifecycle;
        this.simulationTasks = simulationTasks;
        this.simulationTelemetry = simulationTelemetry;
        this.simulationLifecycle = simulationLifecycle;
        this.aggregatedMetrics = aggregatedMetrics;
        this.aggregatedSummary = aggregatedSummary;
        this.evaluationResults = evaluationResults;
        this.evaluationLifecycle = evaluationLifecycle;
        this.designSubmissionsDlt = designSubmissionsDlt;
        this.simulationTasksDlt = simulationTasksDlt;
        this.simulationLifecycleDlt = simulationLifecycleDlt;
        this.aggregatedSummaryDlt = aggregatedSummaryDlt;
        this.evaluationLifecycleDlt = evaluationLifecycleDlt;
        this.designUpdatesDlt = designUpdatesDlt;
        this.simulationTelemetryDlt = simulationTelemetryDlt;
        this.aggregatedMetricsDlt = aggregatedMetricsDlt;
        this.evaluationResultsDlt = evaluationResultsDlt;
        this.submissionLifecycleDlt = submissionLifecycleDlt;
    }

    public String designSubmissions() {
        return designSubmissions;
    }

    public String designUpdates() {
        return designUpdates;
    }

    public String submissionLifecycle() {
        return submissionLifecycle;
    }

    public String simulationTasks() {
        return simulationTasks;
    }

    public String simulationTelemetry() {
        return simulationTelemetry;
    }

    public String simulationLifecycle() {
        return simulationLifecycle;
    }

    public String aggregatedMetrics() {
        return aggregatedMetrics;
    }

    public String aggregatedSummary() {
        return aggregatedSummary;
    }

    public String evaluationResults() {
        return evaluationResults;
    }

    public String evaluationLifecycle() {
        return evaluationLifecycle;
    }

    public String designSubmissionsDlt() {
        return designSubmissionsDlt;
    }

    public String simulationLifecycleDlt() {
        return simulationLifecycleDlt;
    }

    public String aggregatedSummaryDlt() {
        return aggregatedSummaryDlt;
    }

    public String evaluationLifecycleDlt() {
        return evaluationLifecycleDlt;
    }

    public String designUpdatesDlt() {
        return designUpdatesDlt;
    }

    public String simulationTasksDlt() {
        return simulationTasksDlt;
    }

    public String simulationTelemetryDlt() {
        return simulationTelemetryDlt;
    }

    public String aggregatedMetricsDlt() {
        return aggregatedMetricsDlt;
    }

    public String evaluationResultsDlt() {
        return evaluationResultsDlt;
    }

    public String submissionLifecycleDlt() {
        return submissionLifecycleDlt;
    }

    /**
     * Every topic resolved to its contract name, with no configuration.
     *
     * <p>Exists because this class has twenty constructor parameters and tests construct it
     * positionally. Adding the five dead-letter topics that {@code simulation.lifecycle},
     * {@code aggregated.summary}, {@code evaluation.lifecycle} and {@code design.updates}
     * needed broke both call sites that spelled out all seventeen previous names -- and the
     * failure was an arity error in a test, which says nothing about what broke or why.
     *
     * <p>The deeper problem is duplication, not arity. A test that hard-codes topic names
     * asserts against a second copy of the catalogue, and a second copy is exactly what
     * drifts: the test would keep passing while the broker was configured from
     * {@code topics.conf} and the two disagreed. Deriving every name from
     * {@link TopicNames} makes that impossible -- adding a topic is a one-line change here
     * and in the contract, and no test can be left behind.
     *
     * <p>Overrides are still the normal path; this is for a test or a tool that wants the
     * contract names without a Spring context.
     */
    public static KafkaTopics withContractNames() {
        return new KafkaTopics(
                TopicNames.DESIGN_SUBMISSIONS,
                TopicNames.DESIGN_UPDATES,
                TopicNames.SUBMISSION_LIFECYCLE,
                TopicNames.SIMULATION_TASKS,
                TopicNames.SIMULATION_TELEMETRY,
                TopicNames.SIMULATION_LIFECYCLE,
                TopicNames.AGGREGATED_METRICS,
                TopicNames.AGGREGATED_SUMMARY,
                TopicNames.EVALUATION_RESULTS,
                TopicNames.EVALUATION_LIFECYCLE,
                TopicNames.DESIGN_SUBMISSIONS_DLT,
                TopicNames.SIMULATION_TASKS_DLT,
                TopicNames.SIMULATION_LIFECYCLE_DLT,
                TopicNames.AGGREGATED_SUMMARY_DLT,
                TopicNames.EVALUATION_LIFECYCLE_DLT,
                TopicNames.DESIGN_UPDATES_DLT,
                TopicNames.SIMULATION_TELEMETRY_DLT,
                TopicNames.AGGREGATED_METRICS_DLT,
                TopicNames.EVALUATION_RESULTS_DLT,
                TopicNames.SUBMISSION_LIFECYCLE_DLT);
    }

    /**
     * Dead-letter topic for an arbitrary source topic, honouring configuration overrides.
     *
     * <p><b>This is the one that must never throw</b>, because it backs the
     * {@code DeadLetterPublishingRecoverer} destination resolver. Two failure modes have to
     * be avoided at once and they pull in opposite directions:
     *
     * <ul>
     *   <li>Throwing (or returning null) aborts recovery. The offset is not committed, so
     *       the poison record is redelivered on every poll forever — a livelock that looks
     *       like a stuck consumer.</li>
     *   <li>Returning the <em>source</em> topic publishes the poison record back where the
     *       container is reading from. It fails again, dead-letters again, forever: a
     *       self-poison loop, and the one that would look like a healthy broker.</li>
     * </ul>
     *
     * <p>So an unmapped source composes {@code <source>.dlt}: not declared in
     * {@code topics.conf}, so it may be auto-created at the broker's default partition
     * count, but never the source itself, so nothing loops. {@link
     * TopicNames#hasDeadLetterFor} is the query a deployment check should use to find the
     * misconfiguration rather than waiting to trip over it.
     */
    public String deadLetterFor(String sourceTopic) {
        if (sourceTopic == null) {
            return null;
        }
        if (designSubmissions.equals(sourceTopic)) {
            return designSubmissionsDlt;
        }
        if (simulationTasks.equals(sourceTopic)) {
            return simulationTasksDlt;
        }
        if (simulationTelemetry.equals(sourceTopic)) {
            return simulationTelemetryDlt;
        }
        if (aggregatedMetrics.equals(sourceTopic)) {
            return aggregatedMetricsDlt;
        }
        if (evaluationResults.equals(sourceTopic)) {
            return evaluationResultsDlt;
        }
        if (submissionLifecycle.equals(sourceTopic)) {
            return submissionLifecycleDlt;
        }
        return TopicNames.hasDeadLetterFor(sourceTopic)
                ? TopicNames.deadLetterFor(sourceTopic)
                : sourceTopic + ".dlt";
    }
}
