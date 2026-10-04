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

    /** Dead-letter topic for an arbitrary source topic, honouring configuration overrides. */
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
        return TopicNames.deadLetterFor(sourceTopic);
    }
}
