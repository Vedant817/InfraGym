package com.archforge.design.ingest;

import com.archforge.contracts.event.SubmissionLifecycleEvent;
import com.archforge.contracts.event.TopicNames;
import com.archforge.contracts.lifecycle.SubmissionStatus;
import com.archforge.design.domain.SubmissionEntity;
import com.archforge.design.domain.SubmissionEventEntity;
import com.archforge.design.repository.SubmissionEventRepository;
import com.archforge.design.repository.SubmissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The system of record's view of a run, built from {@code submission.lifecycle}.
 *
 * <p>Every event is appended to the log and then projected onto the submission row, and the
 * append is what makes the whole thing idempotent: the event id is the log's primary key, so
 * a redelivered event is rejected before the projection runs. That ordering is the design --
 * append first, project second -- because the reverse would let a projection be applied for
 * an event the log does not contain, leaving a status nothing in the history explains.
 *
 * <p>There is deliberately no {@code catch}-and-acknowledge. A record this service cannot
 * read has to reach the dead-letter topic, because the alternative is a submission that stops
 * progressing with no error anywhere -- which is the exact symptom the explicit lifecycle
 * event was introduced to remove.
 */
@Slf4j
@Component
public class SubmissionLifecycleListener {

    private final SubmissionRepository submissions;
    private final SubmissionEventRepository events;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SubmissionLifecycleListener(SubmissionRepository submissions,
                                       SubmissionEventRepository events,
                                       ObjectMapper objectMapper,
                                       MeterRegistry meterRegistry) {
        this.submissions = submissions;
        this.events = events;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${archforge.kafka.topics.submission-lifecycle:submission.lifecycle}",
            groupId = "${archforge.design.consumer-group:design-service-group}",
            containerFactory = "archforgeKafkaListenerContainerFactory"
    )
    @Transactional
    public void onLifecycle(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        SubmissionLifecycleEvent event;
        try {
            event = objectMapper.readValue(payload, SubmissionLifecycleEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("design.lifecycle.unreadable").increment();
            throw new IllegalArgumentException(
                    "Unreadable submission lifecycle record: " + e.getMessage(), e);
        }
        if (event == null || event.submissionId() == null) {
            meterRegistry.counter("design.lifecycle.unidentified").increment();
            throw new IllegalArgumentException("Submission lifecycle record carries no submissionId");
        }
        apply(event);
    }

    void apply(SubmissionLifecycleEvent event) {
        UUID submissionId = parseUuid(event.submissionId());
        UUID eventId = parseUuid(event.eventId());

        int appended = events.append(
                eventId,
                submissionId,
                parseUuid(event.simulationId()),
                parseUuid(event.correlationId()),
                event.stage(),
                event.status().getValue(),
                event.message(),
                json(event.details()),
                event.occurredAt() == null ? java.time.Instant.now() : event.occurredAt());

        if (appended == 0) {
            meterRegistry.counter("design.lifecycle.duplicate").increment();
            log.debug("Submission lifecycle event {} already recorded; ignoring the redelivery",
                    eventId);
            return;
        }
        meterRegistry.counter("design.lifecycle.recorded").increment();

        SubmissionEntity submission = submissions.findByIdForUpdate(submissionId).orElse(null);
        if (submission == null) {
            // The row is created by the REST call that submitted it. An event for a
            // submission this service has never heard of is either a replay from a restored
            // database or a producer reaching the topic directly -- both worth counting and
            // neither worth inventing a row for, since there is no design or owner to attach
            // it to and a fabricated submission would be readable as a real one.
            meterRegistry.counter("design.lifecycle.orphan").increment();
            log.warn("Submission lifecycle event {} references unknown submission {} at stage {}",
                    eventId, submissionId, event.stage());
            return;
        }

        submission.attachSimulation(parseUuid(event.simulationId()));
        SubmissionStatus next = event.status();
        if (next != null && submission.transitionTo(next)) {
            meterRegistry.counter("design.submission.status", "status", next.getValue())
                    .increment();
        }
        submissions.save(submission);

        if (next != null && next.isTerminal()) {
            log.info("Submission {} finished: {} ({} event(s) recorded)", submissionId,
                    next.getValue(), countEvents(submissionId));
        }
    }

    private long countEvents(UUID submissionId) {
        return events.findBySubmissionIdOrderByOccurredAtAsc(submissionId).size();
    }

    /**
     * Parses a UUID, treating an unparseable one as absent.
     *
     * <p>An id that is not a UUID cannot be looked up, and refusing the whole record over a
     * malformed optional field would discard a status transition over a stray space. The
     * submission id itself is not treated this way -- it is the one field the event is
     * meaningless without.
     */
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String json(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Topic this listener reads, named once so a rename has one place to change. */
    static String topic() {
        return TopicNames.SUBMISSION_LIFECYCLE;
    }
}
